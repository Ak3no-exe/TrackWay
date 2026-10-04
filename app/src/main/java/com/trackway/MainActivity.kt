package com.trackway

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.cachemanager.CacheManager
import org.osmdroid.tileprovider.modules.SqlTileWriter
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import java.util.Calendar

val MODES = listOf("🚗 Voiture", "🚆 Train", "✈️ Avion", "🚶 Marche", "🚲 Vélo", "🏍️ Moto",
    "🚌 Bus", "🚇 Métro", "🛴 Trottinette", "⛵ Bateau", "➕ Autre")
val COLORS = listOf(0xFF2196F3, 0xFF9C27B0, 0xFFFF9800, 0xFF4CAF50, 0xFF00BCD4, 0xFFF44336,
    0xFFFFC107, 0xFF3F51B5, 0xFF8BC34A, 0xFF009688, 0xFF795548)
val ACCENTS = listOf(0xFF2196F3, 0xFF4CAF50, 0xFFF44336, 0xFFFF9800, 0xFF9C27B0, 0xFF009688)

fun hasLoc(c: Context) = ContextCompat.checkSelfPermission(c, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
fun dur(ms: Long): String { val s = ms / 1000; return "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60) }
fun tripMs(t: Trip) = (t.endMs ?: System.currentTimeMillis()) - t.startMs
fun avg(t: Trip): Double { val h = tripMs(t) / 3.6e6; return if (h > 0) t.distance / 1000 / h else 0.0 }
fun hm(ms: Long): String = java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale.FRANCE).format(java.util.Date(ms))
fun dU(m: Boolean) = if (m) "mi" else "km"
fun sU(m: Boolean) = if (m) "mph" else "km/h"
fun cv(km: Double, m: Boolean) = if (m) km * 0.621371 else km
fun toast(c: Context, s: String) { Handler(Looper.getMainLooper()).post { Toast.makeText(c, s, Toast.LENGTH_LONG).show() } }

/** Début de la période : 0 jour, 1 semaine, 2 mois, 3 année, 4 tout. */
fun since(p: Int): Long {
    if (p == 4) return 0
    val c = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }
    when (p) {
        1 -> c.set(Calendar.DAY_OF_WEEK, c.firstDayOfWeek)
        2 -> c.set(Calendar.DAY_OF_MONTH, 1)
        3 -> c.set(Calendar.DAY_OF_YEAR, 1)
    }
    return c.timeInMillis
}

class MainActivity : ComponentActivity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Configuration.getInstance().apply {
            load(this@MainActivity, getSharedPreferences("osm", MODE_PRIVATE))
            userAgentValue = packageName
        }
        setContent { App() }
    }
}

