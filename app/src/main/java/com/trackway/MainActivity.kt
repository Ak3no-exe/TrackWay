package com.trackway

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.cachemanager.CacheManager
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay

val MODES = listOf("🚗 Voiture", "🚆 Train", "✈️ Avion", "🚶 Marche", "🚲 Vélo", "🏍️ Moto",
    "🚌 Bus", "🚇 Métro", "🛴 Trottinette", "⛵ Bateau", "➕ Autre")
val COLORS = listOf(0xFF2196F3, 0xFF9C27B0, 0xFFFF9800, 0xFF4CAF50, 0xFF00BCD4, 0xFFF44336,
    0xFFFFC107, 0xFF3F51B5, 0xFF8BC34A, 0xFF009688, 0xFF795548)

fun hasLoc(c: Context) = ContextCompat.checkSelfPermission(c, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
fun dur(ms: Long): String { val s = ms / 1000; return "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60) }
fun avg(t: Trip): Double { val end = t.endMs ?: System.currentTimeMillis(); val h = (end - t.startMs) / 3.6e6; return if (h > 0) t.distance / 1000 / h else 0.0 }
fun hm(ms: Long): String = java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale.FRANCE).format(java.util.Date(ms))

class MainActivity : ComponentActivity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Configuration.getInstance().apply {
            load(this@MainActivity, getSharedPreferences("osm", MODE_PRIVATE))
            userAgentValue = packageName
        }
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) { App() }
            }
        }
    }
}

@Composable
fun App() {
    val ctx = LocalContext.current
    val dao = remember { Db.get(ctx).dao() }
    val scope = rememberCoroutineScope()
    val trips by dao.trips().collectAsState(emptyList())
    var tab by remember { mutableIntStateOf(0) }
    var perm by remember { mutableStateOf(hasLoc(ctx)) }
    var pick by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Trip?>(null) }
    var exporting by remember { mutableStateOf<Trip?>(null) }
    val active = trips.firstOrNull { it.endMs == null }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { perm = hasLoc(ctx) }
    LaunchedEffect(Unit) {
        val p = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) p += Manifest.permission.POST_NOTIFICATIONS
        permLauncher.launch(p.toTypedArray())
    }
    // (Re)lance le service si un trajet est en cours : démarrage et reprise après fermeture brutale
    LaunchedEffect(active?.id, perm) {
        if (active != null && perm) ctx.startForegroundService(Intent(ctx, TrackingService::class.java))
    }
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/gpx+xml")) { uri ->
        val t = exporting
        if (uri != null && t != null) scope.launch(Dispatchers.IO) {
            val pts = dao.pts(t.id)
            ctx.contentResolver.openOutputStream(uri)?.use { it.write(gpx(t, pts).toByteArray()) }
        }
    }

    if (pick) AlertDialog(
        onDismissRequest = { pick = false },
        title = { Text("Moyen de transport") },
        text = {
            LazyColumn { items(MODES) { m ->
                TextButton(onClick = {
                    pick = false
                    scope.launch(Dispatchers.IO) {
                        dao.insertTrip(Trip(name = "Trajet " + hm(System.currentTimeMillis()), mode = m, startMs = System.currentTimeMillis()))
                    }
                    selected = null
                }, modifier = Modifier.fillMaxWidth()) { Text(m) }
            } }
        },
        confirmButton = {}, dismissButton = { TextButton(onClick = { pick = false }) { Text("Annuler") } }
    )

    Scaffold(bottomBar = {
        NavigationBar {
            listOf("🗺️" to "Carte", "📋" to "Trajets", "📊" to "Stats").forEachIndexed { i, (e, l) ->
                NavigationBarItem(selected = tab == i, onClick = { tab = i }, icon = { Text(e) }, label = { Text(l) })
            }
        }
    }) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (tab) {
                0 -> MapTab(active, selected, dao, perm, onNew = { pick = true }, onStop = {
                    ctx.startService(Intent(ctx, TrackingService::class.java).setAction("STOP"))
                })
                1 -> HistoryTab(trips, onShow = { selected = it; tab = 0 }, onGpx = { exporting = it; saver.launch(it.name.replace(" ", "_") + ".gpx") },
                    onDelete = { t -> scope.launch(Dispatchers.IO) { dao.delPts(t.id); dao.delTrip(t.id) } })
                else -> StatsTab(trips)
            }
        }
    }
}

