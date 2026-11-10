package com.alleria.allerflux

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
    val capturedAt: String = "",
    val message: String = "Not configured"
)
private data class SessionRow(
    val username: String, val itemName: String, val mediaType: String,
    val state: String, val positionTicks: Long
)
private data class HistoryRow(
    val username: String, val itemName: String, val mediaType: String,
    val state: String, val capturedAt: String
)
private data class DashboardData(
    val overview: Overview = Overview(),
    val sessions: List<SessionRow> = emptyList(),
    val history: List<HistoryRow> = emptyList()
)

private suspend fun apiGet(baseUrl: String, apiKey: String, path: String): String =
    withContext(Dispatchers.IO) {
        val connection = URL(baseUrl.trim().trimEnd('/') + path).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 8000
            connection.readTimeout = 12000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("X-Allerflux-Key", apiKey)
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream.bufferedReader().use { it.readText() }
            if (code !in 200..299) {
                val detail = runCatching { JSONObject(body).optString("detail") }.getOrDefault(body)
                error("HTTP $code: $detail")
            }
            body
        } finally {
            connection.disconnect()
        }
    }

private suspend fun fetchDashboard(baseUrl: String, apiKey: String): DashboardData {
    val overviewJson = JSONObject(apiGet(baseUrl, apiKey, "/api/v1/overview"))
    val sessionsJson = JSONArray(apiGet(baseUrl, apiKey, "/api/v1/sessions"))
    val historyJson = JSONArray(apiGet(baseUrl, apiKey, "/api/v1/history?limit=300"))
    val sessions = (0 until sessionsJson.length()).map { i ->
        val item = sessionsJson.getJSONObject(i)
        SessionRow(
            item.optString("username", "Unknown user"),
            item.optString("item_name", "No media details"),
            item.optString("media_type", "Media"),
            item.optString("play_state", "unknown"),
            item.optLong("position_ticks", 0L)
        )
    }
    val history = (0 until historyJson.length()).map { i ->
        val item = historyJson.getJSONObject(i)
        HistoryRow(
            item.optString("user", "Unknown user"),
            item.optString("item_name", "Unknown title"),
            item.optString("media_type", "Media"),
            item.optString("state", "unknown"),
            item.optString("captured_at", "")
        )
    }
    val overview = Overview(
        users = overviewJson.optInt("registered_users", 0).toString(),
        sessions = overviewJson.optInt("active_sessions", 0).toString(),
        playing = overviewJson.optInt("currently_playing", 0).toString(),
        paused = overviewJson.optInt("paused", 0).toString(),
        connected = overviewJson.optBoolean("server_connected", false),
        capturedAt = overviewJson.optString("captured_at", ""),
        message = "Connected to Jellyfin"
    )
    return DashboardData(overview, sessions, history)
}

