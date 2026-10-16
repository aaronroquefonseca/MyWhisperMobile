package com.mywhisper.mobile

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp

/**
 * ONE test-button style for every provider Test call site (chain
 * rows, add/edit dialogs, local self-test): min height + content
 * padding identical to the sibling Add buttons (M3
 * [ButtonDefaults], which the Add buttons use implicitly), label
 * vertically centered by the button Row. Text-only buttons get no
 * M3 minimum of their own (height = content + padding), so without
 * this floor they render shorter than the icon+text Add buttons.
 *
 * No per-site sizes: call sites use [ProviderTestButton] (via
 * `ProviderTestRow`), never their own height/padding.
 */
object ProviderTestButtonStyle {
    /** Same floor as the Add buttons ([ButtonDefaults.MinHeight]). */
    val MinHeight: Dp = ButtonDefaults.MinHeight

    /** Same padding as the Add buttons ([ButtonDefaults.ContentPadding]). */
    val ContentPadding: PaddingValues = ButtonDefaults.ContentPadding
}

/**
 * THE provider Test button: shared [ProviderTestButtonStyle],
 * busy copy swaps in while a probe runs. Sizing only — enabled
 * gating and result reporting stay in `ProviderTestRow`.
 */
@Composable
fun ProviderTestButton(
    enabled: Boolean,
    busy: Boolean,
    onTest: () -> Unit,
) {
    OutlinedButton(
        enabled = enabled && !busy,
        onClick = onTest,
        modifier = Modifier.heightIn(min = ProviderTestButtonStyle.MinHeight),
        contentPadding = ProviderTestButtonStyle.ContentPadding,
    ) {
        Text(
            text = if (busy) stringResource(R.string.provider_test_testing)
            else stringResource(R.string.provider_test_run),
            textAlign = TextAlign.Center,
        )
    }
}
