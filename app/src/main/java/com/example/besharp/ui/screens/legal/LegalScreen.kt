package com.example.besharp.ui.screens.legal

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.example.besharp.R
import com.example.besharp.ui.components.SettingsSubpageHeader
import com.example.besharp.ui.theme.appBackgroundBrush
import kotlinx.coroutines.launch

@Composable
fun LegalScreen(
    innerPadding: PaddingValues,
    remoteConsentAccepted: Boolean,
    onRevokeRemoteConsent: suspend () -> Boolean,
    onBack: () -> Unit
) {
    var confirmRevoke by remember { mutableStateOf(false) }
    var revokeFailed by remember { mutableStateOf(false) }
    var revoking by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.appBackgroundBrush)
            .padding(innerPadding)
    ) {
    Column(
        modifier = Modifier
            .widthIn(max = 760.dp)
            .fillMaxWidth()
            .align(Alignment.TopCenter)
            .padding(horizontal = 20.dp, vertical = 16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        SettingsSubpageHeader(stringResource(R.string.privacy_title), onBack)
            Text(
                text = stringResource(R.string.privacy_policy_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )

        Spacer(modifier = Modifier.height(16.dp))

        LegalSection(
            title = stringResource(R.string.privacy_remote_consent_title),
            body = if (remoteConsentAccepted) {
                stringResource(R.string.privacy_remote_consent_granted)
            } else {
                stringResource(R.string.privacy_remote_consent_denied)
            }
        )
        if (remoteConsentAccepted) {
            TextButton(onClick = {
                revokeFailed = false
                confirmRevoke = true
            }) {
                Text(stringResource(R.string.privacy_revoke_consent))
            }
        }

        LegalSection(
            title = stringResource(R.string.privacy_collection_title),
            body = stringResource(R.string.privacy_collection_body)
        )

        LegalSection(
            title = stringResource(R.string.privacy_permissions_title),
            body = stringResource(R.string.privacy_permissions_body)
        )

        LegalSection(
            title = stringResource(R.string.privacy_local_storage_title),
            body = stringResource(R.string.privacy_local_storage_body)
        )

        LegalSection(
            title = stringResource(R.string.privacy_external_processing_title),
            body = stringResource(R.string.privacy_external_processing_body)
        )

        LegalSection(
            title = stringResource(R.string.privacy_confidentiality_title),
            body = stringResource(R.string.privacy_confidentiality_body)
        )

        LegalSection(
            title = stringResource(R.string.privacy_user_rights_title),
            body = stringResource(R.string.privacy_user_rights_body)
        )

        LegalSection(
            title = stringResource(R.string.privacy_terms_title),
            body = stringResource(R.string.privacy_terms_body)
        )

        LegalSection(
            title = stringResource(R.string.privacy_contact_title),
            body = stringResource(R.string.privacy_contact_body)
        )

        Spacer(modifier = Modifier.height(32.dp))

        Text(
            text = stringResource(R.string.privacy_last_updated),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onBackground
        )

        Spacer(modifier = Modifier.height(16.dp))
    }
    }

    if (confirmRevoke) {
        AlertDialog(
            onDismissRequest = { if (!revoking) confirmRevoke = false },
            shape = RectangleShape,
            title = { Text(stringResource(R.string.privacy_revoke_consent)) },
            text = {
                Column {
                    Text(stringResource(R.string.privacy_revoke_message))
                    if (revokeFailed) {
                        Text(
                            stringResource(R.string.privacy_revoke_failed),
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = !revoking, onClick = {
                    revoking = true
                    scope.launch {
                        try {
                            revokeFailed = !onRevokeRemoteConsent()
                            if (!revokeFailed) confirmRevoke = false
                        } finally {
                            revoking = false
                        }
                    }
                }) {
                    Text(stringResource(R.string.action_revoke))
                }
            },
            dismissButton = {
                TextButton(enabled = !revoking, onClick = { confirmRevoke = false }) {
                    Text(stringResource(R.string.action_cancel).uppercase())
                }
            }
        )
    }
}

@Composable
private fun LegalSection(title: String, body: String) {
    Spacer(modifier = Modifier.height(12.dp))

    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.secondary
    )

    Spacer(modifier = Modifier.height(6.dp))

    Text(
        text = body,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onBackground,
        lineHeight = MaterialTheme.typography.bodySmall.lineHeight
    )
}
