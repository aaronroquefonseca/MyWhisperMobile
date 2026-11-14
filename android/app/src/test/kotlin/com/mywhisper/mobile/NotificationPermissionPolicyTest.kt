package com.mywhisper.mobile

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationPermissionPolicyTest {
    @Test fun firstExplicitEnableCanOfferPermission() {
        assertTrue(NotificationPermissionPolicy.shouldOffer(33, allowed = false, requested = false, offered = false))
    }

    @Test fun permissionIsAppWideAndAlreadyGrantedNeedsNoOffer() {
        assertFalse(NotificationPermissionPolicy.shouldOffer(36, allowed = true, requested = false, offered = false))
    }

    @Test fun dismissingGuidancePreventsRepeatedOffersAcrossFeatures() {
        assertFalse(NotificationPermissionPolicy.shouldOffer(33, allowed = false, requested = false, offered = true))
    }

    @Test fun priorPermissionRequestIsNotRepeatedAutomaticallyAfterDenial() {
        assertFalse(NotificationPermissionPolicy.shouldOffer(33, allowed = false, requested = true, offered = false))
    }

    @Test fun olderAndroidDoesNotGetRuntimeNotificationPrompt() {
        assertFalse(NotificationPermissionPolicy.shouldOffer(32, allowed = false, requested = false, offered = false))
    }
}
