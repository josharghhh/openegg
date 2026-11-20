package com.openegg.app.ui.camera

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openegg.app.camera.CameraSettingDef
import com.openegg.app.camera.CameraSettings
import com.openegg.app.camera.CameraSettingsRepository
import com.openegg.app.camera.CameraSettingsRepository.FormatResult
import com.openegg.app.camera.CameraSettingsRepository.SettingState
import com.openegg.app.camera.SdCard
import com.openegg.app.ui.flight.FlightColors
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val SheetColor = Color(0xE6121212)
private val RowColor = Color(0x22FFFFFF)

/**
 * Right-hand camera/gimbal settings sheet bound to a [CameraSettingsRepository].
 * Reads all settings when first shown.
 *
 * Integration (FlightScreen, landscape):
 * ```
 * val repo = remember(session) { CameraSettingsRepository(session.commands, session.link, scope) }
 * Box(Modifier.fillMaxSize()) {
 *     ...video + HUD...
 *     if (showCamera) CameraSettingsPanel(repo, onClose = { showCamera = false }, Modifier.align(Alignment.CenterEnd))
 *     GimbalPitchControl(telemetry.gimbalPitchDeg, onRateChange = repo::holdPitchRate, Modifier.align(Alignment.CenterStart))
 * }
 * ```
 */
@Composable
fun CameraSettingsPanel(
    repository: CameraSettingsRepository,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    refreshOnOpen: Boolean = true,
) {
    val settings by repository.settings.collectAsState()
    val refreshing by repository.refreshing.collectAsState()
    val scope = rememberCoroutineScope()
    var formatMessage by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(repository) { if (refreshOnOpen) repository.readAll() }
    CameraSettingsPanel(
        settings = settings,
        refreshing = refreshing,
        onSelect = { def, v -> repository.select(def, v) },
        onRetry = { repository.retry(it) },
        onRefresh = { repository.refresh() },
        onFormatSdConfirmed = {
            formatMessage = "Formatting SD card…"
            scope.launch {
                formatMessage = when (val r = repository.formatSd(confirmed = true)) {
                    FormatResult.Success -> "SD card formatted"
                    is FormatResult.Failed -> "Format failed (code ${r.code})"
                    FormatResult.Timeout -> "Camera did not answer"
                    FormatResult.NotConfirmed -> null
                }
            }
        },
        formatMessage = formatMessage,
        onClose = onClose,
        modifier = modifier,
    )
}