@Composable
fun App() {
    val ctx = LocalContext.current
    val dao = remember { Db.get(ctx).dao() }
    val scope = rememberCoroutineScope()
    val sp = remember { ctx.getSharedPreferences("tw", Context.MODE_PRIVATE) }
    var theme by remember { mutableIntStateOf(sp.getInt("theme", 0)) }
    var accent by remember { mutableIntStateOf(sp.getInt("accent", 0)) }
    var miles by remember { mutableStateOf(sp.getBoolean("miles", false)) }
    val dark = when (theme) { 1 -> false; 2 -> true; else -> isSystemInDarkTheme() }
    val ac = Color(ACCENTS[accent])

    val trips by dao.trips().collectAsState(emptyList())
    var tab by remember { mutableIntStateOf(0) }
    var perm by remember { mutableStateOf(hasLoc(ctx)) }
    var pick by remember { mutableStateOf(false) }
    var confirmDel by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Trip?>(null) }
    var summary by remember { mutableStateOf<Trip?>(null) }
    var prevActive by remember { mutableStateOf<Trip?>(null) }
    var pending by remember { mutableStateOf<(suspend () -> String)?>(null) }
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
    // Résumé affiché à la fin d'un trajet
    LaunchedEffect(trips) {
        val pa = prevActive
        if (active != null) prevActive = active
        else if (pa != null) { summary = trips.firstOrNull { it.id == pa.id }; prevActive = null }
    }
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val p = pending
        if (uri != null && p != null) scope.launch(Dispatchers.IO) {
            val s = p()
            ctx.contentResolver.openOutputStream(uri)?.use { it.write(s.toByteArray()) }
            toast(ctx, "Export terminé")
        }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch(Dispatchers.IO) {
            val txt = ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.readText() ?: ""
            val n = try { importText(txt, dao) } catch (e: Exception) { -1 }
            toast(ctx, if (n >= 0) "$n trajet(s) importé(s)" else "Fichier invalide")
        }
    }
    fun export(fmt: String, list: List<Trip>, name: String) {
        pending = {
            val data = list.map { it to dao.pts(it.id) }
            when (fmt) { "gpx" -> gpxAll(data); "json" -> toJson(data); else -> toCsv(data) }
        }
        saver.launch("$name.$fmt")
    }

    MaterialTheme(colorScheme = if (dark) darkColorScheme(primary = ac) else lightColorScheme(primary = ac)) {
        Surface(Modifier.fillMaxSize()) {
            if (pick) AlertDialog(
                onDismissRequest = { pick = false },
                title = { Text("Moyen de transport") },
                text = {
                    LazyColumn { items(MODES) { m ->
                        TextButton(onClick = {
                            pick = false; selected = null
                            scope.launch(Dispatchers.IO) {
                                dao.insertTrip(Trip(name = "Trajet " + hm(System.currentTimeMillis()), mode = m, startMs = System.currentTimeMillis()))
                            }
                        }, modifier = Modifier.fillMaxWidth()) { Text(m) }
                    } }
                },
                confirmButton = {}, dismissButton = { TextButton(onClick = { pick = false }) { Text("Annuler") } }
            )
            summary?.let { t ->
                AlertDialog(
                    onDismissRequest = { summary = null },
                    title = { Text("Résumé du trajet") },
                    text = { Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("🚗 Transport : ${t.mode}")
                        Text("📏 Distance : %.2f %s".format(cv(t.distance / 1000, miles), dU(miles)))
                        Text("⏱️ Durée : ${dur(tripMs(t))}")
                        Text("⚡ Vitesse moyenne : %.1f %s".format(cv(avg(t), miles), sU(miles)))
                        Text("🚀 Vitesse max : %.0f %s".format(cv(t.maxSpeed, miles), sU(miles)))
                        Text("🕐 Départ : ${hm(t.startMs)}")
                        Text("🏁 Arrivée : ${hm(t.endMs ?: t.startMs)}")
                        Text("Trajet sauvegardé automatiquement.")
                    } },
                    confirmButton = { TextButton(onClick = { selected = t; tab = 0; summary = null }) { Text("🗺️ Voir le parcours") } },
                    dismissButton = { TextButton(onClick = { summary = null }) { Text("Fermer") } }
                )
            }
            if (confirmDel) AlertDialog(
                onDismissRequest = { confirmDel = false },
                title = { Text("Supprimer tous les trajets ?") },
                text = { Text("Les trajets terminés et leurs points GPS seront effacés de l'appareil. Cette action est définitive.") },
                confirmButton = { TextButton(onClick = {
                    confirmDel = false; selected = null
                    scope.launch(Dispatchers.IO) { dao.delFinishedPts(); dao.delFinishedTrips() }
                }) { Text("Supprimer") } },
                dismissButton = { TextButton(onClick = { confirmDel = false }) { Text("Annuler") } }
            )
            Scaffold(bottomBar = {
                NavigationBar {
                    listOf("🗺️" to "Carte", "📋" to "Trajets", "📊" to "Stats", "👤" to "Profil").forEachIndexed { i, (e, l) ->
                        NavigationBarItem(selected = tab == i, onClick = { tab = i }, icon = { Text(e) }, label = { Text(l, maxLines = 1) })
                    }
                }
            }) { pad ->
                Box(Modifier.padding(pad).fillMaxSize()) {
                    when (tab) {
                        0 -> MapTab(active, selected, dao, perm, miles, onNew = { pick = true }, onStop = {
                            ctx.startService(Intent(ctx, TrackingService::class.java).setAction("STOP"))
                        })
                        1 -> HistoryTab(trips, dao, miles,
                            onShow = { selected = it; tab = 0 },
                            onExport = { t, f -> export(f, listOf(t), t.name.replace(Regex("[^A-Za-z0-9]+"), "_")) },
                            onRename = { t, n -> scope.launch(Dispatchers.IO) { dao.updateTrip(t.copy(name = n)) } },
                            onDelete = { t -> scope.launch(Dispatchers.IO) { dao.delPts(t.id); dao.delTrip(t.id) } })
                        2 -> StatsTab(trips, miles)
                        else -> ProfileTab(theme, accent, miles,
                            setTheme = { theme = it; sp.edit().putInt("theme", it).apply() },
                            setAccent = { accent = it; sp.edit().putInt("accent", it).apply() },
                            setMiles = { miles = it; sp.edit().putBoolean("miles", it).apply() },
                            onExport = { f -> export(f, trips.filter { it.endMs != null }, "trackway_trajets") },
                            onImport = { importer.launch(arrayOf("*/*")) },
                            onDeleteAll = { confirmDel = true },
                            onClearMaps = { scope.launch(Dispatchers.IO) { SqlTileWriter().purgeCache(); toast(ctx, "Cartes hors ligne supprimées") } })
                    }
                }
            }
        }
    }
}

