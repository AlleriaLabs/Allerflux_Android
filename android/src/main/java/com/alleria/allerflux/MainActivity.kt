package com.alleria.allerflux

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.io.OutputStream

private val Ink = Color(0xFF080D16)
private val Panel = Color(0xFF111B29)
private val PanelElevated = Color(0xFF172436)
private val Accent = Color(0xFF70E3CF)
private val Muted = Color(0xFF91A3B8)
private val Border = Color(0xFF26364A)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AllerfluxApp(this) }
    }
}

private data class Overview(
    val users: String = "—",
    val sessions: String = "—",
    val playing: String = "—",
    val paused: String = "—",
    val connected: Boolean = false,
    val message: String = "Not configured"
)
private data class SessionRow(
    val user: String, val title: String, val type: String, val state: String, val position: Long,
    val device: String, val client: String, val version: String, val lastActivity: String, val idleSeconds: Long?
)
private fun JSONObject.safeText(name: String, fallback: String): String {
    val value = optString(name, "").trim()
    return if (value.isBlank() || value == "null") fallback else value
}
private fun formatDuration(seconds: Long): String {
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
}
private data class HistoryRow(val user: String, val title: String, val type: String, val state: String, val time: String)
private data class LibraryStats(val movies: Int = 0, val series: Int = 0, val episodes: Int = 0, val songs: Int = 0, val albums: Int = 0, val artists: Int = 0)
private data class ReportStats(val snapshots: Int = 0, val users: Int = 0, val titles: Int = 0, val playing: Int = 0, val paused: Int = 0)
private data class CompareMetric(val current: Int = 0, val previous: Int = 0, val delta: Int = 0, val percent: Double? = null)
private data class CompareSummary(val snapshots: CompareMetric = CompareMetric(), val users: CompareMetric = CompareMetric(), val starts: CompareMetric = CompareMetric(), val completions: CompareMetric = CompareMetric())
private data class EventSummary(val starts: Int = 0, val stops: Int = 0, val completed: Int = 0, val users: Int = 0, val titles: Int = 0)
private data class AlertSummary(val active: Int = 0, val total: Int = 0, val idleThreshold: Int = 1800, val cooldown: Int = 60, val outboundEnabled: Boolean = false)
private data class AlertRow(val kind: String, val title: String, val message: String, val status: String, val time: String)
private data class Diagnostics(val databaseConnected: Boolean = false, val pollingEnabled: Boolean = false, val pollInterval: Int = 20, val lastSuccess: String = "Not yet", val lastError: String = "None", val snapshotRetention: Int = 365, val eventRetention: Int = 730)
private data class DailyStat(val date: String, val snapshots: Int, val users: Int, val titles: Int)
private data class TopTitle(val name: String, val type: String, val samples: Int, val users: Int)
private data class TopUser(val name: String, val samples: Int, val titles: Int)
private data class ReportDetails(
    val daily: List<DailyStat> = emptyList(),
    val topTitles: List<TopTitle> = emptyList(),
    val topUsers: List<TopUser> = emptyList()
)

private suspend fun apiGet(base: String, key: String, path: String): String = withContext(Dispatchers.IO) {
    val c = URL(base.trim().trimEnd('/') + path).openConnection() as HttpURLConnection
    try {
        c.requestMethod = "GET"
        c.connectTimeout = 8000
        c.readTimeout = 10000
        c.setRequestProperty("Accept", "application/json")
        c.setRequestProperty("X-Allerflux-Key", key)
        val code = c.responseCode
        val stream = if (code in 200..299) c.inputStream else c.errorStream
        val body = stream.bufferedReader().use { it.readText() }
        if (code !in 200..299) error("HTTP " + code + ": " + runCatching { JSONObject(body).optString("detail") }.getOrDefault(body))
        body
    } finally { c.disconnect() }
}

private suspend fun apiGetBytes(base: String, key: String, path: String): ByteArray = withContext(Dispatchers.IO) {
    val c = URL(base.trim().trimEnd('/') + path).openConnection() as HttpURLConnection
    try {
        c.requestMethod = "GET"
        c.connectTimeout = 10000
        c.readTimeout = 30000
        c.setRequestProperty("Accept", "application/octet-stream")
        c.setRequestProperty("X-Allerflux-Key", key)
        val code = c.responseCode
        if (code !in 200..299) {
            val body = c.errorStream?.bufferedReader()?.use { it.readText() } ?: "Request failed"
            error("HTTP $code: $body")
        }
        c.inputStream.use { it.readBytes() }
    } finally { c.disconnect() }
}

private suspend fun loadOverview(base: String, key: String): Overview {
    val j = JSONObject(apiGet(base, key, "/api/v1/overview"))
    return Overview(j.optInt("registered_users").toString(), j.optInt("active_sessions").toString(),
        j.optInt("currently_playing").toString(), j.optInt("paused").toString(),
        j.optBoolean("server_connected"), "Connected to Jellyfin")
}
private suspend fun loadSessions(base: String, key: String): List<SessionRow> {
    val a = JSONArray(apiGet(base, key, "/api/v1/sessions"))
    return (0 until a.length()).map {
        val j = a.getJSONObject(it)
        SessionRow(
            j.safeText("username", "Unknown user"),
            j.safeText("item_name", "Not playing"),
            j.safeText("media_type", "No media information"),
            j.safeText("play_state", "unknown"),
            j.optLong("position_ticks"),
            j.safeText("device_name", "Device unavailable"),
            j.safeText("client_name", "Client unavailable"),
            j.safeText("client_version", "Version unavailable"),
            j.safeText("last_activity", "Last activity unavailable"),
            if (j.has("idle_seconds") && !j.isNull("idle_seconds")) j.optLong("idle_seconds") else null
        )
    }
}

private suspend fun loadLibrary(base: String, key: String): LibraryStats {
    val j = JSONObject(apiGet(base, key, "/api/v1/library"))
    return LibraryStats(j.optInt("movie_count"), j.optInt("series_count"), j.optInt("episode_count"),
        j.optInt("song_count"), j.optInt("album_count"), j.optInt("artist_count"))
}

private suspend fun loadReportStats(base: String, key: String, days: Int): ReportStats {
    val j = JSONObject(apiGet(base, key, "/api/v1/reports/summary?days=$days"))
    return ReportStats(j.optInt("snapshot_count"), j.optInt("observed_users"), j.optInt("observed_titles"),
        j.optInt("playing_samples"), j.optInt("paused_samples"))
}

