package com.alleria.allerflux

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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

private suspend fun fetchOverview(baseUrl: String, apiKey: String): Overview =
    withContext(Dispatchers.IO) {
        val endpoint = baseUrl.trim().trimEnd('/') + "/api/v1/overview"
        val connection = (URL(endpoint).openConnection() as HttpURLConnection)
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 8000
            connection.readTimeout = 10000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("X-Allerflux-Key", apiKey)
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream.bufferedReader().use { it.readText() }
            if (code !in 200..299) error("HTTP $code: " + runCatching { JSONObject(body).optString("detail") }.getOrDefault(body))
            val json = JSONObject(body)
            Overview(
                users = json.optInt("registered_users", 0).toString(),
                sessions = json.optInt("active_sessions", 0).toString(),
                playing = json.optInt("currently_playing", 0).toString(),
                paused = json.optInt("paused", 0).toString(),
                connected = json.optBoolean("server_connected", false),
                message = "Connected to Jellyfin"
            )
        } finally {
            connection.disconnect()
        }
    }

@Composable
fun AllerfluxApp(context: Context) {
    val prefs = remember { context.getSharedPreferences("allerflux", Context.MODE_PRIVATE) }
    var serverUrl by remember { mutableStateOf(prefs.getString("server_url", "") ?: "") }
    var apiKey by remember { mutableStateOf(prefs.getString("api_key", "") ?: "") }
    var overview by remember { mutableStateOf(Overview()) }
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
                overview = fetchOverview(serverUrl, apiKey)
            } catch (e: Exception) {
                overview = Overview(message = "Connection failed")
                error = e.message ?: "Unable to connect"
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
                item { SectionHeading(when (selected) { "Overview" -> "Server overview"; "Activity" -> "Live playback activity"; "Library" -> "Media library"; else -> "Viewing reports" }) }
                if (selected == "Overview") {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        MetricCard("Registered users", overview.users, "Jellyfin accounts", Modifier.weight(1f))
                        MetricCard("Active sessions", overview.sessions, "Live sessions", Modifier.weight(1f))
                    }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        MetricCard("Playing now", overview.playing, "Currently playing", Modifier.weight(1f))
                        MetricCard("Paused", overview.paused, "Paused sessions", Modifier.weight(1f))
                    }
                }
                item {
                    Surface(color = Panel, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                            Text("COMPANION CONNECTION", color = Accent, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                            Text(overview.message, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                            Text(if (overview.connected) serverUrl else "Configure your companion URL and API key to load live server data.", color = Muted, fontSize = 13.sp)
                            if (error.isNotBlank()) Text(error, color = Color(0xFFFF9A8B), fontSize = 12.sp)
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Button(onClick = { refresh() }, enabled = !loading, colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Ink)) {
                                    if (loading) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Ink)
                                    else Icon(Icons.Rounded.Refresh, contentDescription = null)
                                    Spacer(Modifier.width(7.dp))
                                    Text(if (loading) "Connecting" else "Refresh")
                                }
                                OutlinedButton(onClick = { showSettings = true }) { Text("Settings") }
                            }
                        }
                    }
                }
                item {
                    Surface(color = Panel, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            Text("PLAYBACK ANALYTICS", color = Accent, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                            Text("Snapshot collection", color = Color.White, fontWeight = FontWeight.SemiBold)
                            Text("The companion stores periodic session observations. Exact play counts, completion rates, and watch duration require playback event ingestion and are not inferred from snapshots.", color = Muted, fontSize = 13.sp, lineHeight = 19.sp)
                        }
                    }
                }
                } else {
                    item {
                        when (selected) {
                            "Activity" -> ActivityContent(serverUrl, apiKey)
                            "Library" -> LibraryContent(serverUrl, apiKey)
                            else -> ReportsContent(serverUrl, apiKey)
                        }
                    }
                }
                item { Text("Allerflux  •  v0.2.0  •  Alleria Labs", color = Muted, fontSize = 11.sp, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) }
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


private suspend fun fetchArray(baseUrl: String, apiKey: String, path: String): org.json.JSONArray =
    withContext(Dispatchers.IO) {
        val connection = (URL(baseUrl.trim().trimEnd('/') + path).openConnection() as HttpURLConnection)
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 8000
            connection.readTimeout = 12000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("X-Allerflux-Key", apiKey)
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream.bufferedReader().use { it.readText() }
            if (code !in 200..299) error("HTTP $code: " + runCatching { JSONObject(body).optString("detail") }.getOrDefault(body))
            org.json.JSONArray(body)
        } finally { connection.disconnect() }
    }

