package com.example.besharp.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.besharp.R
import com.example.besharp.ui.common.resolutionLabel
import com.example.besharp.ui.model.AssessmentUi

@Composable
fun AssessmentReplacementDialog(
    previous: AssessmentUi,
    candidate: AssessmentUi,
    onKeepPrevious: () -> Unit,
    onKeepCandidate: () -> Unit
) {
    AlertDialog(
        onDismissRequest = {},
        shape = RectangleShape,
        title = { Text(stringResource(R.string.result_replace_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(
                        R.string.result_replace_message,
                        candidate.imageDisplayName
                    )
                )
                Text(
                    stringResource(
                        R.string.result_previous_label,
                        resolutionLabel(previous.finalResolution, previous.state)
                    )
                )
                Text(
                    stringResource(
                        R.string.result_new_label,
                        resolutionLabel(candidate.finalResolution, candidate.state)
                    )
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onKeepPrevious) {
                Text(stringResource(R.string.result_keep_previous))
            }
        },
        confirmButton = {
            TextButton(onClick = onKeepCandidate) {
                Text(stringResource(R.string.result_keep_new))
            }
        }
    )
}