private suspend fun loadReportDetails(base: String, key: String, days: Int, groupBy: String): ReportDetails {
    val summary = JSONObject(apiGet(base, key, "/api/v1/reports/summary?days=$days&group_by=$groupBy"))
    val dailyJson = summary.optJSONArray("daily") ?: JSONArray()
    val daily = (0 until dailyJson.length()).map {
        val row = dailyJson.getJSONObject(it)
        DailyStat(row.optString("date"), row.optInt("snapshots"), row.optInt("unique_users"), row.optInt("unique_titles"))
    }
    val titlesJson = JSONObject(apiGet(base, key, "/api/v1/reports/top-titles?days=$days&limit=8"))
        .optJSONArray("items") ?: JSONArray()
    val titles = (0 until titlesJson.length()).map {
        val row = titlesJson.getJSONObject(it)
        TopTitle(row.optString("item_name", "Unknown title"), row.optString("media_type", "Media"),
            row.optInt("snapshot_count"), row.optInt("observed_users"))
    }
    val usersJson = JSONObject(apiGet(base, key, "/api/v1/reports/top-users?days=$days&limit=8"))
        .optJSONArray("users") ?: JSONArray()
    val users = (0 until usersJson.length()).map {
        val row = usersJson.getJSONObject(it)
        TopUser(row.optString("username", "Unknown user"), row.optInt("snapshot_count"), row.optInt("observed_titles"))
    }
    return ReportDetails(daily, titles, users)
}



private suspend fun loadReportCompare(base: String, key: String, days: Int): CompareSummary {
    val root = JSONObject(apiGet(base, key, "/api/v1/reports/compare?days=$days"))
    val metrics = root.optJSONObject("metrics") ?: JSONObject()
    fun read(name: String): CompareMetric {
        val item = metrics.optJSONObject(name) ?: JSONObject()
        val percent = if (item.isNull("change_percent")) null else item.optDouble("change_percent")
        return CompareMetric(item.optInt("current"), item.optInt("previous"),
            item.optInt("delta"), percent)
    }
    return CompareSummary(read("snapshot_count"), read("observed_users"),
        read("playback_starts"), read("completed_stops"))
}

private suspend fun loadEventSummary(base: String, key: String, days: Int): EventSummary {
    val j = JSONObject(apiGet(base, key, "/api/v1/events/summary?days=$days"))
    return EventSummary(j.optInt("playback_starts"), j.optInt("playback_stops"),
        j.optInt("completed_stops"), j.optInt("distinct_users"), j.optInt("distinct_titles"))
}



private suspend fun loadDiagnostics(base: String, key: String): Diagnostics {
    val j = JSONObject(apiGet(base, key, "/api/v1/diagnostics"))
    return Diagnostics(j.optBoolean("database_connected"), j.optBoolean("jellyfin_polling_enabled"),
        j.optInt("poll_interval_seconds", 20), j.optString("last_poll_success_at", "Not yet"),
        j.optString("last_poll_error_type", "None"), j.optInt("snapshot_retention_days", 365),
        j.optInt("event_retention_days", 730))
}

private suspend fun loadAlertSummary(base: String, key: String): AlertSummary {
    val j = JSONObject(apiGet(base, key, "/api/v1/alerts/summary"))
    return AlertSummary(j.optInt("active_alerts"), j.optInt("total_events"),
        j.optInt("idle_threshold_seconds", 1800), j.optInt("cooldown_minutes", 60),
        j.optBoolean("outbound_notifications_enabled"))
}

private suspend fun loadAlertHistory(base: String, key: String): List<AlertRow> {
    val a = JSONArray(apiGet(base, key, "/api/v1/alerts/history?limit=100"))
    return (0 until a.length()).map {
        val j = a.getJSONObject(it)
        AlertRow(j.optString("kind"), j.optString("title"), j.optString("message"),
            j.optString("status"), j.optString("created_at"))
    }
}

private suspend fun loadHistory(base: String, key: String): List<HistoryRow> {
    val a = JSONArray(apiGet(base, key, "/api/v1/history?limit=200"))
    return (0 until a.length()).map {
        val j = a.getJSONObject(it)
        HistoryRow(j.optString("user", "Unknown user"), j.optString("item_name", "Unknown title"),
            j.optString("media_type", "Media"), j.optString("state", "unknown"), j.optString("captured_at"))
    }
}



private fun deltaLabel(metric: CompareMetric): String {
    val prefix = if (metric.delta > 0) "+" else ""
    val percent = metric.percent?.let { " · ${if (it > 0) "+" else ""}${it}%" } ?: ""
    return "$prefix${metric.delta}$percent"
}

private fun csvCell(value: String): String = "\"" + value.replace("\"", "\"\"") + "\""

private fun buildReportCsv(days: Int, granularity: String, stats: ReportStats, details: ReportDetails, events: EventSummary): String = buildString {
    appendLine("Allerflux viewing report")
    appendLine("Period days,$days")
    appendLine("Grouping,$granularity")
    appendLine("Snapshot observations,${stats.snapshots}")
    appendLine("Observed users,${stats.users}")
    appendLine("Observed titles,${stats.titles}")
    appendLine("Playing samples,${stats.playing}")
    appendLine("Paused samples,${stats.paused}")
    appendLine("Playback start events,${events.starts}")
    appendLine("Playback stop events,${events.stops}")
    appendLine("Explicit completed stops,${events.completed}")
    appendLine("Distinct event viewers,${events.users}")
    appendLine()
    appendLine("Daily observations")
    appendLine("Date,Snapshots,Distinct viewers,Distinct titles")
    details.daily.forEach { appendLine(listOf(it.date, it.snapshots, it.users, it.titles).joinToString(",")) }
    appendLine()
    appendLine("Top observed titles")
    appendLine("Title,Media type,Observed samples,Distinct viewers")
    details.topTitles.forEach { appendLine(listOf(csvCell(it.name), csvCell(it.type), it.samples, it.users).joinToString(",")) }
    appendLine()
    appendLine("Top observed viewers")
    appendLine("Username,Observed samples,Distinct titles")
    details.topUsers.forEach { appendLine(listOf(csvCell(it.name), it.samples, it.titles).joinToString(",")) }
    appendLine()
    appendLine("Accuracy: snapshot counts are periodic observations, not exact plays or watch duration. Event counts come from received Jellyfin webhook notifications; completion is counted only when explicitly supplied.")
}