@Composable
fun MapTab(active: Trip?, selected: Trip?, dao: TripDao, perm: Boolean, onNew: () -> Unit, onStop: () -> Unit) {
    val ctx = LocalContext.current
    val shown = active ?: selected
    var msg by remember { mutableStateOf("") }
    val map = remember {
        MapView(ctx).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            controller.setZoom(15.0)
            controller.setCenter(GeoPoint(45.75, 4.85))
        }
    }
    val me = remember { MyLocationNewOverlay(GpsMyLocationProvider(ctx), map) }
    val line = remember { Polyline() }
    DisposableEffect(Unit) {
        map.overlays.add(me); map.onResume()
        onDispose { map.overlays.remove(me); me.disableMyLocation(); map.onPause() }
    }
    LaunchedEffect(perm) { if (perm) { me.enableMyLocation(); me.enableFollowLocation() } }
    LaunchedEffect(shown?.id) {
        map.overlays.remove(line)
        if (shown != null) {
            line.outlinePaint.color = COLORS[MODES.indexOf(shown.mode).coerceAtLeast(0)].toInt()
            line.outlinePaint.strokeWidth = 14f
            map.overlays.add(line)
            dao.ptsFlow(shown.id).collect { pts ->
                line.setPoints(pts.map { GeoPoint(it.lat, it.lon) })
                if (shown.endMs != null && pts.isNotEmpty()) map.zoomToBoundingBox(line.bounds, true, 80)
                map.invalidate()
            }
        } else map.invalidate()
    }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(active?.id) { while (active != null) { now = System.currentTimeMillis(); delay(1000) } }

    Box(Modifier.fillMaxSize()) {
        AndroidView(factory = { map }, modifier = Modifier.fillMaxSize())
        Column(Modifier.align(Alignment.TopCenter).padding(12.dp)) {
            if (shown != null) Card { Column(Modifier.padding(12.dp)) {
                Text(shown.mode + " · " + shown.name, style = MaterialTheme.typography.titleMedium)
                Text("%.2f km · %s · moy %.1f km/h · max %.0f km/h".format(
                    shown.distance / 1000, dur((shown.endMs ?: now) - shown.startMs), avg(shown), shown.maxSpeed))
            } }
            if (msg.isNotEmpty()) Text(msg)
        }
        Column(Modifier.align(Alignment.BottomCenter).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { me.enableFollowLocation() }) { Text("Recentrer") }
                OutlinedButton(onClick = {
                    val z = map.zoomLevelDouble.toInt()
                    CacheManager(map).downloadAreaAsync(ctx, map.boundingBox, z, minOf(z + 3, 17), object : CacheManager.CacheManagerCallback {
                        override fun onTaskComplete() { msg = "Zone téléchargée" }
                        override fun onTaskFailed(errors: Int) { msg = "Échec du téléchargement ($errors erreurs)" }
                        override fun updateProgress(progress: Int, currentZoomLevel: Int, zoomMin: Int, zoomMax: Int) { msg = "Téléchargement… $progress" }
                        override fun downloadStarted() { msg = "Téléchargement…" }
                        override fun setPossibleTilesInArea(total: Int) {}
                    })
                }) { Text("Télécharger la zone") }
            }
            Spacer(Modifier.height(8.dp))
            if (active == null) Button(onClick = { if (perm) onNew() else msg = "Autorise la localisation pour enregistrer un trajet" }, modifier = Modifier.fillMaxWidth()) { Text("+ Nouveau trajet") }
            else Button(onClick = onStop, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("Terminer le trajet") }
        }
    }
}

@Composable
fun HistoryTab(trips: List<Trip>, onShow: (Trip) -> Unit, onGpx: (Trip) -> Unit, onDelete: (Trip) -> Unit) {
    if (trips.isEmpty()) { Box(Modifier.fillMaxSize(), Alignment.Center) { Text("Aucun trajet pour l'instant") }; return }
    LazyColumn(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(trips) { t ->
            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
                Text(t.mode + " · " + t.name, style = MaterialTheme.typography.titleMedium)
                Text("%.2f km · %s".format(t.distance / 1000, dur((t.endMs ?: System.currentTimeMillis()) - t.startMs)))
                Text("moy %.1f km/h · max %.0f km/h".format(avg(t), t.maxSpeed))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { onShow(t) }) { Text("Voir") }
                    TextButton(onClick = { onGpx(t) }) { Text("GPX") }
                    TextButton(onClick = { onDelete(t) }) { Text("Supprimer") }
                }
            } }
        }
    }
}

@Composable
fun StatsTab(trips: List<Trip>) {
    val done = trips.filter { it.endMs != null }
    val km = done.sumOf { it.distance } / 1000
    val ms = done.sumOf { it.endMs!! - it.startMs }
    val h = ms / 3.6e6
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Statistiques globales", style = MaterialTheme.typography.headlineSmall)
        Text("Trajets : ${done.size}")
        Text("Distance totale : %.1f km".format(km))
        Text("Temps total : ${dur(ms)}")
        Text("Vitesse moyenne : %.1f km/h".format(if (h > 0) km / h else 0.0))
        Text("Vitesse max : %.0f km/h".format(done.maxOfOrNull { it.maxSpeed } ?: 0.0))
        Spacer(Modifier.height(8.dp))
        Text("Par transport", style = MaterialTheme.typography.titleMedium)
        done.groupBy { it.mode }.forEach { (m, l) ->
            Text("$m : %.1f km · %s".format(l.sumOf { it.distance } / 1000, dur(l.sumOf { it.endMs!! - it.startMs })))
        }
    }
}
