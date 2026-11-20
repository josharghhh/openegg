package com.openegg.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.openegg.app.link.probe.Verdict

@Composable
fun LinkTestScreen(vm: LinkTestViewModel = viewModel()) {
    val net by vm.network.collectAsStateWithLifecycle()
    val ui by vm.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val hasResults = ui.done > 0

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("OpenEgg · link test", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.width(16.dp))
            Text(
                if (net.isUp) "Wi-Fi bound · phone ${net.localAddress} · gateway ${net.gateway}"
                else "Not bound: join the base station Wi-Fi (Power_Ground…, password 1234567890), then come back.",
                style = MaterialTheme.typography.bodyMedium,
                color = if (net.isUp) Color.Unspecified else MaterialTheme.colorScheme.error,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = vm::runProbe, enabled = !ui.running) { Text(if (ui.rows.isEmpty()) "Probe drone link" else "Probe again") }
            OutlinedButton(onClick = { copy(context, vm.report()) }, enabled = hasResults) { Text("Copy report") }
            OutlinedButton(onClick = { share(context, vm.report()) }, enabled = hasResults) { Text("Share report") }
            if (ui.running) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
                Text("${ui.done} of ${ui.rows.size} probes done (≈20 s max)", style = MaterialTheme.typography.bodyMedium)
            } else if (ui.rows.isNotEmpty()) {
                val found = ui.rows.count { it.section?.verdict == Verdict.FOUND }
                Text("$found of ${ui.rows.size} answered", style = MaterialTheme.typography.bodyMedium)
            }
        }
        ui.savedPath?.let {
            SelectionContainer { Text("Saved: $it", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
        }
        ui.saveError?.let { Text("Could not save report: $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(ui.rows, key = { it.id }) { row -> ProbeCard(row) }
        }
    }
}

@Composable
private fun ProbeCard(row: LinkTestViewModel.Row) {
    var expanded by remember { mutableStateOf(false) }
    val s = row.section
    Card(Modifier.fillMaxWidth().clickable(enabled = s != null) { expanded = !expanded }) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                VerdictChip(s?.verdict)
                Spacer(Modifier.width(8.dp))
                Text("${row.group} · ${row.label} · ${row.target}", style = MaterialTheme.typography.titleSmall)
            }
            Text(
                s?.summary ?: "running…",
                style = MaterialTheme.typography.bodyMedium,
                maxLines = if (expanded) Int.MAX_VALUE else 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (s != null && expanded) {
                SelectionContainer {
                    Text(
                        buildString {
                            if (s.sent.isNotEmpty()) { appendLine("Sent:"); s.sent.forEach { appendLine("  $it") } }
                            s.details.forEach { appendLine(it) }
                        }.trimEnd(),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 14.sp,
                    )
                }
            } else if (s != null && (s.details.isNotEmpty() || s.sent.isNotEmpty())) {
                Text("tap for details", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun VerdictChip(v: Verdict?) {
    val (bg, label) = when (v) {
        null -> Color(0xFF616161) to "…"
        Verdict.FOUND -> Color(0xFF2E7D32) to v.tag
        Verdict.OPEN -> Color(0xFFF9A825) to v.tag
        Verdict.REFUSED -> Color(0xFF6D4C41) to v.tag
        Verdict.NO_ANSWER -> Color(0xFF424242) to v.tag
        Verdict.ERROR -> Color(0xFFC62828) to v.tag
    }
    Text(
        label,
        color = Color.White,
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier.background(bg, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

private fun copy(context: Context, text: String) {
    val cm = context.getSystemService(ClipboardManager::class.java)
    cm.setPrimaryClip(ClipData.newPlainText("OpenEgg link report", text))
    Toast.makeText(context, "Report copied (${text.length} chars)", Toast.LENGTH_SHORT).show()
}

private fun share(context: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "OpenEgg link test report")
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(send, "Share link report"))
}
