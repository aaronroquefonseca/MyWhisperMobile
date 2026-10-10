//! Pill visualizer preference types (serde surface only).
//!
//! Desktop `viz.rs` is GTK-bound (pill overlay — Android has no pill;
//! the IME button replaces it). Only the four pref enums are needed so
//! `settings.rs` stays a verbatim port and settings JSON stays
//! compatible. If desktop adds variants, mirror them here.

#[derive(Debug, Clone, Copy, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub enum VizMode {
    History,
    Tape,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub enum VizTheme {
    White,
    Grey,
    Red,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub enum Ornament {
    None,
    Mark,
    Mic,
    Dot,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub enum IdleStyle {
    Still,
    Silhouette,
    Calm,
}
