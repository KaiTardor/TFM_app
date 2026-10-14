package com.example.besharp.ui.screens.detail

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import coil.compose.AsyncImage
import com.example.besharp.R
import com.example.besharp.domain.model.EvidenceSignal
import com.example.besharp.domain.model.EvidenceOutcome
import com.example.besharp.domain.model.EvidenceType
import com.example.besharp.domain.model.FinalResolution
import com.example.besharp.ui.model.AnalysisJobState
import com.example.besharp.ui.model.AnalysisJobUi
import com.example.besharp.ui.model.AssessmentUi
import com.example.besharp.ui.model.AssessmentState
import com.example.besharp.ui.model.latestAssessmentForImage
import com.example.besharp.domain.model.GalleryPhoto
import com.example.besharp.ui.common.evidenceTypeLabel
import com.example.besharp.ui.common.formatAnalysisDate
import com.example.besharp.ui.common.outcomeLabel
import com.example.besharp.ui.common.providerStatusLabel
import com.example.besharp.ui.common.verificationLabel
import com.example.besharp.ui.common.resolutionLabel
import com.example.besharp.ui.components.ExpandableSection
import com.example.besharp.ui.theme.appBackgroundBrush
import com.example.besharp.ui.theme.appChromeColors
import com.example.besharp.ui.theme.verdictColors

@Composable
fun PhotoDetailScreen(
    innerPadding: PaddingValues,
    photo: GalleryPhoto,
    assessments: List<AssessmentUi>,
    onBack: () -> Unit,
    onAnalyze: () -> Unit
) {
    val context = LocalContext.current
    var imageFailed by remember(photo.contentUri) { mutableStateOf(false) }
    var imageAttempt by remember(photo.contentUri) { mutableIntStateOf(0) }
    val latest = remember(assessments, photo.contentUri) {
        latestAssessmentForImage(assessments, photo.contentUri)
    }
    val chromeColors = MaterialTheme.appChromeColors

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.appBackgroundBrush)
            .padding(innerPadding)
            .verticalScroll(rememberScrollState())
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(chromeColors.container)
                .border(2.dp, MaterialTheme.colorScheme.onSurface)
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.action_back),
                    tint = chromeColors.content
                )
            }
            Text(
                photo.displayName,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                color = chromeColors.content
            )
            IconButton(enabled = !imageFailed, onClick = { shareImage(context, photo) }) {
                Icon(
                    Icons.Default.Share,
                    contentDescription = stringResource(R.string.detail_share_image),
                    tint = chromeColors.content
                )
            }
        }

        latest?.let { assessment ->
            ResolutionHero(
                assessment = assessment,
                modifier = Modifier.padding(16.dp)
            )
        }

        key(photo.contentUri, imageAttempt) {
            AsyncImage(
                model = Uri.parse(photo.contentUri),
                contentDescription = photo.displayName,
                contentScale = ContentScale.Fit,
                onError = { imageFailed = true },
                onSuccess = { imageFailed = false },
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(4f / 3f)
                    .background(Color(0xFF171717))
                    .border(2.dp, MaterialTheme.colorScheme.onSurface)
            )
        }

        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (imageFailed) {
                Text(stringResource(R.string.detail_image_unavailable))
                OutlinedButton(onClick = { imageAttempt++ }, shape = RectangleShape) {
                    Text(stringResource(R.string.action_retry))
                }
            }
            Button(
                enabled = !imageFailed,
                onClick = onAnalyze,
                shape = RectangleShape,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.AutoAwesome, contentDescription = null)
                Text(" ${stringResource(R.string.detail_analyze_again)}")
            }

            if (latest == null) {
                Surface(
                    shape = RectangleShape,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface),
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.detail_no_history), Modifier.padding(18.dp))
                }
            } else {
                val signals = latest.jobs
                    .flatMap { it.result?.evidenceSignals.orEmpty() }
                    .filter { it.outcome != EvidenceOutcome.NOT_CHECKED }
                val signalGroups = signals.groupBy(EvidenceSignal::type)
                ExpandableSection(
                    title = stringResource(R.string.detail_credentials),
                    subtitle = stringResource(R.string.detail_signal_type_count, signalGroups.size),
                    initiallyExpanded = signals.isNotEmpty()
                ) {
                    if (signals.isEmpty()) {
                        Text(
                            stringResource(R.string.detail_no_provenance_signals),
                            style = MaterialTheme.typography.bodySmall
                        )
                    } else {
                        signalGroups.forEach { (type, groupedSignals) ->
                            EvidenceSignalGroupRow(type, groupedSignals)
                        }
                    }
                }

                latest.jobs.forEach { entry ->
                    key(entry.jobId) {
                        ExpandableSection(
                            title = entry.providerName.uppercase(),
                            subtitle = providerStatusLabel(entry)
                        ) {
                            ProviderResult(entry)
                        }
                    }
                }

                ExpandableSection(
                    title = stringResource(R.string.detail_technical_info),
                    subtitle = stringResource(
                        R.string.detail_policy,
                        latest.policyVersion ?: stringResource(R.string.detail_policy_pending)
                    )
                ) {
                    Text(latest.reason ?: stringResource(R.string.detail_no_final_resolution))
                    Text(
                        stringResource(
                            R.string.detail_job_signal_counts,
                            latest.jobs.size,
                            latest.signalCount
                        ),
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (latest.conflictingSignalIds.isNotEmpty()) {
                        Text(
                            stringResource(
                                R.string.detail_conflicting_signals,
                                latest.conflictingSignalIds.size
                            ),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    val timestamp = latest.completedAt ?: latest.createdAt
                    Text(formatAnalysisDate(timestamp), style = MaterialTheme.typography.labelSmall)
                }
            }

            Text(
                stringResource(R.string.detail_authenticity_disclaimer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun ResolutionHero(assessment: AssessmentUi, modifier: Modifier = Modifier) {
    val resolution = assessment.finalResolution
    val accent = resolutionColor(resolution)
    val icon = when (resolution) {
        FinalResolution.AI_GENERATED_OR_EDITED -> Icons.Default.AutoAwesome
        FinalResolution.POSSIBLY_SYNTHETIC -> Icons.Default.Warning
        FinalResolution.VERIFIED_CAPTURE -> Icons.Default.Verified
        FinalResolution.NO_SUFFICIENT_EVIDENCE -> Icons.Default.SearchOff
        FinalResolution.INCONCLUSIVE, null -> Icons.AutoMirrored.Filled.HelpOutline
    }
    Surface(
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(3.dp, accent),
        shape = RectangleShape,
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(32.dp))
            Text(
                resolutionLabel(resolution, assessment.state),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = accent
            )
            Text(assessment.summary ?: stringResource(R.string.detail_assessment_running))
        }
    }
}

@Composable
private fun resolutionColor(resolution: FinalResolution?): Color = when (resolution) {
    FinalResolution.AI_GENERATED_OR_EDITED,
    FinalResolution.POSSIBLY_SYNTHETIC -> MaterialTheme.verdictColors.synthetic
    FinalResolution.VERIFIED_CAPTURE -> MaterialTheme.verdictColors.authentic
    FinalResolution.NO_SUFFICIENT_EVIDENCE -> MaterialTheme.colorScheme.primary
    FinalResolution.INCONCLUSIVE, null -> MaterialTheme.verdictColors.inconclusive
}

@Composable
private fun ProviderResult(entry: AnalysisJobUi) {
    if (entry.state == AnalysisJobState.FAILED) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.Error,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(18.dp)
            )
            Text(
                " ${entry.errorMessage ?: stringResource(R.string.detail_analysis_failed)}",
                color = MaterialTheme.colorScheme.error
            )
        }
    }
    entry.result?.let { result ->
        result.syntheticProbability?.let { probability ->
            LinearProgressIndicator(
                progress = { probability / 100f },
                modifier = Modifier.fillMaxWidth()
            )
            Text(stringResource(R.string.detail_synthetic_estimate, probability))
        }
        Text(result.summary)
        Text(verificationLabel(result.verificationLevel), style = MaterialTheme.typography.bodySmall)
        result.indicators.forEach { Text("· $it", style = MaterialTheme.typography.bodySmall) }
        Text(
            stringResource(
                R.string.detail_evidence_duration,
                evidenceTypeLabel(result.provider.evidenceType),
                result.durationMillis
            ),
            style = MaterialTheme.typography.labelSmall
        )
    }
    if (entry.result == null && entry.errorMessage == null) {
        Text(providerStatusLabel(entry))
    }
}