@Composable
private fun ActivityContent(base: String, key: String) {
    var rows by remember { mutableStateOf<org.json.JSONArray?>(null) }
    var error by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    LaunchedEffect(base, key) {
        if (base.isBlank() || key.isBlank()) { error = "Configure the companion in Settings."; loading = false }
        else try { rows = fetchArray(base, key, "/api/v1/sessions") } catch (e: Exception) { error = e.message ?: "Unable to load activity" }
        loading = false
    }
    when {
        loading -> CircularProgressIndicator(color = Accent)
        error.isNotBlank() -> Text(error, color = Color(0xFFFF9A8B))
        rows == null || rows!!.length() == 0 -> InfoPanel("No active playback", "No Jellyfin sessions are currently reporting playback.")
        else -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("${rows!!.length()} session(s) returned by Jellyfin", color = Muted, fontSize = 12.sp)
            for (i in 0 until rows!!.length()) {
                val row = rows!!.optJSONObject(i) ?: JSONObject()
                InfoPanel(row.optString("item_name", "Unknown title"), "${row.optString("username", "Unknown user")}  •  ${row.optString("media_type", "Media")}  •  ${row.optString("play_state", "unknown").uppercase()}")
            }
        }
    }
}

@Composable
private fun LibraryContent(base: String, key: String) {
    var rows by remember { mutableStateOf<org.json.JSONArray?>(null) }
    var error by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    LaunchedEffect(base, key) {
        if (base.isBlank() || key.isBlank()) { error = "Configure the companion in Settings."; loading = false }
        else try { rows = fetchArray(base, key, "/api/v1/libraries") } catch (e: Exception) { error = e.message ?: "Unable to load libraries" }
        loading = false
    }
    when {
        loading -> CircularProgressIndicator(color = Accent)
        error.isNotBlank() -> Text(error, color = Color(0xFFFF9A8B))
        rows == null || rows!!.length() == 0 -> InfoPanel("No libraries found", "The companion returned no Jellyfin media libraries.")
        else -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("${rows!!.length()} Jellyfin libraries", color = Muted, fontSize = 12.sp)
            for (i in 0 until rows!!.length()) {
                val row = rows!!.optJSONObject(i) ?: JSONObject()
                InfoPanel(row.optString("name", "Unnamed library"), "${row.optString("collection_type", "Mixed")}  •  ${row.optString("item_count", "—")} items")
            }
        }
    }
}

@Composable
private fun ReportsContent(base: String, key: String) {
    var rows by remember { mutableStateOf<org.json.JSONArray?>(null) }
    var error by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    LaunchedEffect(base, key) {
        if (base.isBlank() || key.isBlank()) { error = "Configure the companion in Settings."; loading = false }
        else try { rows = fetchArray(base, key, "/api/v1/history?limit=200") } catch (e: Exception) { error = e.message ?: "Unable to load reports" }
        loading = false
    }
    when {
        loading -> CircularProgressIndicator(color = Accent)
        error.isNotBlank() -> Text(error, color = Color(0xFFFF9A8B))
        rows == null || rows!!.length() == 0 -> InfoPanel("Collecting report data", "Reports will appear as the companion records periodic playback snapshots.")
        else -> {
            val data = rows!!
            val titles = (0 until data.length()).mapNotNull { data.optJSONObject(it)?.optString("item_name")?.takeIf(String::isNotBlank) }
            val users = (0 until data.length()).mapNotNull { data.optJSONObject(it)?.optString("user")?.takeIf(String::isNotBlank) }.distinct()
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MetricCard("Snapshots", data.length().toString(), "Recorded observations", Modifier.weight(1f))
                    MetricCard("Observed users", users.size.toString(), "Distinct usernames", Modifier.weight(1f))
                }
                InfoPanel("Most observed titles", titles.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(8).joinToString("\n") { "${it.key}  •  ${it.value} snapshots" }.ifBlank { "No title data yet" })
                Text("These are periodic snapshots, not exact play counts, completion rates, or watch duration.", color = Muted, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun InfoPanel(title: String, detail: String) {
    Surface(color = Panel, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(title, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            Text(detail, color = Muted, fontSize = 13.sp, lineHeight = 19.sp)
        }
    }
}
