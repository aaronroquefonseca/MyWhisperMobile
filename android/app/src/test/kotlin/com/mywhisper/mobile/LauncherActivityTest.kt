package com.mywhisper.mobile

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class LauncherActivityTest {
    @Test
    fun launcherEntryFinishesAndOpensRealActivityInIndependentTask() {
        val controller = Robolectric.buildActivity(LauncherActivity::class.java).create()
        val activity = controller.get()
        val intent = shadowOf(activity).nextStartedActivity
        assertEquals(ComponentName(activity, MainActivity::class.java), intent.component)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_SINGLE_TOP != 0)
        assertTrue(activity.isFinishing)
        val info = RuntimeEnvironment.getApplication().packageManager.getActivityInfo(
            ComponentName(activity, LauncherActivity::class.java), 0,
        )
        assertTrue(info.taskAffinity.isNullOrEmpty())
        controller.destroy()
    }
}
