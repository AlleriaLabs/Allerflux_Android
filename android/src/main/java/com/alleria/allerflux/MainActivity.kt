package com.alleria.allerflux

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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

private val Ink = Color(0xFF0B1018)
private val Panel = Color(0xFF151E2B)
private val Accent = Color(0xFF55D6C2)
private val Muted = Color(0xFF8B9AAF)

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
private data class SessionRow(val user: String, val title: String, val type: String, val state: String, val position: Long)
private data class HistoryRow(val user: String, val title: String, val type: String, val state: String, val time: String)
private data class LibraryStats(val movies: Int = 0, val series: Int = 0, val episodes: Int = 0, val songs: Int = 0, val albums: Int = 0, val artists: Int = 0)
private data class ReportStats(val snapshots: Int = 0, val users: Int = 0, val titles: Int = 0, val playing: Int = 0, val paused: Int = 0)
private data class DailyStat(val date: String, val snapshots: Int)
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
        SessionRow(j.optString("username", "Unknown user"), j.optString("item_name", "No media title"),
            j.optString("media_type", "Media"), j.optString("play_state", "idle"), j.optLong("position_ticks"))
    }
}

private suspend fun loadLibrary(base: String, key: String): LibraryStats {
    val j = JSONObject(apiGet(base, key, "/api/v1/library"))
    return LibraryStats(j.optInt("movie_count"), j.optInt("series_count"), j.optInt("episode_count"),
        j.optInt("song_count"), j.optInt("album_count"), j.optInt("artist_count"))
}

private suspend fun loadReportStats(base: String, key: String): ReportStats {
    val j = JSONObject(apiGet(base, key, "/api/v1/reports/summary?days=30"))
    return ReportStats(j.optInt("snapshot_count"), j.optInt("observed_users"), j.optInt("observed_titles"),
        j.optInt("playing_samples"), j.optInt("paused_samples"))
}

private suspend fun loadReportDetails(base: String, key: String): ReportDetails {
    val summary = JSONObject(apiGet(base, key, "/api/v1/reports/summary?days=30"))
    val dailyJson = summary.optJSONArray("daily") ?: JSONArray()
    val daily = (0 until dailyJson.length()).map {
        val row = dailyJson.getJSONObject(it)
        DailyStat(row.optString("date"), row.optInt("snapshots"))
    }
    val titlesJson = JSONObject(apiGet(base, key, "/api/v1/reports/top-titles?days=30&limit=8"))
        .optJSONArray("items") ?: JSONArray()
    val titles = (0 until titlesJson.length()).map {
        val row = titlesJson.getJSONObject(it)
        TopTitle(row.optString("item_name", "Unknown title"), row.optString("media_type", "Media"),
            row.optInt("snapshot_count"), row.optInt("observed_users"))
    }
    val usersJson = JSONObject(apiGet(base, key, "/api/v1/reports/top-users?days=30&limit=8"))
        .optJSONArray("users") ?: JSONArray()
    val users = (0 until usersJson.length()).map {
        val row = usersJson.getJSONObject(it)
        TopUser(row.optString("username", "Unknown user"), row.optInt("snapshot_count"), row.optInt("observed_titles"))
    }
    return ReportDetails(daily, titles, users)
}

private suspend fun loadHistory(base: String, key: String): List<HistoryRow> {
    val a = JSONArray(apiGet(base, key, "/api/v1/history?limit=200"))
    return (0 until a.length()).map {
        val j = a.getJSONObject(it)
        HistoryRow(j.optString("user", "Unknown user"), j.optString("item_name", "Unknown title"),
            j.optString("media_type", "Media"), j.optString("state", "unknown"), j.optString("captured_at"))
    }
}

