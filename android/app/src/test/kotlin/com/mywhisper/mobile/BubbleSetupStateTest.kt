package com.mywhisper.mobile

import org.junit.Assert.assertEquals
import org.junit.Test

class BubbleSetupStateTest {
    @Test fun missingPermissionsAreRequestedInOrder() {
        assertEquals(BubbleSetupState.Step.MICROPHONE, BubbleSetupState(false, false, false, false).next)
        assertEquals(BubbleSetupState.Step.OVERLAY, BubbleSetupState(true, false, false, false).next)
        assertEquals(BubbleSetupState.Step.DICTATION_SERVICE, BubbleSetupState(true, true, false, false).next)
        assertEquals(BubbleSetupState.Step.SPEECH, BubbleSetupState(true, true, true, false).next)
    }

    @Test fun readyDoesNotRequireKeyboardOrNotifications() {
        val setup = BubbleSetupState(true, true, true, true)
        assertEquals(BubbleSetupState.Step.COMPLETE, setup.next)
        assertEquals(4, setup.completed)
    }

    @Test fun revocationReturnsToFirstMissingPermissionWithoutLosingOtherProgress() {
        val setup = BubbleSetupState(true, false, true, true)
        assertEquals(BubbleSetupState.Step.OVERLAY, setup.next)
        assertEquals(3, setup.completed)
    }
    @Test fun cloudSetupNeedsCredentialsForVendorDefaultEndpoint() {
        org.junit.Assert.assertTrue(cloudCredentialsMissing("Cloud", "", ""))
        org.junit.Assert.assertTrue(cloudCredentialsMissing("Cloud", "  ", "  "))
        org.junit.Assert.assertFalse(cloudCredentialsMissing("Cloud", "", "test-key"))
    }

    @Test fun customAndSelfHostedEndpointsMayBeKeyless() {
        org.junit.Assert.assertFalse(cloudCredentialsMissing("Cloud", "http://localhost:8080", ""))
        org.junit.Assert.assertFalse(cloudCredentialsMissing("SelfHosted", "http://localhost:8080", ""))
    }
}