private fun buildReportPdf(output: OutputStream, days: Int, granularity: String, stats: ReportStats,
    details: ReportDetails, events: EventSummary) {
    val lines = mutableListOf(
        "Reporting period: $days days",
        "Snapshot observations: ${stats.snapshots}",
        "Observed users: ${stats.users}",
        "Observed titles: ${stats.titles}",
        "Playing samples: ${stats.playing}",
        "Paused samples: ${stats.paused}",
        "Playback starts: ${events.starts}",
        "Playback stops: ${events.stops}",
        "Explicit completions: ${events.completed}",
        "",
        "DAILY OBSERVATIONS"
    )
    details.daily.takeLast(14).forEach { lines.add("${it.date}  ·  ${it.users} viewers  ·  ${it.titles} titles  ·  ${it.snapshots} samples") }
    lines.add("")
    lines.add("TOP OBSERVED TITLES")
    details.topTitles.take(8).forEachIndexed { index, item -> lines.add("${index + 1}. ${item.name}  ·  ${item.samples} samples  ·  ${item.users} viewers") }
    lines.add("")
    lines.add("TOP OBSERVED VIEWERS")
    details.topUsers.take(8).forEachIndexed { index, item -> lines.add("${index + 1}. ${item.name}  ·  ${item.samples} samples  ·  ${item.titles} titles") }
    lines.add("")
    lines.add("Data note: snapshots are periodic observations, not exact plays or watch duration.")
    lines.add("Event counts are received Jellyfin webhook notifications. Stops are not automatically completions.")
    val document = PdfDocument()
    val chunks = lines.chunked(29)
    chunks.forEachIndexed { pageIndex, chunk ->
        val pageInfo = PdfDocument.PageInfo.Builder(595, 842, pageIndex + 1).create()
        val page = document.startPage(pageInfo)
        val canvas = page.canvas
        val header = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.rgb(10, 20, 32)
            style = Paint.Style.FILL
        }
        canvas.drawRect(0f, 0f, 595f, 112f, header)
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            textSize = 24f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
        }
        val metaPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.rgb(112, 225, 207)
            textSize = 11f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
        }
        val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.rgb(30, 42, 56)
            textSize = 12f
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        }
        canvas.drawText("ALLERFLUX", 40f, 48f, titlePaint)
        canvas.drawText("MEDIA SERVER INTELLIGENCE  ·  $days-DAY  ·  ${granularity.uppercase()} ROLLUP", 40f, 76f, metaPaint)
        var y = 145f
        chunk.forEach { line ->
            if (line.isNotBlank() && (line.startsWith("DAILY") || line.startsWith("TOP "))) {
                bodyPaint.typeface = Typeface.create("sans-serif", Typeface.BOLD)
                bodyPaint.textSize = 13f
            } else {
                bodyPaint.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
                bodyPaint.textSize = 11.5f
            }
            canvas.drawText(line.take(92), 40f, y, bodyPaint)
            y += 21f
        }
        document.finishPage(page)
    }
    document.writeTo(output)
    document.close()
}

