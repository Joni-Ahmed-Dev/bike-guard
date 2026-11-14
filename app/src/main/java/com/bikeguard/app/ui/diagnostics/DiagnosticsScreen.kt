package com.bikeguard.app.ui.diagnostics

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bikeguard.app.BuildConfig
import com.bikeguard.app.R
import com.bikeguard.app.bikeApp
import com.bikeguard.app.data.ReportOutcome
import com.bikeguard.app.tracker.TrackerState
import com.bikeguard.app.ui.agoText
import com.bikeguard.app.ui.fmtHourMin
import com.bikeguard.app.ui.rememberNow
import com.bikeguard.app.ui.theme.Palette
import com.bikeguard.app.ui.theme.Panel
import kotlinx.coroutines.launch

/** Shows what is happening inside the app, and lets the user send a report
 * to the diagnostics Worker so Muse can see the same state. */
@Composable
fun DiagnosticsScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val app = ctx.bikeApp
    val prefs = app.prefs
    val scope = rememberCoroutineScope()
    val running by TrackerState.running.collectAsStateWithLifecycle()
    val fix by TrackerState.lastFix.collectAsStateWithLifecycle()
    val queued by app.db.pending().count().collectAsStateWithLifecycle(0)
    val now = rememberNow()
    var sending by remember { mutableStateOf(false) }
    var sendResult by remember { mutableStateOf<String?>(null) }
    var sendOk by remember { mutableStateOf<Boolean?>(null) }

    Column(
        modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.diag_back))
            }
            Text(stringResource(R.string.diag_title), style = MaterialTheme.typography.headlineMedium)
        }
        Panel(Modifier.fillMaxWidth()) {
            DiagRow(stringResource(R.string.diag_account), prefs.email?.ifBlank { null } ?: stringResource(R.string.none))
            DiagRow(
                stringResource(R.string.diag_role),
                when {
                    prefs.isTracker -> stringResource(R.string.diag_role_tracker)
                    prefs.isRemote -> stringResource(R.string.diag_role_remote)
                    else -> stringResource(R.string.diag_role_none)
                },
            )
            DiagRow(stringResource(R.string.diag_device), prefs.deviceId.take(8))
            DiagRow(
                stringResource(R.string.diag_version),
                stringResource(R.string.version, BuildConfig.VERSION_NAME),
            )
            DiagRow(
                stringResource(R.string.diag_worker),
                if (app.diag.configured) {
                    runCatching { java.net.URI(BuildConfig.DIAG_URL).host }.getOrNull()
                        .orEmpty().ifBlank { stringResource(R.string.none) }
                } else {
                    stringResource(R.string.diag_not_configured)
                },
            )
            DiagRow(
                stringResource(R.string.diag_tracking),
                stringResource(if (running) R.string.diag_running else R.string.diag_stopped),
            )
            DiagRow(
                stringResource(R.string.diag_last_fix),
                fix?.let { agoText(ctx, it.timeMs, now) } ?: stringResource(R.string.none),
            )
            DiagRow(stringResource(R.string.diag_queued), stringResource(R.string.unit_points, queued))
            DiagRow(
                stringResource(R.string.diag_last_sync),
                if (prefs.lastSyncAttemptMs == 0L) stringResource(R.string.diag_never)
                else fmtHourMin(prefs.lastSyncAttemptMs) +
                    if (prefs.lastSyncError.isBlank()) " " + stringResource(R.string.diag_ok)
                    else " " + prefs.lastSyncError,
            )
            DiagRow(
                stringResource(R.string.diag_last_report),
                if (prefs.lastDiagReportAtMs == 0L) stringResource(R.string.diag_never)
                else fmtHourMin(prefs.lastDiagReportAtMs) + " " +
                    stringResource(if (prefs.lastDiagReportOk) R.string.diag_ok else R.string.diag_failed_short),
            )
        }
        Button(
            onClick = {
                if (sending) return@Button
                sending = true
                sendResult = null
                sendOk = null
                scope.launch {
                    // reportDetailed (not report) so a build without a
                    // diagnostics token says "not set up in this build"
                    // instead of blaming the connection.
                    val outcome = app.diag.reportDetailed("manual report")
                    sendOk = outcome == ReportOutcome.SENT
                    sendResult = ctx.getString(
                        when (outcome) {
                            ReportOutcome.SENT -> R.string.diag_sent
                            ReportOutcome.NOT_CONFIGURED -> R.string.diag_not_configured
                            ReportOutcome.REJECTED -> R.string.diag_rejected
                            ReportOutcome.FAILED -> R.string.diag_failed
                        },
                    )
                    sending = false
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(if (sending) R.string.diag_sending else R.string.diag_send)) }
        sendResult?.let {
            Text(
                it,
                color = if (sendOk == true) Palette.Signal else Palette.Danger,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.diag_hint), color = Palette.Muted, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun DiagRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Palette.Muted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.titleMedium)
    }
}