@Composable
private fun EvidenceSignalGroupRow(
    type: EvidenceType,
    signals: List<EvidenceSignal>
) {
    val outcomes = signals.map(EvidenceSignal::outcome).distinct()
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            stringResource(
                R.string.detail_evidence_label,
                evidenceTypeLabel(type),
                if (outcomes.size == 1) {
                    outcomeLabel(outcomes.single())
                } else {
                    stringResource(R.string.detail_mixed_outcomes)
                }
            ),
            fontWeight = FontWeight.Bold
        )
        signals.map { it.source to it.verificationLevel }.distinct().forEach { (source, level) ->
            Text("$source: ${verificationLabel(level)}", style = MaterialTheme.typography.bodySmall)
        }
        if (outcomes.size > 1) {
            val checks = outcomes.joinToString { outcome ->
                "${signals.count { it.outcome == outcome }} ${outcomeLabel(outcome)}"
            }
            Text(
                stringResource(R.string.detail_signal_checks, checks),
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

private fun shareImage(context: Context, photo: GalleryPhoto) {
    try {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = photo.mimeType ?: "image/*"
            putExtra(Intent.EXTRA_STREAM, Uri.parse(photo.contentUri))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, context.getString(R.string.detail_share_chooser)))
    } catch (_: Exception) {
        Toast.makeText(context, context.getString(R.string.detail_share_failed), Toast.LENGTH_SHORT).show()
    }
}