/** Stateless variant: the caller supplies the state and callbacks. */
@Composable
fun CameraSettingsPanel(
    settings: Map<String, SettingState>,
    refreshing: Boolean,
    onSelect: (CameraSettingDef, Int) -> Unit,
    onRetry: (CameraSettingDef) -> Unit,
    onRefresh: () -> Unit,
    /** Called only after the user accepted the confirmation dialog. */
    onFormatSdConfirmed: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    formatMessage: String? = null,
    defs: List<CameraSettingDef> = CameraSettings.all,
    width: Dp = 360.dp,
) {
    var confirmFormat by remember { mutableStateOf(false) }
    val sdRows = setOf(CameraSettings.SD_CAPACITY.param, CameraSettings.SD_LEFT.param)
    val groups = defs.filter { it.param !in sdRows }.groupBy { it.group }

    Column(
        modifier
            .fillMaxHeight()
            .width(width)
            .background(SheetColor)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Camera", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            if (refreshing) CircularProgressIndicator(Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
            else TextButton(onClick = onRefresh) { Text("Refresh", color = Color.White) }
            TextButton(onClick = onClose, modifier = Modifier.semantics { contentDescription = "Close camera settings" }) {
                Text("✕", color = Color.White, fontSize = 16.sp)
            }
        }

        SdCardSummary(
            capacity = settings[CameraSettings.SD_CAPACITY.param],
            free = settings[CameraSettings.SD_LEFT.param],
            onFormat = { confirmFormat = true },
            message = formatMessage,
        )

        LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            groups.forEach { (group, rows) ->
                item(key = "h_${group.name}") {
                    Text(
                        group.title.uppercase(), color = FlightColors.muted, fontSize = 11.sp, fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
                    )
                }
                items(rows, key = { it.param }) { def ->
                    SettingRow(def, settings[def.param] ?: SettingState(), onSelect, onRetry)
                }
            }
        }
    }

    if (confirmFormat) {
        AlertDialog(
            onDismissRequest = { confirmFormat = false },
            title = { Text("Format SD card?") },
            text = { Text("Every photo and video on the aircraft's SD card will be erased. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = { confirmFormat = false; onFormatSdConfirmed() }) {
                    Text("Erase card", color = FlightColors.danger)
                }
            },
            dismissButton = { TextButton(onClick = { confirmFormat = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SdCardSummary(capacity: SettingState?, free: SettingState?, onFormat: () -> Unit, message: String?) {
    val cap = capacity?.value
    val left = free?.value
    val present = SdCard.isPresent(cap) || SdCard.isPresent(left)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(RowColor)
            .padding(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("SD card", color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
            val loading = capacity?.loading == true || free?.loading == true
            when {
                loading -> CircularProgressIndicator(Modifier.size(14.dp), color = Color.White, strokeWidth = 2.dp)
                cap == null && left == null -> Text(
                    if (capacity?.error != null || free?.error != null) "No reply" else "—",
                    color = if (capacity?.error != null) FlightColors.danger else FlightColors.muted, fontSize = 13.sp,
                )
                !present -> Text("No SD card", color = FlightColors.warn, fontSize = 13.sp)
                else -> Text("${SdCard.format(left)} free of ${SdCard.format(cap)}", color = Color.White, fontSize = 13.sp)
            }
        }
        if (SdCard.isPresent(cap) && SdCard.isPresent(left) && cap!! > 0) {
            val used = 1f - (left!!.toFloat() / cap).coerceIn(0f, 1f)
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { used },
                modifier = Modifier.fillMaxWidth().height(4.dp),
                color = if (used > 0.9f) FlightColors.danger else FlightColors.ok,
                trackColor = Color.White.copy(alpha = 0.2f),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(message ?: "", color = FlightColors.muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = onFormat, enabled = present) {
                Text("Format…", color = if (present) FlightColors.danger else FlightColors.muted)
            }
        }
    }
}

@Composable
private fun SettingRow(
    def: CameraSettingDef,
    state: SettingState,
    onSelect: (CameraSettingDef, Int) -> Unit,
    onRetry: (CameraSettingDef) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val canEdit = !def.readOnly && !state.loading && state.value != null
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(RowColor)
            .then(
                if (def.isOptionList && canEdit) Modifier.clickable(role = Role.DropdownList) { menuOpen = true } else Modifier,
            )
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(def.label, color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
            when {
                state.loading -> CircularProgressIndicator(Modifier.size(14.dp), color = Color.White, strokeWidth = 2.dp)
                state.error != null && state.value == null -> Text(
                    "${state.error} · Retry", color = FlightColors.danger, fontSize = 13.sp,
                    modifier = Modifier.clickable(role = Role.Button) { onRetry(def) },
                )
                else -> Box {
                    Text(
                        def.labelFor(state.value) + if (def.isOptionList && !def.readOnly) "  ▾" else "",
                        color = if (def.readOnly) FlightColors.muted else Color.White, fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        def.options.forEach { opt ->
                            DropdownMenuItem(
                                text = { Text((if (opt.value == state.value) "✓ " else "    ") + opt.label) },
                                onClick = { menuOpen = false; if (opt.value != state.value) onSelect(def, opt.value) },
                            )
                        }
                    }
                }
            }
        }
        if (state.error != null && state.value != null) {
            Text(state.error, color = FlightColors.warn, fontSize = 11.sp)
        }
        val range = def.range
        if (range != null && !def.readOnly) {
            var slider by remember(state.value) { mutableFloatStateOf((state.value ?: range.first).toFloat()) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Slider(
                    value = slider,
                    onValueChange = { slider = it },
                    onValueChangeFinished = { val v = slider.roundToInt(); if (v != state.value) onSelect(def, v) },
                    valueRange = range.first.toFloat()..range.last.toFloat(),
                    enabled = state.value != null && !state.loading,
                    colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = FlightColors.ok),
                    modifier = Modifier.weight(1f),
                )
                Text(slider.roundToInt().toString(), color = Color.White, fontSize = 13.sp, modifier = Modifier.width(32.dp).padding(start = 6.dp))
            }
        }
    }
}
