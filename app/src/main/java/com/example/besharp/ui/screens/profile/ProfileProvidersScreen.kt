package com.example.besharp.ui.screens.profile

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.besharp.R
import com.example.besharp.data.analysis.CustomApiConfiguration
import com.example.besharp.data.credentials.CredentialProvider
import com.example.besharp.ui.theme.appBackgroundBrush
import com.example.besharp.ui.components.SettingsSubpageHeader

@Composable
internal fun ProviderCredentialsScreen(
    innerPadding: PaddingValues,
    viewModel: ProfileViewModel,
    onBack: () -> Unit
) {
    val providerStates by viewModel.providerStates.collectAsStateWithLifecycle()
    val credentialDrafts by viewModel.credentialDrafts.collectAsStateWithLifecycle()
    val customApiConfiguration by viewModel.customApiConfiguration.collectAsStateWithLifecycle()
    val customApiDraft by viewModel.customApiDraft.collectAsStateWithLifecycle()
    val customApiSaveState by viewModel.customApiSaveState.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.appBackgroundBrush)
            .padding(innerPadding)
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        SettingsSubpageHeader(stringResource(R.string.settings_providers), onBack)

        OpenAiCredentialCard(
            state = providerStates[CredentialProvider.OPENAI] ?: ProviderCredentialState(),
            credential = credentialDrafts[CredentialProvider.OPENAI].orEmpty(),
            onCredentialChange = { viewModel.updateCredentialDraft(CredentialProvider.OPENAI, it) },
            onSave = { viewModel.saveCredential(CredentialProvider.OPENAI) },
            onValidate = { viewModel.validateCredential(CredentialProvider.OPENAI) },
            onDelete = { viewModel.deleteCredential(CredentialProvider.OPENAI) },
            modifier = Modifier.fillMaxWidth()
        )

        CustomApiConfigurationCard(
            configuration = customApiConfiguration,
            hasToken = providerStates[CredentialProvider.CUSTOM_API]?.isConfigured == true,
            draft = customApiDraft,
            saveState = customApiSaveState,
            onDraftChange = viewModel::updateCustomApiDraft,
            onSave = viewModel::saveCustomApiConfiguration,
            onDelete = viewModel::deleteCustomApiConfiguration
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CustomApiConfigurationCard(
    configuration: CustomApiConfiguration?,
    hasToken: Boolean,
    draft: CustomApiDraft,
    saveState: SaveState,
    onDraftChange: (CustomApiDraft) -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit
) {
    var showToken by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val canSave = draft.displayName.isNotBlank() && draft.endpointUrl.isNotBlank() &&
        draft.modelId.isNotBlank() && saveState !is SaveState.Saving
    LaunchedEffect(saveState) {
        if (saveState is SaveState.Success) showToken = false
    }

    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RectangleShape,
        border = BorderStroke(2.dp, MaterialTheme.colorScheme.onSurface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.providers_custom_api), fontWeight = FontWeight.Bold)
                Text(
                    if (configuration == null) {
                        stringResource(R.string.providers_not_configured)
                    } else {
                        stringResource(R.string.providers_configured)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (configuration == null) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.secondary
                    }
                )
            }
            Text(
                stringResource(R.string.providers_custom_api_description),
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = draft.displayName,
                onValueChange = { onDraftChange(draft.copy(displayName = it)) },
                label = { Text(stringResource(R.string.providers_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = draft.endpointUrl,
                onValueChange = { onDraftChange(draft.copy(endpointUrl = it)) },
                label = { Text(stringResource(R.string.providers_endpoint)) },
                placeholder = { Text(stringResource(R.string.providers_endpoint_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = draft.modelId,
                onValueChange = { onDraftChange(draft.copy(modelId = it)) },
                label = { Text(stringResource(R.string.providers_model)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = draft.accessToken,
                onValueChange = { onDraftChange(draft.copy(accessToken = it)) },
                label = {
                    Text(
                        stringResource(
                            if (hasToken) R.string.providers_new_optional_token
                            else R.string.providers_optional_token
                        )
                    )
                },
                singleLine = true,
                visualTransformation = if (showToken) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                trailingIcon = {
                    IconButton(onClick = { showToken = !showToken }) {
                        Icon(
                            if (showToken) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = stringResource(
                                if (showToken) R.string.providers_hide_token
                                else R.string.providers_show_token
                            )
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(10.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(enabled = canSave, onClick = onSave, shape = RectangleShape) {
                    if (saveState is SaveState.Saving) {
                        CircularProgressIndicator(modifier = Modifier.height(16.dp), strokeWidth = 2.dp)
                    } else Text(stringResource(R.string.action_save))
                }
                if (configuration != null) {
                    IconButton(
                        enabled = saveState !is SaveState.Saving,
                        onClick = { confirmDelete = true }
                    ) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = stringResource(R.string.providers_delete_custom_api)
                        )
                    }
                }
            }
            val message = when (saveState) {
                SaveState.Idle, SaveState.Saving -> null
                SaveState.Success -> stringResource(R.string.providers_custom_api_saved)
                is SaveState.Error -> saveState.message
            }
            message?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (saveState is SaveState.Error) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite }
                )
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            shape = RectangleShape,
            title = { Text(stringResource(R.string.providers_delete_custom_api_title)) },
            text = { Text(stringResource(R.string.providers_delete_custom_api_message)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDelete()
                }) {
                    Text(stringResource(R.string.action_remove), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text(stringResource(R.string.action_cancel).uppercase())
                }
            }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OpenAiCredentialCard(
    state: ProviderCredentialState,
    credential: String,
    onCredentialChange: (String) -> Unit,
    onSave: () -> Unit,
    onValidate: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uriHandler = LocalUriHandler.current
    var showCredential by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    LaunchedEffect(state.saveState) {
        if (state.saveState is SaveState.Success) showCredential = false
    }

    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RectangleShape,
        border = BorderStroke(2.dp, MaterialTheme.colorScheme.onSurface),
        modifier = modifier
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(CredentialProvider.OPENAI.displayName.uppercase(), fontWeight = FontWeight.Bold)
                Text(
                    text = stringResource(
                        if (state.isConfigured) R.string.providers_configured
                        else R.string.providers_not_configured
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (state.isConfigured) MaterialTheme.colorScheme.secondary
                    else MaterialTheme.colorScheme.onSurface
                )
            }

            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = credential,
                onValueChange = onCredentialChange,
                modifier = Modifier.fillMaxWidth(),
                label = {
                    Text(
                        stringResource(
                            if (state.isConfigured) R.string.providers_new_api_key
                            else R.string.providers_api_key
                        )
                    )
                },
                singleLine = true,
                visualTransformation = if (showCredential) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                trailingIcon = {
                    IconButton(onClick = { showCredential = !showCredential }) {
                        Icon(
                            if (showCredential) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = stringResource(
                                if (showCredential) R.string.providers_hide_key
                                else R.string.providers_show_key
                            )
                        )
                    }
                }
            )

            Spacer(Modifier.height(10.dp))
            TextButton(onClick = { uriHandler.openUri("https://openai.com/verify/") }) {
                Text(stringResource(R.string.providers_open_openai_verifier))
            }
            Spacer(Modifier.height(10.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    enabled = (credential.isNotBlank() || state.isConfigured) &&
                        state.saveState !is SaveState.Saving,
                    onClick = onSave,
                    shape = RectangleShape
                ) {
                    if (state.saveState is SaveState.Saving) {
                        CircularProgressIndicator(modifier = Modifier.height(16.dp), strokeWidth = 2.dp)
                    } else {
                        Text(stringResource(R.string.action_save))
                    }
                }
                if (state.isConfigured) {
                    OutlinedButton(
                        enabled = !state.isValidating,
                        onClick = onValidate,
                        shape = RectangleShape
                    ) {
                        if (state.isValidating) {
                            CircularProgressIndicator(modifier = Modifier.height(16.dp), strokeWidth = 2.dp)
                        } else {
                            Text(stringResource(R.string.action_validate))
                        }
                    }
                    IconButton(
                        enabled = state.saveState !is SaveState.Saving && !state.isValidating,
                        onClick = { confirmDelete = true }
                    ) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = stringResource(R.string.providers_delete_key)
                        )
                    }
                }
            }

            state.validationMessage?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = when (state.validationSucceeded) {
                        true -> MaterialTheme.colorScheme.secondary
                        false -> MaterialTheme.colorScheme.error
                        null -> MaterialTheme.colorScheme.onSurface
                    },
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite }
                )
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            shape = RectangleShape,
            title = { Text(stringResource(R.string.providers_delete_credential_title)) },
            text = {
                Text(stringResource(R.string.providers_delete_credential_message, CredentialProvider.OPENAI.displayName))
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDelete()
                }) {
                    Text(stringResource(R.string.action_remove), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text(stringResource(R.string.action_cancel).uppercase())
                }
            }
        )
    }
}