@Composable
fun MapTab(active: Trip?, selected: Trip?, dao: TripDao, perm: Boolean, miles: Boolean, onNew: () -> Unit, onStop: () -> Unit) {
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
                if (shown.endMs != null && pts.size > 1) map.zoomToBoundingBox(line.bounds, true, 80)
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
                Text("%.2f %s · %s".format(cv(shown.distance / 1000, miles), dU(miles), dur((shown.endMs ?: now) - shown.startMs)))
                Text("moy %.1f · max %.0f %s".format(cv(avg(shown), miles), cv(shown.maxSpeed, miles), sU(miles)))
            } }
            if (msg.isNotEmpty()) Text(msg)
        }
        Column(Modifier.align(Alignment.BottomCenter).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { me.enableFollowLocation() }, modifier = Modifier.weight(1f)) { Text("Recentrer") }
                OutlinedButton(onClick = {
                    val z = map.zoomLevelDouble.toInt()
                    CacheManager(map).downloadAreaAsync(ctx, map.boundingBox, z, minOf(z + 3, 17), object : CacheManager.CacheManagerCallback {
                        override fun onTaskComplete() { msg = "Zone téléchargée" }
                        override fun onTaskFailed(errors: Int) { msg = "Échec du téléchargement ($errors erreurs)" }
                        override fun updateProgress(progress: Int, currentZoomLevel: Int, zoomMin: Int, zoomMax: Int) { msg = "Téléchargement… $progress" }
                        override fun downloadStarted() { msg = "Téléchargement…" }
                        override fun setPossibleTilesInArea(total: Int) {}
                    })
                }, modifier = Modifier.weight(1f)) { Text("Télécharger la zone", maxLines = 1) }
            }
            Spacer(Modifier.height(8.dp))
            if (active == null) Button(onClick = { if (perm) onNew() else msg = "Autorise la localisation pour enregistrer un trajet" }, modifier = Modifier.fillMaxWidth()) { Text("+ Nouveau trajet") }
            else Button(onClick = onStop, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("Terminer le trajet") }
        }
    }
}

@Composable
fun Thumb(dao: TripDao, t: Trip) {
    val pts by produceState(emptyList<Pt>(), t.id, t.distance) { value = dao.pts(t.id) }
    val col = Color(COLORS[MODES.indexOf(t.mode).coerceAtLeast(0)])
    Canvas(Modifier.fillMaxWidth().height(64.dp)) {
        if (pts.size < 2) return@Canvas
        val minLa = pts.minOf { it.lat }; val maxLa = pts.maxOf { it.lat }
        val minLo = pts.minOf { it.lon }; val maxLo = pts.maxOf { it.lon }
        val k = Math.cos(Math.toRadians((minLa + maxLa) / 2))
        val w = ((maxLo - minLo) * k).coerceAtLeast(1e-7)
        val h = (maxLa - minLa).coerceAtLeast(1e-7)
        val s = minOf(size.width / w, size.height / h)
        val ox = (size.width - w * s) / 2
        val oy = (size.height - h * s) / 2
        val path = Path()
        pts.forEachIndexed { i, p ->
            val x = (ox + (p.lon - minLo) * k * s).toFloat()
            val y = (oy + (maxLa - p.lat) * s).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, col, style = Stroke(width = 6f))
    }
}