@Composable
fun AllerfluxApp(context: Context) {
    val securePrefs = remember { SecurePreferences(context) }
    val savedConnection = remember { securePrefs.load() }
    var serverUrl by remember { mutableStateOf(savedConnection.first) }
    var apiKey by remember { mutableStateOf(savedConnection.second) }
    var overview by remember { mutableStateOf(Overview()) }
    var sessions by remember { mutableStateOf<List<SessionRow>>(emptyList()) }
    var history by remember { mutableStateOf<List<HistoryRow>>(emptyList()) }
    var library by remember { mutableStateOf(LibraryStats()) }
    var libraryError by remember { mutableStateOf("") }
    var reportStats by remember { mutableStateOf(ReportStats()) }
    var reportDetails by remember { mutableStateOf(ReportDetails()) }
    var eventSummary by remember { mutableStateOf(EventSummary()) }
    var eventError by remember { mutableStateOf("") }
    var alertSummary by remember { mutableStateOf(AlertSummary()) }
    var alertHistory by remember { mutableStateOf<List<AlertRow>>(emptyList()) }
    var alertError by remember { mutableStateOf("") }
    var diagnostics by remember { mutableStateOf(Diagnostics()) }
    var diagnosticsError by remember { mutableStateOf("") }
    var backupMessage by remember { mutableStateOf("") }
    var pendingBackup by remember { mutableStateOf<ByteArray?>(null) }
    var reportDays by remember { mutableIntStateOf(30) }
    var reportGranularity by remember { mutableStateOf("day") }
    var compareSummary by remember { mutableStateOf(CompareSummary()) }
    var compareError by remember { mutableStateOf("") }
    var reportError by remember { mutableStateOf("") }
    var exportMessage by remember { mutableStateOf("") }
    var pendingCsv by remember { mutableStateOf("") }
    var pendingPdfDays by remember { mutableIntStateOf(30) }
    var pendingPdfGranularity by remember { mutableStateOf("day") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var activityError by remember { mutableStateOf("") }
    var historyError by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf("Overview") }
    var urlDraft by remember { mutableStateOf(serverUrl) }
    var keyDraft by remember { mutableStateOf(apiKey) }
    val scope = rememberCoroutineScope()
    val csvLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) {
            val content = pendingCsv
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openOutputStream(uri)?.use { it.write(content.toByteArray(Charsets.UTF_8)) }
                            ?: error("Unable to open destination")
                    }
                }
                exportMessage = if (result.isSuccess) "CSV report saved." else "CSV export failed: ${result.exceptionOrNull()?.message}"
            }
        }
    }
    val backupLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.sqlite3")) { uri ->
        if (uri != null) {
            val bytes = pendingBackup
            if (bytes != null) scope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("Unable to open destination") }
                }
                backupMessage = if (result.isSuccess) "Database backup saved." else "Backup save failed: ${result.exceptionOrNull()?.message}"
            }
        }
    }
    val pdfLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null) {
            val days = pendingPdfDays
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openOutputStream(uri)?.use { buildReportPdf(it, days, pendingPdfGranularity, reportStats, reportDetails, eventSummary) }
                            ?: error("Unable to open destination")
                    }
                }
                exportMessage = if (result.isSuccess) "PDF report saved." else "PDF export failed: ${result.exceptionOrNull()?.message}"
            }
        }
    }

    fun refresh() {
        if (serverUrl.isBlank() || apiKey.isBlank()) {
            error = "Enter the companion URL and API key in Settings."
            selected = "Settings"
            return
        }
        scope.launch {
            loading = true
            error = ""
            try { overview = loadOverview(serverUrl, apiKey) }
            catch (e: Exception) { overview = Overview(message = "Connection failed"); error = e.message ?: "Unable to load overview" }
            try { sessions = loadSessions(serverUrl, apiKey); activityError = "" }
            catch (e: Exception) { activityError = e.message ?: "Unable to load sessions" }
            try { history = loadHistory(serverUrl, apiKey); historyError = "" }
            catch (e: Exception) { historyError = e.message ?: "Unable to load history" }
            try { library = loadLibrary(serverUrl, apiKey); libraryError = "" }
            catch (e: Exception) { libraryError = e.message ?: "Unable to load library inventory" }
            try { reportStats = loadReportStats(serverUrl, apiKey, reportDays); reportDetails = loadReportDetails(serverUrl, apiKey, reportDays, reportGranularity); reportError = "" }
            catch (e: Exception) { reportError = e.message ?: "Unable to load report summary" }
            try { compareSummary = loadReportCompare(serverUrl, apiKey, reportDays); compareError = "" }
            catch (e: Exception) { compareError = e.message ?: "Period comparison is unavailable" }
            try { eventSummary = loadEventSummary(serverUrl, apiKey, reportDays); eventError = "" }
            catch (e: Exception) { eventError = e.message ?: "Playback events are unavailable" }
            try { alertSummary = loadAlertSummary(serverUrl, apiKey); alertHistory = loadAlertHistory(serverUrl, apiKey); alertError = "" }
            catch (e: Exception) { alertError = e.message ?: "Alert history is unavailable" }
            try { diagnostics = loadDiagnostics(serverUrl, apiKey); diagnosticsError = "" }
            catch (e: Exception) { diagnosticsError = e.message ?: "Diagnostics are unavailable" }
            loading = false
        }
    }

    LaunchedEffect(serverUrl, apiKey, reportDays, reportGranularity) {
        if (serverUrl.isNotBlank() && apiKey.isNotBlank()) {
            refresh()
            while (true) {
                delay(60_000)
                refresh()
            }
        }
    }

    MaterialTheme(colorScheme = darkColorScheme(background = Ink, surface = Panel, primary = Accent, onBackground = Color.White, onSurface = Color.White)) {
        Scaffold(containerColor = Ink, bottomBar = {
            NavigationBar(containerColor = Panel) {
                listOf("Overview", "Activity", "Library", "Reports", "More").forEach { tab ->
                    NavigationBarItem(selected = selected == tab, onClick = { selected = tab },
                        icon = { Icon(when (tab) {
                            "Overview" -> Icons.Rounded.Dashboard
                            "Activity" -> Icons.Rounded.PlayCircle
                            "Library" -> Icons.Rounded.VideoLibrary
                            "Reports" -> Icons.Rounded.QueryStats
                            else -> Icons.Rounded.MoreHoriz
                        }, contentDescription = tab) },
                        label = { Text(tab) },
                        colors = NavigationBarItemDefaults.colors(selectedIconColor = Accent, selectedTextColor = Accent,
                            indicatorColor = Color(0xFF203B42), unselectedTextColor = Muted))
                }
            }
        }) { padding ->
            LazyColumn(Modifier.fillMaxSize().background(Ink).padding(padding),
                contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                item {
                    Surface(color = PanelElevated, shape = RoundedCornerShape(24.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Border),
                        modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(horizontal = 16.dp, vertical = 15.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(13.dp)) {
                            Surface(color = Color(0xFF203B42), shape = RoundedCornerShape(16.dp),
                                modifier = Modifier.size(50.dp)) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(Icons.Rounded.GraphicEq, contentDescription = null, tint = Accent,
                                        modifier = Modifier.size(27.dp))
                                }
                            }
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text("ALLERIA LABS", color = Accent, fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold, letterSpacing = 1.7.sp)
                                Text("Allerflux", color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.Bold)
                                Text("Media server intelligence", color = Muted, fontSize = 11.sp)
                            }
                            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Surface(color = if (overview.connected) Color(0xFF173B36) else Color(0xFF3B2B2B),
                                    shape = RoundedCornerShape(30.dp)) {
                                    Text(if (overview.connected) "ONLINE" else "SETUP",
                                        color = if (overview.connected) Accent else Color(0xFFFFB5A8),
                                        fontSize = 9.sp, fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp))
                                }
                                IconButton(onClick = { selected = "Settings" }) {
                                    Icon(Icons.Rounded.Settings, "Settings", tint = Color.White)
                                }
                            }
                        }
                    }
                }
                if (selected == "Overview") {
                    item {
                        Surface(color = Panel, shape = RoundedCornerShape(22.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Border),
                            modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("SERVER SNAPSHOT", color = Accent, fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                                Text(if (overview.connected) "Your media server, at a glance" else "Bring your media server into focus",
                                    color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                                Text(if (overview.connected) "Live Jellyfin status with observed activity and library intelligence."
                                    else "Connect your Allerflux companion to see live sessions, library health and viewing trends.",
                                    color = Muted, fontSize = 13.sp, lineHeight = 19.sp)
                                Row(verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Icon(if (overview.connected) Icons.Rounded.CheckCircle else Icons.Rounded.Info,
                                        contentDescription = null,
                                        tint = if (overview.connected) Accent else Color(0xFFFFB86B),
                                        modifier = Modifier.size(16.dp))
                                    Text(if (overview.connected) "Jellyfin connection established" else "Connection setup required",
                                        color = if (overview.connected) Accent else Color(0xFFFFB86B),
                                        fontSize = 11.sp, fontWeight = FontWeight.Medium)
                                }
                            }
                        }
                    }
                }
                item { SectionHeading(when (selected) {
                    "Overview" -> "Server overview"
                    "Activity" -> "Live activity"
                    "Library" -> "Media library"
                    "Reports" -> "Viewing analytics"
                    "More" -> "Workspace & tools"
                    "Alerts" -> "Server alerts & recovery history"
                    "Top Viewers" -> "Most active viewers"
                    "Top Videos" -> "Most watched titles"
                    "Settings" -> "Connection & preferences"
                    else -> "About Allerflux"
                }) }

                if (selected == "Overview") {
                    item { Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        MetricCard("Registered users", overview.users, "Jellyfin accounts", Modifier.weight(1f))
                        MetricCard("Active sessions", overview.sessions, "Live sessions", Modifier.weight(1f))
                    } }
                    item { Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        MetricCard("Playing now", overview.playing, "Currently playing", Modifier.weight(1f))
                        MetricCard("Paused", overview.paused, "Paused sessions", Modifier.weight(1f))
                    } }
                    item { InfoCard("COMPANION CONNECTION", overview.message,
                        if (overview.connected) serverUrl else "Configure your companion URL and API key to load live server data.", error) }
                    item { InfoCard("DATA COLLECTION", "Snapshot-based analytics",
                        "The companion records periodic session observations. Snapshots are not exact playback events and do not establish watch duration or completion rates.", "") }
                }

                if (selected == "Activity") {
                    item { Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        MetricCard("Live sessions", sessions.size.toString(), "Current Jellyfin sessions", Modifier.weight(1f))
                        MetricCard("Playing", sessions.count { it.state == "playing" }.toString(), "Active playback", Modifier.weight(1f))
                    } }
                    if (activityError.isNotBlank()) item { InfoCard("ACTIVITY UNAVAILABLE", "Could not load live sessions", activityError, "") }
                    else if (sessions.isEmpty()) item { InfoCard("NO ACTIVE SESSIONS", "Nothing is streaming right now",
                        "Current Jellyfin sessions will appear here when users start playback.", "") }
                    else itemsIndexed(sessions) { _, session ->
                        SessionCard(session)
                    }
                }

                if (selected == "Library") {
                    if (libraryError.isNotBlank()) item { InfoCard("LIBRARY UNAVAILABLE", "Could not load Jellyfin inventory", libraryError, "") }
                    item { SectionHeading("Video library") }
                    item { Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        MetricCard("Movies", library.movies.toString(), "Movie library", Modifier.weight(1f))
                        MetricCard("TV series", library.series.toString(), "Series library", Modifier.weight(1f))
                    } }
                    item { Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        MetricCard("Episodes", library.episodes.toString(), "TV episodes", Modifier.weight(1f))
                        MetricCard("Songs", library.songs.toString(), "Music tracks", Modifier.weight(1f))
                    } }
                    item { SectionHeading("Music library") }
                    item { Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        MetricCard("Albums", library.albums.toString(), "Music albums", Modifier.weight(1f))
                        MetricCard("Artists", library.artists.toString(), "Music artists", Modifier.weight(1f))
                    } }
                    item { InfoCard("JELLYFIN LIBRARY", "Live inventory counts",
                        "Counts are fetched directly from Jellyfin. Refresh data to update the inventory.", "") }
                }

                if (selected == "Reports") {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                            Text("REPORTING PERIOD", color = Muted, fontSize = 10.sp,
                                fontWeight = FontWeight.Bold, letterSpacing = 1.3.sp)
                            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                                listOf(7, 30, 90, 365).forEach { days ->
                                    FilterChip(selected = reportDays == days, onClick = { reportDays = days },
                                        label = { Text(when (days) { 7 -> "7D"; 30 -> "30D"; 90 -> "90D"; else -> "1Y" }) })
                                }
                            }
                            Text("GROUP BY", color = Muted, fontSize = 10.sp,
                                fontWeight = FontWeight.Bold, letterSpacing = 1.3.sp)
                            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                                listOf("day", "week", "month").forEach { group ->
                                    FilterChip(selected = reportGranularity == group,
                                        onClick = { reportGranularity = group },
                                        label = { Text(group.replaceFirstChar { it.uppercase() }) })
                                }
                            }
                        }
                    }
                    if (reportError.isNotBlank()) item { InfoCard("REPORT SUMMARY UNAVAILABLE", "Could not load $reportDays-day summary", reportError, "") }
                    item { Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        MetricCard("Snapshots", reportStats.snapshots.toString(), "Last $reportDays days", Modifier.weight(1f))
                        MetricCard("Observed users", reportStats.users.toString(), "Distinct account IDs", Modifier.weight(1f))
                    } }
                    item { Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        MetricCard("Observed titles", reportStats.titles.toString(), "Distinct item IDs", Modifier.weight(1f))
                        MetricCard("Playing samples", reportStats.playing.toString(), "Snapshot count", Modifier.weight(1f))
                    } }
                    item { MetricCard("Paused samples", reportStats.paused.toString(), "Snapshot count", Modifier.fillMaxWidth()) }
                    item { SectionHeading("Period comparison") }
                    if (compareError.isNotBlank()) {
                        item { InfoCard("COMPARISON UNAVAILABLE", "Previous-period comparison unavailable", compareError, "") }
                    } else {
                        item { Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            MetricCard("Snapshot change", deltaLabel(compareSummary.snapshots),
                                "${compareSummary.snapshots.current} now · ${compareSummary.snapshots.previous} prior", Modifier.weight(1f))
                            MetricCard("Viewer change", deltaLabel(compareSummary.users),
                                "${compareSummary.users.current} now · ${compareSummary.users.previous} prior", Modifier.weight(1f))
                        } }
                        item { Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            MetricCard("Playback starts", deltaLabel(compareSummary.starts),
                                "${compareSummary.starts.current} now · ${compareSummary.starts.previous} prior", Modifier.weight(1f))
                            MetricCard("Completions", deltaLabel(compareSummary.completions),
                                "${compareSummary.completions.current} now · ${compareSummary.completions.previous} prior", Modifier.weight(1f))
                        } }
                        item { Text("Comparison uses the immediately preceding period of equal length. Snapshot and webhook event totals remain separate.",
                            color = Muted, fontSize = 11.sp, lineHeight = 16.sp) }
                    }
                    item { SectionHeading("Playback events") }
                    if (eventError.isNotBlank()) {
                        item { InfoCard("EVENT INGESTION UNAVAILABLE", "Playback event data not connected",
                            "Configure the Jellyfin Webhook plugin and the separate webhook secret on the companion.", eventError) }
                    } else {
                        item { Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            MetricCard("Playback starts", eventSummary.starts.toString(), "Received webhook events", Modifier.weight(1f))
                            MetricCard("Playback stops", eventSummary.stops.toString(), "Received webhook events", Modifier.weight(1f))
                        } }
                        item { Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            MetricCard("Completed", eventSummary.completed.toString(), "Explicit completion flags", Modifier.weight(1f))
                            MetricCard("Event viewers", eventSummary.users.toString(), "Distinct user IDs", Modifier.weight(1f))
                        } }
                        item { Text("Event-based counts are separate from periodic snapshot estimates. A stop is not automatically a completion, and watch duration is not inferred.",
                            color = Muted, fontSize = 11.sp, lineHeight = 16.sp) }
                    }
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("EXPORT REPORT", color = Muted, fontSize = 10.sp,
                                fontWeight = FontWeight.Bold, letterSpacing = 1.3.sp)
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                OutlinedButton(onClick = {
                                    pendingCsv = buildReportCsv(reportDays, reportGranularity, reportStats, reportDetails, eventSummary)
                                    exportMessage = ""
                                    csvLauncher.launch("allerflux-report-${reportDays}d.csv")
                                }, modifier = Modifier.weight(1f)) {
                                    Icon(Icons.Rounded.TableView, contentDescription = null)
                                    Spacer(Modifier.width(6.dp))
                                    Text("Export CSV")
                                }
                                OutlinedButton(onClick = {
                                    pendingPdfDays = reportDays
                                    pendingPdfGranularity = reportGranularity
                                    exportMessage = ""
                                    pdfLauncher.launch("allerflux-report-${reportDays}d.pdf")
                                }, modifier = Modifier.weight(1f)) {
                                    Icon(Icons.Rounded.PictureAsPdf, contentDescription = null)
                                    Spacer(Modifier.width(6.dp))
                                    Text("Export PDF")
                                }
                            }
                            if (exportMessage.isNotBlank()) Text(exportMessage, color = Accent, fontSize = 12.sp)
                        }
                    }
                    item { SectionHeading("${reportGranularity.replaceFirstChar { it.uppercase() }} observed activity") }
                    item {
                        Surface(color = Panel, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("${reportGranularity.replaceFirstChar { it.uppercase() }} viewers and distinct titles · last $reportDays days", color = Muted, fontSize = 12.sp)
                                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                    Text("● Viewers", color = Accent, fontSize = 11.sp)
                                    Text("● Titles", color = Color(0xFFFFB86B), fontSize = 11.sp)
                                }
                                if (reportDetails.daily.isEmpty()) {
                                    Text("No observations recorded in this period.", color = Muted, fontSize = 13.sp)
                                } else {
                                    Canvas(Modifier.fillMaxWidth().height(120.dp)) {
                                        val maxValue = (reportDetails.daily.maxOfOrNull { maxOf(it.users, it.titles) } ?: 0).coerceAtLeast(1)
                                        val slot = size.width / reportDetails.daily.size
                                        val barWidth = (slot * 0.30f).coerceAtLeast(1.5f)
                                        reportDetails.daily.forEachIndexed { index, day ->
                                            val center = index * slot + slot / 2f
                                            val usersHeight = size.height * day.users.toFloat() / maxValue
                                            val titlesHeight = size.height * day.titles.toFloat() / maxValue
                                            drawRect(Accent, Offset(center - barWidth - 1f, size.height - usersHeight),
                                                Size(barWidth, usersHeight))
                                            drawRect(Color(0xFFFFB86B), Offset(center + 1f, size.height - titlesHeight),
                                                Size(barWidth, titlesHeight))
                                        }
                                    }
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text(reportDetails.daily.first().date, color = Muted, fontSize = 10.sp)
                                        Text(reportDetails.daily.last().date, color = Muted, fontSize = 10.sp)
                                    }
                                }
                            }
                        }
                    }
                    item { Row(verticalAlignment = Alignment.CenterVertically) {
                        SectionHeading("Most watched videos")
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { selected = "Top Videos" }) { Text("See all", color = Accent) }
                    } }
                    item { Text("Ranked by observed playback samples, with distinct viewers shown for each title.", color = Muted, fontSize = 12.sp) }
                    if (reportDetails.topTitles.isEmpty()) item { InfoCard("NO TITLE DATA", "No ranked titles yet", "Playback observations will populate this list.", "") }
                    items(reportDetails.topTitles) { title ->
                        InfoCard(title.type, title.name, "${title.users} distinct viewers", "${title.samples} observed playback samples")
                    }
                    item { Row(verticalAlignment = Alignment.CenterVertically) {
                        SectionHeading("Top viewers")
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { selected = "Top Viewers" }) { Text("See all", color = Accent) }
                    } }
                    if (reportDetails.topUsers.isEmpty()) item { InfoCard("NO USER DATA", "No ranked users yet", "Playback observations will populate this list.", "") }
                    items(reportDetails.topUsers) { user ->
                        InfoCard("JELLYFIN USER", user.name, "${user.samples} observed samples", "${user.titles} observed titles")
                    }
                    item { InfoCard("REPORTING ACCURACY", "Periodic observations only",
                        "These are 30-day snapshot estimates, not unique plays, completed titles, or total watch time. One long playback can contribute many observations.", "") }
                    item { SectionHeading("Recent observations") }
                    items(minOf(history.size, 30)) { i ->
                        val h = history[i]
                        InfoCard(h.title, h.user, h.type + "  •  " + h.state.uppercase(), h.time.replace("T", " ").take(19))
                    }
                    if (history.isEmpty() && historyError.isBlank()) item { InfoCard("NO HISTORY YET", "Waiting for snapshots",
                        "History will appear after the companion has collected session observations.", "") }
                }

                if (selected == "Top Viewers") {
                    item { InfoCard("VIEWER LEADERBOARD", "Most active Jellyfin users", "Ranked by observed playback samples over the last $reportDays days.", "") }
                    if (reportDetails.topUsers.isEmpty()) item { InfoCard("NO DATA", "No ranked users yet", "User activity will appear as snapshots are collected.", "") }
                    val maxViewerSamples = reportDetails.topUsers.maxOfOrNull { it.samples } ?: 1
                    itemsIndexed(reportDetails.topUsers) { index, user ->
                        LeaderboardCard(index + 1, user.name, user.titles.toString() + " distinct titles",
                            user.samples, maxViewerSamples, "samples")
                    }
                    item { InfoCard("ACCURACY", "Snapshot-based estimates", "These are observed sessions, not exact play counts or watch time.", "") }
                    item { OutlinedButton(onClick = { selected = "Reports" }) { Text("Back to Reports") } }
                }
                if (selected == "Top Videos") {
                    item { InfoCard("TITLE LEADERBOARD", "Top observed videos and episodes", "Ranked by periodic playback observations over the last $reportDays days.", "") }
                    if (reportDetails.topTitles.isEmpty()) item { InfoCard("NO DATA", "No ranked titles yet", "Title rankings will appear as snapshots are collected.", "") }
                    val maxTitleSamples = reportDetails.topTitles.maxOfOrNull { it.samples } ?: 1
                    itemsIndexed(reportDetails.topTitles) { index, title ->
                        LeaderboardCard(index + 1, title.name, title.type + " · " + title.users + " viewers",
                            title.samples, maxTitleSamples, "samples")
                    }
                    item { InfoCard("ACCURACY", "Snapshot-based estimates", "These are not exact play starts, completions, or total watch duration.", "") }
                    item { OutlinedButton(onClick = { selected = "Reports" }) { Text("Back to Reports") } }
                }

                if (selected == "More") {
                    item {
                        Surface(color = PanelElevated, shape = RoundedCornerShape(24.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Border),
                            modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("YOUR WORKSPACE", color = Accent, fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                                Text("More from Allerflux", color = Color.White, fontSize = 21.sp,
                                    fontWeight = FontWeight.Bold)
                                Text("Explore deeper insights and manage your server connection.",
                                    color = Muted, fontSize = 13.sp)
                            }
                        }
                    }
                    item { MoreDestination("Alerts", "Server alerts", "Idle-session and Jellyfin availability alerts", Icons.Rounded.NotificationsActive) { selected = "Alerts" } }
                    item { MoreDestination("Top Viewers", "Viewer leaderboard", "Explore observed activity by Jellyfin account", Icons.Rounded.Leaderboard) { selected = "Top Viewers" } }
                    item { MoreDestination("Top Videos", "Title leaderboard", "See movies and episodes with the most observed samples", Icons.Rounded.Movie) { selected = "Top Videos" } }
                    item { MoreDestination("Settings", "Connection & preferences", "Manage your companion URL, API key and connection", Icons.Rounded.Settings) { selected = "Settings" } }
                    item { MoreDestination("About", "About Allerflux", "Product information, version and data transparency", Icons.Rounded.Info) { selected = "About" } }
                    item { InfoCard("ALLERIA LABS", "One view of your media server",
                        "Live server status, session intelligence, library inventory and observed viewing trends.", "Built for self-hosted Jellyfin") }
                }

                if (selected == "Alerts") {
                    if (alertError.isNotBlank()) {
                        item { InfoCard("ALERTS UNAVAILABLE", "Could not load alert history",
                            "Update the companion to a build with alert support.", alertError) }
                    } else {
                        item { Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            MetricCard("Active alerts", alertSummary.active.toString(), "Needs attention", Modifier.weight(1f))
                            MetricCard("Alert events", alertSummary.total.toString(), "Trigger and recovery history", Modifier.weight(1f))
                        } }
                        item { InfoCard("IDLE SESSION RULE", "Threshold: " + formatDuration(alertSummary.idleThreshold.toLong()),
                            "Alerts are raised only when Jellyfin provides a usable last-activity timestamp. Cooldown: ${alertSummary.cooldown} minutes.",
                            if (alertSummary.outboundEnabled) "Outbound webhook notifications enabled" else "In-app history only · outbound webhook not configured") }
                        item { Text("Recent alert history", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold) }
                        if (alertHistory.isEmpty()) {
                            item { InfoCard("ALL CLEAR", "No alert history yet",
                                "Idle-session and server-availability events will appear here when detected.", "") }
                        } else {
                            items(alertHistory) { alert ->
                                InfoCard(alert.kind.replace("_", " ").uppercase() + " · " + alert.status.uppercase(),
                                    alert.title, alert.message, alert.time.replace("T", " ").take(19))
                            }
                        }
                        item { Text("Allerflux alerts are informational and read-only. The app never disconnects or changes Jellyfin sessions.",
                            color = Muted, fontSize = 11.sp, lineHeight = 16.sp) }
                    }
                }
                if (selected == "Settings") {
                    item { SectionHeading("Server connection") }
                    item { InfoCard("CONNECTION", if (overview.connected) "Connected to Jellyfin" else "Not connected",
                        if (serverUrl.isBlank()) "Configure the companion URL below." else serverUrl, error) }
                    item {
                        Surface(color = Panel, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text("Companion configuration", color = Color.White, fontWeight = FontWeight.SemiBold)
                                OutlinedTextField(value = urlDraft, onValueChange = { urlDraft = it }, label = { Text("Companion URL") },
                                    placeholder = { Text("http://192.168.x.x:8787") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                                OutlinedTextField(value = keyDraft, onValueChange = { keyDraft = it }, label = { Text("Allerflux API key") },
                                    visualTransformation = PasswordVisualTransformation(),
                                    singleLine = true, modifier = Modifier.fillMaxWidth())
                                Text("For remote access, use HTTPS or a VPN.", color = Muted, fontSize = 12.sp)
                                Button(onClick = {
                                    val nextUrl = urlDraft.trim().trimEnd('/')
                                    val nextKey = keyDraft.trim()
                                    if (securePrefs.save(nextUrl, nextKey)) {
                                        serverUrl = nextUrl
                                        apiKey = nextKey
                                        selected = "Overview"
                                        error = ""
                                        refresh()
                                    } else {
                                        error = "Unable to securely save connection settings. Please try again."
                                    }
                                }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Ink)) {
                                    Text("Save & connect")
                                }
                                OutlinedButton(onClick = { refresh() }, modifier = Modifier.fillMaxWidth()) { Text("Test connection") }
                            }
                        }
                    }
                    item { SectionHeading("Companion diagnostics") }
                    item { InfoCard("COMPANION DIAGNOSTICS",
                        if (diagnosticsError.isBlank()) (if (diagnostics.databaseConnected) "Database healthy" else "Database unavailable") else "Diagnostics unavailable",
                        if (diagnosticsError.isBlank()) "Jellyfin polling: ${if (diagnostics.pollingEnabled) "enabled" else "not configured"} · interval: ${diagnostics.pollInterval}s · last success: ${diagnostics.lastSuccess}"
                            else diagnosticsError,
                        if (diagnosticsError.isBlank()) "Retention: ${diagnostics.snapshotRetention} days of snapshots · ${diagnostics.eventRetention} days of events · last poll error: ${diagnostics.lastError}" else "") }
                    item { SectionHeading("Backup & recovery") }
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            OutlinedButton(onClick = {
                                backupMessage = "Preparing database backup…"
                                scope.launch {
                                    runCatching { apiGetBytes(serverUrl, apiKey, "/api/v1/admin/backup") }
                                        .onSuccess { bytes ->
                                            pendingBackup = bytes
                                            backupMessage = "Choose where to save your database backup."
                                            backupLauncher.launch("allerflux-backup.sqlite3")
                                        }
                                        .onFailure { backupMessage = "Backup failed: ${it.message}" }
                                }
                            }, modifier = Modifier.fillMaxWidth(), enabled = serverUrl.isNotBlank() && apiKey.isNotBlank() && !loading) {
                                Icon(Icons.Rounded.Backup, contentDescription = null)
                                Spacer(Modifier.width(7.dp))
                                Text("Download database backup")
                            }
                            if (backupMessage.isNotBlank()) Text(backupMessage, color = Accent, fontSize = 12.sp)
                            Text("Database only. Keep your existing deploy/.env file and Docker volume safe. Restore is an operator-controlled maintenance step.",
                                color = Muted, fontSize = 11.sp, lineHeight = 16.sp)
                        }
                    }
                    item { SectionHeading("Preferences") }
                    item { InfoCard("APP PREFERENCES", "Automatic refresh · 60 seconds", "Dark theme with navy surfaces and cyan accents.", "") }
                    item { TextButton(onClick = { selected = "About" }) { Text("About Allerflux  →", color = Accent) } }
                }
                if (selected == "About") {
                    item { InfoCard("ALLERFLUX", "Media Server Intelligence", "Version 0.6.0 · Built by Alleria Labs", "Monitor. Analyze. Enjoy.") }
                    item { InfoCard("PRODUCT", "Alleria Labs", "Self-hosted Jellyfin monitoring, live activity, library inventory, and viewing analytics.", "") }
                    item { InfoCard("DATA ACCURACY", "Transparent analytics", "Snapshot rankings are periodic observations. Optional Jellyfin webhook events are counted separately; watch duration is never inferred.", "") }
                    item { OutlinedButton(onClick = { selected = "Settings" }) { Text("Back to Settings") } }
                }
                if (selected in listOf("Overview", "Activity", "Library", "Reports", "Alerts")) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = { refresh() }, enabled = !loading,
                            colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Ink)) {
                            if (loading) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Ink)
                            else Icon(Icons.Rounded.Refresh, contentDescription = null)
                            Spacer(Modifier.width(7.dp))
                            Text(if (loading) "Refreshing" else "Refresh data")
                        }
                        OutlinedButton(onClick = { selected = "Settings" }) { Text("Settings") }
                    }
                }
                item { Text("Allerflux  •  v0.6.0  •  Alleria Labs", color = Muted, fontSize = 11.sp, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) }
                }
            }
        }

    }
}


