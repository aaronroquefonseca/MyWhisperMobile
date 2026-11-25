package com.mywhisper.mobile

import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.lang.ref.WeakReference

/**
 * Small picture-in-picture window that keeps the instructions visible while the
 * user navigates inside Settings (#125). Runs in its own task so MainActivity
 * never enters PiP. Static text: the current Settings screen is not observable.
 * [SettingsGuide.watch] closes it on success or deadline; returning by hand
 * closes it from MainActivity; dismissing or expanding it just finishes it, and
 * the auto-return poll keeps running as in the non-PiP flow.
 */
class SetupGuideActivity : ComponentActivity() {
    companion object {
        private const val EXTRA_STEP = "step"
        private var current: WeakReference<SetupGuideActivity>? = null

        internal fun start(ctx: Context, step: SettingsGuide.Step) {
            ctx.startActivity(Intent(ctx, SetupGuideActivity::class.java)
                .putExtra(EXTRA_STEP, step.name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        }

        fun close() {
            current?.get()?.finishAndRemoveTask()
            current = null
        }
    }

    private lateinit var step: SettingsGuide.Step
    private var entered = false
    private var settingsOpened = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        step = SettingsGuide.Step.valueOf(intent.getStringExtra(EXTRA_STEP) ?: SettingsGuide.Step.ACCESSIBILITY.name)
        // Recreated after process death: the poll that owned this window is gone.
        if (savedInstanceState != null) { finishAndRemoveTask(); return }
        current = WeakReference(this)
        val lines = when (step) {
            SettingsGuide.Step.RESTRICTED -> listOf(R.string.guide_restricted_title, R.string.guide_restricted_path, R.string.guide_restricted_then)
            else -> listOf(R.string.guide_a11y_title, SettingsGuide.a11yPathRes(Build.MANUFACTURER), R.string.guide_a11y_warning)
        }.map(::getString)
        val theme = getSharedPreferences("mw_state", MODE_PRIVATE).getString(MainActivity.PREF_THEME, "System")
        setContent {
            val dark = when (theme) { "Light" -> false; "Dark" -> true; else -> isSystemInDarkTheme() }
            MaterialTheme(colorScheme = whisperColorScheme(dark)) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                    val text = buildAnnotatedString {
                        lines.forEachIndexed { i, line ->
                            if (i > 0) append("\n")
                            if (i == 0) withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)) { append(line) }
                            else append(line)
                        }
                    }
                    // Fills the PiP window: the system picks its size, so the text scales to it.
                    BasicText(text, Modifier.padding(8.dp),
                        style = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                        autoSize = TextAutoSize.StepBased(minFontSize = 8.sp, maxFontSize = 32.sp))
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (entered) return
        entered = true
        val params = PictureInPictureParams.Builder().setAspectRatio(Rational(1, 1)).apply {
            // Text, not video: cross-fade on resize instead of stretching.
            if (Build.VERSION.SDK_INT >= 31) setSeamlessResizeEnabled(false)
        }.build()
        val ok = try { enterPictureInPictureMode(params) } catch (_: Exception) { false }
        if (!ok) {
            // Fallback: the current flow; the step's hint line in the app already explains it.
            openSettings()
            finishAndRemoveTask()
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        // Settings opens once the window is small, so the window never covers it full screen.
        if (isInPictureInPictureMode) openSettings() else finishAndRemoveTask() // dismissed or expanded
    }

    private fun openSettings() {
        if (settingsOpened) return
        settingsOpened = true
        try {
            // Settings gets its own task: launching into this pinned task would expand it.
            startActivity(SettingsGuide.intent(this, step).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            finishAndRemoveTask()
        }
    }

    override fun onDestroy() {
        if (current?.get() === this) current = null
        super.onDestroy()
    }
}
