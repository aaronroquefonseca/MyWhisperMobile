package com.mywhisper.mobile

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Sync-tab split for the pairing parity fix: our own sent requests
 * land in WAITING (cancel only, never Accept), incoming asks stay in
 * INCOMING. Pure JVM: exercises the same [peerBucket] the tab uses.
 */
class SyncPeerBucketTest {

    @Test
    fun outgoing_only_isWaiting_neverIncoming() {
        assertEquals(PeerBucket.WAITING, peerBucket(false, false, true, false))
        // Even when discoverable on the LAN it must not offer Accept.
        assertEquals(PeerBucket.WAITING, peerBucket(false, false, true, true))
    }

    @Test
    fun incoming_only_isIncoming() {
        assertEquals(PeerBucket.INCOMING, peerBucket(false, true, false, false))
        assertEquals(PeerBucket.INCOMING, peerBucket(false, true, false, true))
    }

    @Test
    fun mutualRequest_staysIncoming_soAcceptClearsBoth() {
        assertEquals(PeerBucket.INCOMING, peerBucket(false, true, true, false))
    }

    @Test
    fun trusted_winsOverEverything() {
        assertEquals(PeerBucket.TRUSTED, peerBucket(true, false, false, false))
        assertEquals(PeerBucket.TRUSTED, peerBucket(true, true, true, true))
    }

    @Test
    fun nearby_needsDiscovery_andNoFlags() {
        assertEquals(PeerBucket.NEARBY, peerBucket(false, false, false, true))
        assertEquals(PeerBucket.OTHER, peerBucket(false, false, false, false))
    }
}
