package com.example.besharp.ui.screens.analysis

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.example.besharp.R
import com.example.besharp.domain.model.AnalysisInputType

@Composable
internal fun RemoteConsentDialog(
    providerNames: String,
    inputTypes: Set<AnalysisInputType>,
    saveFailed: Boolean,
    confirming: Boolean,
    onDismiss: () -> Unit,
    onOpenPrivacy: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { if (!confirming) onDismiss() },
        shape = RectangleShape,
        title = { Text(stringResource(R.string.analysis_remote_consent_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.analysis_remote_consent_recipients, providerNames))
                if (AnalysisInputType.NORMALIZED_IMAGE in inputTypes) {
                    Text(stringResource(R.string.analysis_remote_consent_copy))
                }
                if (AnalysisInputType.ORIGINAL_FILE in inputTypes) {
                    Text(
                        stringResource(R.string.analysis_remote_consent_original)
                    )
                }
                Text(stringResource(R.string.analysis_remote_consent_storage))
                Text(stringResource(R.string.analysis_remote_consent_disclaimer))
                if (saveFailed) {
                    Text(
                        stringResource(R.string.analysis_remote_consent_save_failed),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !confirming, onClick = onConfirm) {
                Text(stringResource(R.string.analysis_accept_and_analyze))
            }
        },
        dismissButton = {
            Row {
                TextButton(enabled = !confirming, onClick = onOpenPrivacy) {
                    Text(stringResource(R.string.analysis_view_privacy))
                }
                TextButton(enabled = !confirming, onClick = onDismiss) {
                    Text(stringResource(R.string.action_cancel).uppercase())
                }
            }
        }
    )
}