@Composable
fun AllerfluxApp(context: Context) {
    val prefs = remember { context.getSharedPreferences("allerflux", Context.MODE_PRIVATE) }
    var serverUrl by remember { mutableStateOf(prefs.getString("server_url", "") ?: "") }
    var apiKey by remember { mutableStateOf(prefs.getString("api_key", "") ?: "") }
    var overview by remember { mutableStateOf(Overview()) }
    var sessions by remember { mutableStateOf<List<SessionRow>>(emptyList()) }
    var history by remember { mutableStateOf<List<HistoryRow>>(emptyList()) }
    var library by remember { mutableStateOf(LibraryStats()) }
    var libraryError by remember { mutableStateOf("") }
    var reportStats by remember { mutableStateOf(ReportStats()) }
    var reportDetails by remember { mutableStateOf(ReportDetails()) }
    var reportError by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var activityError by remember { mutableStateOf("") }
    var historyError by remember { mutableStateOf("") }
    var showSettings by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf("Overview") }
    val scope = rememberCoroutineScope()

    fun refresh() {
        if (serverUrl.isBlank() || apiKey.isBlank()) {
            error = "Enter the companion URL and API key in Settings."
            showSettings = true
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
            try { reportStats = loadReportStats(serverUrl, apiKey); reportDetails = loadReportDetails(serverUrl, apiKey); reportError = "" }
            catch (e: Exception) { reportError = e.message ?: "Unable to load report summary" }
            loading = false
        }
    }

    LaunchedEffect(serverUrl, apiKey) {
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
                listOf("Overview", "Activity", "Library", "Reports").forEach { tab ->
                    NavigationBarItem(selected = selected == tab, onClick = { selected = tab },
                        icon = { Icon(when (tab) {
                            "Overview" -> Icons.Rounded.Dashboard
                            "Activity" -> Icons.Rounded.PlayCircle
                            "Library" -> Icons.Rounded.VideoLibrary
                            else -> Icons.Rounded.QueryStats
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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("ALLERIA LABS  /  MEDIA INTELLIGENCE", color = Accent, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
                            Text("Allerflux", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                            Text("Your Media Server Intelligence", color = Muted, fontSize = 13.sp)
                        }
                        IconButton(onClick = { showSettings = true }) { Icon(Icons.Rounded.Settings, "Settings", tint = Color.White) }
                    }
                }
                item { SectionHeading(when (selected) {
                    "Overview" -> "Server overview"
                    "Activity" -> "Live activity"
                    "Library" -> "Media library"
                    else -> "Usage reports"
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
                    else items(sessions.size) { i ->
                        val s = sessions[i]
                        InfoCard(s.user, s.title, s.type + "  •  " + s.state.uppercase(),
                            if (s.position > 0) "Position: " + (s.position / 10000000L / 60) + " min" else "")
                    }
                }

                if (selected == "Library") {
                    if (libraryError.isNotBlank()) item { InfoCard("LIBRARY UNAVAILABLE", "Could not load Jellyfin inventory", libraryError, "") }
                    item { Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        MetricCard("Movies", library.movies.toString(), "Movie library", Modifier.weight(1f))
                        MetricCard("TV series", library.series.toString(), "Series library", Modifier.weight(1f))
                    } }
                    item { Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        MetricCard("Episodes", library.episodes.toString(), "TV episodes", Modifier.weight(1f))
                        MetricCard("Songs", library.songs.toString(), "Music tracks", Modifier.weight(1f))
                    } }
                    item { Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        MetricCard("Albums", library.albums.toString(), "Music albums", Modifier.weight(1f))
                        MetricCard("Artists", library.artists.toString(), "Music artists", Modifier.weight(1f))
                    } }
                    item { InfoCard("JELLYFIN LIBRARY", "Live inventory counts",
                        "Counts are fetched directly from Jellyfin. Refresh data to update the inventory.", "") }
                }

                if (selected == "Reports") {
                    if (reportError.isNotBlank()) item { InfoCard("REPORT SUMMARY UNAVAILABLE", "Could not load 30-day summary", reportError, "") }
                    item { Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        MetricCard("Snapshots", reportStats.snapshots.toString(), "Last 30 days", Modifier.weight(1f))
                        MetricCard("Observed users", reportStats.users.toString(), "Distinct account IDs", Modifier.weight(1f))
                    } }
                    item { Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        MetricCard("Observed titles", reportStats.titles.toString(), "Distinct item IDs", Modifier.weight(1f))
                        MetricCard("Playing samples", reportStats.playing.toString(), "Snapshot count", Modifier.weight(1f))
                    } }
                    item { MetricCard("Paused samples", reportStats.paused.toString(), "Snapshot count", Modifier.fillMaxWidth()) }
                    item { SectionHeading("Daily observed activity") }
                    item {
                        Surface(color = Panel, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Playback samples · last 30 days", color = Muted, fontSize = 12.sp)
                                if (reportDetails.daily.isEmpty()) {
                                    Text("No observations recorded in this period.", color = Muted, fontSize = 13.sp)
                                } else {
                                    Canvas(Modifier.fillMaxWidth().height(120.dp)) {
                                        val maxValue = (reportDetails.daily.maxOfOrNull { it.snapshots } ?: 0).coerceAtLeast(1)
                                        val slot = size.width / reportDetails.daily.size
                                        val barWidth = (slot * 0.62f).coerceAtLeast(2f)
                                        reportDetails.daily.forEachIndexed { index, day ->
                                            val barHeight = size.height * day.snapshots.toFloat() / maxValue
                                            drawRect(Accent, Offset(index * slot + (slot - barWidth) / 2f, size.height - barHeight),
                                                Size(barWidth, barHeight))
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
                    item { SectionHeading("Most observed titles") }
                    if (reportDetails.topTitles.isEmpty()) item { InfoCard("NO TITLE DATA", "No ranked titles yet", "Playback observations will populate this list.", "") }
                    items(reportDetails.topTitles) { title ->
                        InfoCard(title.type, title.name, "${title.samples} observed samples", "${title.users} observed users")
                    }
                    item { SectionHeading("User activity") }
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

                item {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = { refresh() }, enabled = !loading,
                            colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Ink)) {
                            if (loading) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Ink)
                            else Icon(Icons.Rounded.Refresh, contentDescription = null)
                            Spacer(Modifier.width(7.dp))
                            Text(if (loading) "Refreshing" else "Refresh data")
                        }
                        OutlinedButton(onClick = { showSettings = true }) { Text("Settings") }
                    }
                }
                item { Text("Allerflux  •  v0.4.0  •  Alleria Labs", color = Muted, fontSize = 11.sp, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) }
            }
        }
        if (showSettings) {
            var urlDraft by remember(showSettings) { mutableStateOf(serverUrl) }
            var keyDraft by remember(showSettings) { mutableStateOf(apiKey) }
            AlertDialog(onDismissRequest = { showSettings = false }, containerColor = Panel,
                title = { Text("Companion settings", color = Color.White) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(value = urlDraft, onValueChange = { urlDraft = it }, label = { Text("Companion URL") },
                            placeholder = { Text("http://192.168.x.x:8787") }, singleLine = true)
                        OutlinedTextField(value = keyDraft, onValueChange = { keyDraft = it }, label = { Text("Allerflux API key") }, singleLine = true)
                        Text("Use your server's reachable companion address. For remote access, use HTTPS or a VPN.", color = Muted, fontSize = 12.sp)
                    }
                },
                confirmButton = { TextButton(onClick = {
                    serverUrl = urlDraft.trim().trimEnd('/')
                    apiKey = keyDraft.trim()
                    prefs.edit().putString("server_url", serverUrl).putString("api_key", apiKey).apply()
                    showSettings = false
                    refresh()
                }) { Text("Save & connect", color = Accent) } },
                dismissButton = { TextButton(onClick = { showSettings = false }) { Text("Cancel", color = Muted) } })
        }
    }
}

@Composable
private fun InfoCard(title: String, heading: String, detail: String, extra: String) {
    Surface(color = Panel, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
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
    Surface(modifier = modifier, color = Panel, shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, color = Muted, fontSize = 12.sp)
            Text(value, color = Color.White, fontSize = 27.sp, fontWeight = FontWeight.Bold)
            Text(detail, color = Accent, fontSize = 10.sp)
        }
    }
}