@Composable
private fun MoreDestination(title: String, subtitle: String, detail: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Surface(color = Panel, shape = RoundedCornerShape(20.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Border),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Surface(color = Color(0xFF203B42), shape = RoundedCornerShape(14.dp), modifier = Modifier.size(46.dp)) {
                Box(contentAlignment = Alignment.Center) { Icon(icon, contentDescription = null, tint = Accent) }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(subtitle, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(detail, color = Muted, fontSize = 12.sp, lineHeight = 17.sp)
            }
            Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = Muted)
        }
    }
}


@Composable
private fun SessionCard(session: SessionRow) {
    val stateColor = when (session.state.lowercase()) {
        "playing" -> Accent
        "paused" -> Color(0xFFFFC078)
        else -> Muted
    }
    Surface(color = Panel, shape = RoundedCornerShape(22.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Border),
        modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(session.user, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text(session.device, color = Muted, fontSize = 11.sp)
                }
                Surface(color = stateColor.copy(alpha = 0.14f), shape = RoundedCornerShape(30.dp)) {
                    Text(session.state.uppercase(), color = stateColor, fontSize = 9.sp,
                        fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
                }
            }
            Text(if (session.state == "idle") "No media currently playing" else session.title,
                color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Devices, contentDescription = null, tint = Accent, modifier = Modifier.size(14.dp))
                Text(session.client + if (session.version != "Version unavailable") " · " + session.version else "",
                    color = Muted, fontSize = 11.sp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Schedule, contentDescription = null, tint = Muted, modifier = Modifier.size(14.dp))
                Text("Last activity: " + session.lastActivity, color = Muted, fontSize = 11.sp)
                if (session.state == "idle") {
                    Text("·", color = Muted)
                    Text("Idle " + (session.idleSeconds?.let { formatDuration(it) } ?: "unknown"),
                        color = stateColor, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                } else if (session.position > 0) {
                    Text("·", color = Muted)
                    Text((session.position / 10000000L / 60).toString() + " min in",
                        color = Muted, fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
private fun LeaderboardCard(rank: Int, name: String, subtitle: String, value: Int,
    maxValue: Int, metricLabel: String) {
    val fraction = (value.toFloat() / maxValue.coerceAtLeast(1)).coerceIn(0f, 1f)
    Surface(color = Panel, shape = RoundedCornerShape(20.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Border),
        modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(15.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(13.dp)) {
            Surface(color = if (rank == 1) Color(0xFF24453F) else PanelElevated,
                shape = RoundedCornerShape(13.dp), modifier = Modifier.size(42.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Text(rank.toString().padStart(2, '0'),
                        color = if (rank == 1) Accent else Muted, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(name, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(subtitle, color = Muted, fontSize = 11.sp)
                Box(Modifier.fillMaxWidth().height(5.dp).background(Border, RoundedCornerShape(8.dp))) {
                    Box(Modifier.fillMaxWidth(fraction).height(5.dp)
                        .background(Accent, RoundedCornerShape(8.dp)))
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(value.toString(), color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(metricLabel, color = Muted, fontSize = 9.sp)
            }
        }
    }
}

@Composable
private fun InfoCard(title: String, heading: String, detail: String, extra: String) {
    Surface(color = Panel, shape = RoundedCornerShape(20.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Border), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title.uppercase(), color = Accent, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Text(heading, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            if (detail.isNotBlank()) Text(detail, color = Muted, fontSize = 13.sp, lineHeight = 19.sp)
            if (extra.isNotBlank()) Text(extra, color = Color(0xFFFF9A8B), fontSize = 12.sp)
        }
    }
}
@Composable
private fun SectionHeading(value: String) {
    Text(value, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
}
@Composable
private fun MetricCard(title: String, value: String, detail: String, modifier: Modifier = Modifier) {
    Surface(modifier = modifier, color = PanelElevated, shape = RoundedCornerShape(20.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Border)) {
        Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, color = Muted, fontSize = 12.sp)
            Text(value, color = Color.White, fontSize = 27.sp, fontWeight = FontWeight.Bold)
            Text(detail, color = Accent, fontSize = 10.sp)
        }
    }
}