@Composable
fun AllerfluxApp(context: Context) {
    val prefs = remember { context.getSharedPreferences("allerflux", Context.MODE_PRIVATE) }
    var serverUrl by remember { mutableStateOf(prefs.getString("server_url", "") ?: "") }
    var apiKey by remember { mutableStateOf(prefs.getString("api_key", "") ?: "") }
    var data by remember { mutableStateOf(DashboardData()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
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
            try {
                data = fetchDashboard(serverUrl, apiKey)
            } catch (e: Exception) {
                error = e.message ?: "Unable to load dashboard data"
                if (!data.overview.connected) data = DashboardData(
                    overview = Overview(message = "Connection failed")
                )
            } finally {
                loading = false
            }
        }
    }

    MaterialTheme(colorScheme = darkColorScheme(
        background = Ink, surface = Panel, primary = Accent,
        onBackground = Color.White, onSurface = Color.White
    )) {
        Scaffold(containerColor = Ink, bottomBar = {
            NavigationBar(containerColor = Panel) {
                listOf("Overview", "Activity", "Library", "Reports").forEach { tab ->
                    NavigationBarItem(
                        selected = selected == tab,
                        onClick = { selected = tab },
                        icon = {
                            Icon(when (tab) {
                                "Overview" -> Icons.Rounded.Dashboard
                                "Activity" -> Icons.Rounded.PlayCircle
                                "Library" -> Icons.Rounded.VideoLibrary
                                else -> Icons.Rounded.QueryStats
                            }, contentDescription = tab)
                        },
                        label = { Text(tab) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Accent, selectedTextColor = Accent,
                            indicatorColor = Color(0xFF203B42), unselectedTextColor = Muted
                        )
                    )
                }
            }
        }) { padding ->
            LazyColumn(
                modifier = Modifier.fillMaxSize().background(Ink).padding(padding),
                contentPadding = PaddingValues(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("ALLERIA LABS  /  MEDIA INTELLIGENCE", color = Accent, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
                            Text("Allerflux", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                            Text("Your Media Server Intelligence", color = Muted, fontSize = 13.sp)
                        }
                        IconButton(onClick = { showSettings = true }) {
                            Icon(Icons.Rounded.Settings, contentDescription = "Settings", tint = Color.White)
                        }
                    }
                }
                when (selected) {
                    "Overview" -> {
                        item { SectionHeading("Server overview") }
                        item {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                MetricCard("Registered users", data.overview.users, "Jellyfin accounts", Modifier.weight(1f))
                                MetricCard("Active sessions", data.overview.sessions, "Live sessions", Modifier.weight(1f))
                            }
                        }
                        item {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                MetricCard("Playing now", data.overview.playing, "Currently playing", Modifier.weight(1f))
                                MetricCard("Paused", data.overview.paused, "Paused sessions", Modifier.weight(1f))
                            }
                        }
                        item {
                            InfoPanel("COMPANION CONNECTION", data.overview.message,
                                if (data.overview.connected) serverUrl else "Configure your companion URL and API key to load live server data.")
                        }
                        item {
                            InfoPanel("DATA COLLECTION", "Snapshot collection",
                                "The companion stores periodic session observations. Exact play counts, completion rates, and watch duration require playback event ingestion and are not inferred from snapshots.")
                        }
                    }
                    "Activity" -> {
                        item { SectionHeading("Live playback activity") }
                        item {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                MetricCard("Active sessions", data.sessions.size.toString(), "Current Jellyfin sessions", Modifier.weight(1f))
                                MetricCard("Playing", data.sessions.count { it.state == "playing" }.toString(), "Live playback", Modifier.weight(1f))
                            }
                        }
                        if (data.sessions.isEmpty()) {
                            item { EmptyPanel("No active playback sessions", "When users start or pause media, their current session details will appear here.") }
                        } else {
                            items(data.sessions) { session ->
                                Surface(color = Panel, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                        Text(session.itemName, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                                        Text(session.username + "  •  " + session.mediaType, color = Muted, fontSize = 12.sp)
                                        Text(session.state.uppercase(), color = Accent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                        item { SectionHeading("Recent observations") }
                        items(data.history.take(30)) { row -> HistoryCard(row) }
                        if (data.history.isEmpty()) item { EmptyPanel("No observations recorded yet", "History appears after the companion's polling service captures sessions.") }
                    }
                    "Library" -> {
                        val observedItems = data.history.distinctBy { it.itemName }.sortedBy { it.itemName.lowercase() }
                        item { SectionHeading("Observed media") }
                        item {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                MetricCard("Unique titles", observedItems.size.toString(), "Seen in snapshots", Modifier.weight(1f))
                                MetricCard("Media types", data.history.map { it.mediaType }.distinct().size.toString(), "Observed types", Modifier.weight(1f))
                            }
                        }
                        item { InfoPanel("LIBRARY DATA SCOPE", "Playback-observed titles",
                            "This list is built from captured playback sessions. It is not yet a complete inventory of every movie, series, or episode stored in Jellyfin.") }
                        if (observedItems.isEmpty()) {
                            item { EmptyPanel("No media observed yet", "Titles will appear here after users play media and the companion records snapshots.") }
                        } else {
                            items(observedItems) { row ->
                                Surface(color = Panel, shape = RoundedCornerShape(15.dp), modifier = Modifier.fillMaxWidth()) {
                                    Row(Modifier.padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Rounded.Movie, contentDescription = null, tint = Accent, modifier = Modifier.size(28.dp))
                                        Spacer(Modifier.width(12.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text(row.itemName, color = Color.White, fontWeight = FontWeight.SemiBold)
                                            Text(row.mediaType, color = Muted, fontSize = 12.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    else -> {
                        item { SectionHeading("Usage reports") }
                        item {
                            val uniqueTitles = data.history.map { it.itemName }.distinct().size
                            val uniqueUsers = data.history.map { it.username }.distinct().size
                            val playingSamples = data.history.count { it.state == "playing" }
                            val pausedSamples = data.history.count { it.state == "paused" }
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    MetricCard("Snapshots", data.history.size.toString(), "Captured observations", Modifier.weight(1f))
                                    MetricCard("Observed users", uniqueUsers.toString(), "In snapshot history", Modifier.weight(1f))
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    MetricCard("Unique titles", uniqueTitles.toString(), "Observed media", Modifier.weight(1f))
                                    MetricCard("Playing samples", playingSamples.toString(), "Not play counts", Modifier.weight(1f))
                                }
                                MetricCard("Paused samples", pausedSamples.toString(), "Not pause events", Modifier.fillMaxWidth())
                            }
                        }
                        item { InfoPanel("REPORTING ACCURACY", "Snapshot-based estimates",
                            "These figures count periodic observations, not unique playback starts, exact watch time, completion rates, or total plays. A session may contribute multiple snapshots.") }
                        item { SectionHeading("Latest captured records") }
                        items(data.history.take(20)) { row -> HistoryCard(row) }
                        if (data.history.isEmpty()) item { EmptyPanel("Not enough history yet", "Reports will populate as the companion collects session snapshots.") }
                    }
                }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (error.isNotBlank()) Text(error, color = Color(0xFFFF9A8B), fontSize = 12.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
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
                }
                item { Text("Allerflux  •  v0.3.0  •  Alleria Labs", color = Muted, fontSize = 11.sp, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) }
            }
        }
        if (showSettings) {
            var urlDraft by remember(showSettings) { mutableStateOf(serverUrl) }
            var keyDraft by remember(showSettings) { mutableStateOf(apiKey) }
            AlertDialog(
                onDismissRequest = { showSettings = false },
                containerColor = Panel,
                title = { Text("Companion settings", color = Color.White) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(value = urlDraft, onValueChange = { urlDraft = it }, label = { Text("Companion URL") }, placeholder = { Text("http://192.168.x.x:8787") }, singleLine = true)
                        OutlinedTextField(value = keyDraft, onValueChange = { keyDraft = it }, label = { Text("Allerflux API key") }, singleLine = true)
                        Text("Use your server's reachable companion address. For remote access, use HTTPS or a VPN.", color = Muted, fontSize = 12.sp)
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        serverUrl = urlDraft.trim().trimEnd('/')
                        apiKey = keyDraft.trim()
                        prefs.edit().putString("server_url", serverUrl).putString("api_key", apiKey).apply()
                        showSettings = false
                        refresh()
                    }) { Text("Save & connect", color = Accent) }
                },
                dismissButton = { TextButton(onClick = { showSettings = false }) { Text("Cancel", color = Muted) } }
            )
        }
    }
}

@Composable
private fun HistoryCard(row: HistoryRow) {
    Surface(color = Panel, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(row.itemName, color = Color.White, fontWeight = FontWeight.SemiBold)
            Text(row.username + "  •  " + row.mediaType + "  •  " + row.state, color = Accent, fontSize = 11.sp)
            Text(row.capturedAt.replace("T", " ").take(19), color = Muted, fontSize = 11.sp)
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

@Composable
private fun InfoPanel(eyebrow: String, heading: String, body: String) {
    Surface(color = Panel, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(eyebrow, color = Accent, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Text(heading, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(body, color = Muted, fontSize = 13.sp, lineHeight = 19.sp)
        }
    }
}

@Composable
private fun EmptyPanel(heading: String, body: String) {
    Surface(color = Panel, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Rounded.VideoLibrary, contentDescription = null, tint = Accent, modifier = Modifier.size(30.dp))
            Text(heading, color = Color.White, fontWeight = FontWeight.SemiBold)
            Text(body, color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
        }
    }
}