@Composable
fun HistoryTab(trips: List<Trip>, dao: TripDao, miles: Boolean, onShow: (Trip) -> Unit, onExport: (Trip, String) -> Unit,
               onRename: (Trip, String) -> Unit, onDelete: (Trip) -> Unit) {
    var q by remember { mutableStateOf("") }
    var mf by remember { mutableIntStateOf(-1) }
    var sort by remember { mutableIntStateOf(0) }
    var ren by remember { mutableStateOf<Trip?>(null) }
    var nn by remember { mutableStateOf("") }
    val list = trips.filter { (mf < 0 || it.mode == MODES[mf]) && it.name.contains(q, true) }.let { l ->
        when (sort) { 1 -> l.sortedByDescending { it.distance }; 2 -> l.sortedByDescending { tripMs(it) }; else -> l.sortedByDescending { it.startMs } }
    }
    ren?.let { t ->
        AlertDialog(onDismissRequest = { ren = null }, title = { Text("Renommer") },
            text = { OutlinedTextField(value = nn, onValueChange = { nn = it }, singleLine = true) },
            confirmButton = { TextButton(onClick = { if (nn.isNotBlank()) onRename(t, nn.trim()); ren = null }) { Text("OK") } },
            dismissButton = { TextButton(onClick = { ren = null }) { Text("Annuler") } })
    }
    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(value = q, onValueChange = { q = it }, label = { Text("Rechercher") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { mf = if (mf >= MODES.size - 1) -1 else mf + 1 }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(4.dp)) {
                Text("Filtre : " + if (mf < 0) "Tous" else MODES[mf], maxLines = 1)
            }
            OutlinedButton(onClick = { sort = (sort + 1) % 3 }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(4.dp)) {
                Text("Tri : " + listOf("Date", "Distance", "Durée")[sort], maxLines = 1)
            }
        }
        if (list.isEmpty()) Box(Modifier.fillMaxSize(), Alignment.Center) { Text("Aucun trajet") }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(list, key = { it.id }) { t ->
                Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
                    Text(t.mode + " · " + t.name, style = MaterialTheme.typography.titleMedium)
                    Text(hm(t.startMs))
                    Thumb(dao, t)
                    Text("%.2f %s · %s".format(cv(t.distance / 1000, miles), dU(miles), dur(tripMs(t))))
                    Text("moy %.1f · max %.0f %s".format(cv(avg(t), miles), cv(t.maxSpeed, miles), sU(miles)))
                    Row(Modifier.fillMaxWidth()) {
                        TextButton(onClick = { onShow(t) }, Modifier.weight(1f), contentPadding = PaddingValues(2.dp)) { Text("Voir") }
                        TextButton(onClick = { nn = t.name; ren = t }, Modifier.weight(1f), enabled = t.endMs != null, contentPadding = PaddingValues(2.dp)) { Text("Renommer", maxLines = 1) }
                        TextButton(onClick = { onDelete(t) }, Modifier.weight(1f), enabled = t.endMs != null, contentPadding = PaddingValues(2.dp)) { Text("Supprimer", maxLines = 1) }
                    }
                    Row(Modifier.fillMaxWidth()) {
                        listOf("gpx", "json", "csv").forEach { f ->
                            TextButton(onClick = { onExport(t, f) }, Modifier.weight(1f), enabled = t.endMs != null, contentPadding = PaddingValues(2.dp)) { Text(f.uppercase()) }
                        }
                    }
                } }
            }
        }
    }
}

@Composable
fun BarChart(title: String, items: List<Pair<String, Double>>, fmt: (Double) -> String) {
    Text(title, style = MaterialTheme.typography.titleMedium)
    if (items.isEmpty()) { Text("Pas de données"); return }
    val mx = items.maxOf { it.second }.coerceAtLeast(1e-9)
    Row(Modifier.fillMaxWidth().height(130.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        items.forEach { (l, v) ->
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                Text(fmt(v), style = MaterialTheme.typography.labelSmall, maxLines = 1)
                Box(Modifier.fillMaxWidth().height((80 * (v / mx)).dp.coerceAtLeast(2.dp)).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp)))
                Text(l)
            }
        }
    }
}

