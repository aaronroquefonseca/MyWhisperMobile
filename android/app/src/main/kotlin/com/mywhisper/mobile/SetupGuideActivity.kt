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
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
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
        private val CARD_WIDTH = 180.dp

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
    // The card draws only once in PiP: the state change forces a fresh frame at the PiP size
    // (Android 16 otherwise kept the full-screen frame) and skips a full-screen flash.
    private val inPip = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        step = SettingsGuide.Step.valueOf(intent.getStringExtra(EXTRA_STEP) ?: SettingsGuide.Step.ACCESSIBILITY.name)
        // Recreated after process death: the poll that owned this window is gone.
        if (savedInstanceState != null) { finishAndRemoveTask(); return }
        current = WeakReference(this)
        val restricted = step == SettingsGuide.Step.RESTRICTED
        val title = getString(if (restricted) R.string.guide_restricted_title else R.string.guide_a11y_title)
        val steps = resources.getStringArray(if (restricted) R.array.guide_restricted_steps else SettingsGuide.a11yStepsRes(Build.MANUFACTURER)).toList()
        val noteTitle = getString(if (restricted) R.string.guide_restricted_then else R.string.guide_a11y_allow)
        val noteBody = if (restricted) null else getString(R.string.guide_a11y_why)
        val theme = getSharedPreferences("mw_state", MODE_PRIVATE).getString(MainActivity.PREF_THEME, "System")
        setContent {
            val dark = when (theme) { "Light" -> false; "Dark" -> true; else -> isSystemInDarkTheme() }
            MaterialTheme(colorScheme = whisperColorScheme(dark)) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                    if (inPip.value) FitToWindow { GuideCard(title, steps, if (restricted) Icons.Filled.Info else Icons.Filled.CheckCircle, noteTitle, noteBody) }
                }
            }
        }
    }

    @Composable
    private fun GuideCard(title: String, steps: List<String>, noteIcon: ImageVector, noteTitle: String, noteBody: String?) {
        val colors = MaterialTheme.colorScheme
        val type = MaterialTheme.typography
        Column(Modifier.width(CARD_WIDTH).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(22.dp).background(colors.primary, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(painterResource(R.drawable.ic_mic), null, Modifier.size(13.dp), tint = colors.onPrimary)
                }
                Text("MyWhisper", Modifier.padding(start = 6.dp), style = type.labelMedium, color = colors.onSurfaceVariant)
            }
            Text(title, style = type.titleMedium, fontWeight = FontWeight.SemiBold, color = colors.onSurface)
            steps.forEachIndexed { i, text ->
                Row {
                    Box(Modifier.defaultMinSize(22.dp, 22.dp).background(colors.primaryContainer, CircleShape), contentAlignment = Alignment.Center) {
                        Text("${i + 1}", style = type.labelMedium, fontWeight = FontWeight.Bold, color = colors.onPrimaryContainer)
                    }
                    Text(text, Modifier.padding(start = 8.dp, top = 1.dp), style = type.bodyMedium, color = colors.onSurface)
                }
            }
            Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = colors.secondaryContainer) {
                Row(Modifier.padding(10.dp)) {
                    Icon(noteIcon, null, Modifier.size(18.dp), tint = colors.onSecondaryContainer)
                    Column(Modifier.padding(start = 8.dp)) {
                        Text(noteTitle, style = type.labelLarge, fontWeight = FontWeight.SemiBold, color = colors.onSecondaryContainer)
                        noteBody?.let { Text(it, style = type.bodySmall, color = colors.onSecondaryContainer) }
                    }
                }
            }
        }
    }

    /**
     * The system picks the PiP size, so the card is laid out at [CARD_WIDTH], measured, then
     * composed again at a density scaled to fit the window both ways: same layout, sharp text.
     */
    @Composable
    private fun FitToWindow(card: @Composable () -> Unit) {
        val base = LocalDensity.current
        SubcomposeLayout { constraints ->
            val probe = subcompose("probe", card).first().measure(Constraints())
            // 0.96: rounding at the new density can add a few pixels. Capped for large PiP windows.
            val scale = 0.96f * minOf(constraints.maxWidth / probe.width.toFloat(), constraints.maxHeight / probe.height.toFloat(), 1.6f)
            val scaled = Density(base.density * scale, base.fontScale)
            val placeable = subcompose("card") { CompositionLocalProvider(LocalDensity provides scaled, card) }
                .first().measure(Constraints(maxWidth = constraints.maxWidth, maxHeight = constraints.maxHeight))
            layout(constraints.maxWidth, constraints.maxHeight) {
                placeable.place((constraints.maxWidth - placeable.width) / 2, (constraints.maxHeight - placeable.height) / 2)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (entered) return
        entered = true
        val params = PictureInPictureParams.Builder().setAspectRatio(Rational(3, 4)).apply {
            // Portrait and narrow: the PiP lands at the side, so the centered buttons of
            // Android's warning dialogs stay tappable. Apps cannot choose the position.
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
        inPip.value = isInPictureInPictureMode
        if (isInPictureInPictureMode) openSettings() else finishAndRemoveTask() // dismissed or expanded
    }

    private fun openSettings() {
        if (settingsOpened) return
        settingsOpened = true
        try {
            // Settings gets its own task: launching into this pinned task would expand it.
            // Cleared so a Settings task left on a deeper page opens on the page the PiP describes.
            startActivity(SettingsGuide.intent(this, step).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        } catch (_: Exception) {
            finishAndRemoveTask()
        }
    }

    override fun onDestroy() {
        if (current?.get() === this) current = null
        super.onDestroy()
    }
}
