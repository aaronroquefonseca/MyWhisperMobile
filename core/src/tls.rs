//! Shared TLS client configuration for outbound HTTPS (remote STT/LLM).
//!
//! reqwest 0.13's `rustls` feature installs `rustls-platform-verifier`,
//! which on Android needs its JVM helper and an explicit
//! `init_with_env(context)` call. Without that init it panics inside the
//! async runtime on the first certificate check:
//! `Expect rustls-platform-verifier to be initialized`. The panic tears
//! down the connection mid-handshake and reqwest surfaces it as
//! `reqwest::Error { kind: Body, source: SendError { kind: Disconnected } }`
//! while every Java `HttpURLConnection` probe (the settings "Test"
//! buttons) keeps passing. Owner repro: IME dictation and the floating
//! bubble both failed against a valid Let's Encrypt endpoint.
//!
//! We therefore build the rustls config ourselves from the bundled
//! Mozilla roots (`webpki-roots`) on every platform: no JNI handshake,
//! no extra AAR, and identical verification on host and device. Public
//! CAs are covered; a private CA would need its root added here.

use std::sync::{Arc, OnceLock};

/// Shared Mozilla root store (built once per process).
fn roots() -> &'static rustls::RootCertStore {
    static ROOTS: OnceLock<rustls::RootCertStore> = OnceLock::new();
    ROOTS.get_or_init(|| {
        let mut roots = rustls::RootCertStore::empty();
        roots.extend(webpki_roots::TLS_SERVER_ROOTS.iter().cloned());
        roots
    })
}

/// rustls client config rooted in `webpki-roots`.
pub fn client_config() -> Result<rustls::ClientConfig, String> {
    let provider = rustls::crypto::aws_lc_rs::default_provider();
    let config = rustls::ClientConfig::builder_with_provider(Arc::new(provider))
        .with_safe_default_protocol_versions()
        .map_err(|e| format!("tls versions: {e}"))?
        .with_root_certificates(roots().clone())
        .with_no_client_auth();
    Ok(config)
}

/// Blocking client builder with the shared TLS config applied. Callers
/// chain their own connect/total timeouts and `.build()`.
pub fn blocking_client() -> Result<reqwest::blocking::ClientBuilder, String> {
    Ok(reqwest::blocking::Client::builder().use_preconfigured_tls(client_config()?))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn roots_are_populated() {
        assert!(roots().len() > 50, "webpki root store looks empty");
    }

    #[test]
    fn config_builds() {
        let cfg = client_config().expect("tls config");
        assert_eq!(cfg.alpn_protocols.len(), 0);
    }
}