@Composable
fun StatsTab(trips: List<Trip>, miles: Boolean) {
    var p by remember { mutableIntStateOf(4) }
    val from = since(p)
    val done = trips.filter { it.endMs != null && it.startMs >= from }
    val km = done.sumOf { it.distance } / 1000
    val ms = done.sumOf { tripMs(it) }
    val h = ms / 3.6e6
    val byMode = done.groupBy { it.mode.substringBefore(" ") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Statistiques", style = MaterialTheme.typography.headlineSmall)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf("Jour", "Semaine", "Mois", "Année", "Tout").forEachIndexed { i, l ->
                if (p == i) Button(onClick = { p = i }, Modifier.weight(1f), contentPadding = PaddingValues(0.dp)) { Text(l, maxLines = 1, style = MaterialTheme.typography.labelSmall) }
                else OutlinedButton(onClick = { p = i }, Modifier.weight(1f), contentPadding = PaddingValues(0.dp)) { Text(l, maxLines = 1, style = MaterialTheme.typography.labelSmall) }
            }
        }
        Text("%.1f %s".format(cv(km, miles), dU(miles)), style = MaterialTheme.typography.displayMedium)
        Text("Trajets : ${done.size} · Temps total : ${dur(ms)}")
        Text("Vitesse moyenne : %.1f %s · max : %.0f %s".format(
            cv(if (h > 0) km / h else 0.0, miles), sU(miles), cv(done.maxOfOrNull { it.maxSpeed } ?: 0.0, miles), sU(miles)))
        Spacer(Modifier.height(8.dp))
        BarChart("Distance par transport (${dU(miles)})", byMode.map { (m, l) -> m to cv(l.sumOf { it.distance } / 1000, miles) }) { "%.0f".format(it) }
        Spacer(Modifier.height(8.dp))
        BarChart("Temps par transport (min)", byMode.map { (m, l) -> m to l.sumOf { tripMs(it) } / 60000.0 }) { "%.0f".format(it) }
    }
}

@Composable
fun ProfileTab(theme: Int, accent: Int, miles: Boolean, setTheme: (Int) -> Unit, setAccent: (Int) -> Unit, setMiles: (Boolean) -> Unit,
               onExport: (String) -> Unit, onImport: () -> Unit, onDeleteAll: () -> Unit, onClearMaps: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Profil & paramètres", style = MaterialTheme.typography.headlineSmall)
        Text("Thème", style = MaterialTheme.typography.titleMedium)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("Auto", "Clair", "Sombre").forEachIndexed { i, l ->
                if (theme == i) Button(onClick = { setTheme(i) }, Modifier.weight(1f)) { Text(l) }
                else OutlinedButton(onClick = { setTheme(i) }, Modifier.weight(1f)) { Text(l) }
            }
        }
        Text("Couleur principale", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ACCENTS.forEachIndexed { i, c ->
                Box(Modifier.size(44.dp).background(Color(c), CircleShape)
                    .then(if (accent == i) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier)
                    .clickable { setAccent(i) })
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Miles / mph (sinon km / km/h)", Modifier.weight(1f))
            Switch(checked = miles, onCheckedChange = setMiles)
        }
        HorizontalDivider()
        Text("Export des trajets terminés", style = MaterialTheme.typography.titleMedium)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("gpx", "json", "csv").forEach { f -> OutlinedButton(onClick = { onExport(f) }, Modifier.weight(1f)) { Text(f.uppercase()) } }
        }
        Button(onClick = onImport, Modifier.fillMaxWidth()) { Text("Importer un fichier GPX / JSON / CSV") }
        HorizontalDivider()
        Text("Données et cartes", style = MaterialTheme.typography.titleMedium)
        OutlinedButton(onClick = onClearMaps, Modifier.fillMaxWidth()) { Text("Vider les cartes hors ligne") }
        Button(onClick = onDeleteAll, Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("Supprimer mes trajets") }
        HorizontalDivider()
        Text("TrackWay 1.0", style = MaterialTheme.typography.titleMedium)
        Text("Toutes les données (trajets, points GPS, cartes) restent sur cet appareil. Aucun serveur n'est utilisé, sauf pour télécharger les tuiles de carte.")
    }
}
