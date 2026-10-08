package com.trackway

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant

const val STRAVA_REDIRECT = "trackway://strava"

/** Incrémenté quand l'état Strava change (connexion, déconnexion) pour rafraîchir l'onglet. */
var stravaTick by mutableIntStateOf(0)
private fun bump() { Handler(Looper.getMainLooper()).post { stravaTick++ } }

private class StravaHttp(val code: Int, msg: String) : RuntimeException("Strava $code : $msg")
private val syncLock = Mutex()

private fun sp(c: Context) = c.getSharedPreferences("strava", Context.MODE_PRIVATE)
fun stravaConnected(c: Context) = sp(c).getString("refresh", null) != null
fun stravaLogout(c: Context) { sp(c).edit().clear().apply(); bump() }

/** Ouvre la page d'autorisation Strava (app Strava si installée, sinon navigateur). */
fun stravaLogin(c: Context) {
    val uri = Uri.parse("https://www.strava.com/oauth/mobile/authorize").buildUpon()
        .appendQueryParameter("client_id", sp(c).getString("cid", "") ?: "")
        .appendQueryParameter("redirect_uri", STRAVA_REDIRECT)
        .appendQueryParameter("response_type", "code")
        .appendQueryParameter("approval_prompt", "auto")
        .appendQueryParameter("scope", "read,activity:read_all")
        .build()
    c.startActivity(Intent(Intent.ACTION_VIEW, uri))
}

/** Appelé par MainActivity quand Strava renvoie vers trackway://strava?code=... */
suspend fun stravaHandleRedirect(c: Context, data: Uri) = withContext(Dispatchers.IO) {
    val code = data.getQueryParameter("code")
    if (code == null) { toast(c, "Autorisation Strava refusée"); return@withContext }
    try {
        saveTokens(c, post("https://www.strava.com/oauth/token", mapOf(
            "client_id" to (sp(c).getString("cid", "") ?: ""),
            "client_secret" to (sp(c).getString("secret", "") ?: ""),
            "code" to code,
            "grant_type" to "authorization_code")))
        bump()
        toast(c, "Strava connecté")
        val n = stravaSync(c, Db.get(c).dao())
        toast(c, "$n trajet(s) Strava importé(s)")
    } catch (e: Exception) { toast(c, "Erreur Strava : ${e.message}") }
}

private fun saveTokens(c: Context, j: JSONObject) {
    val e = sp(c).edit()
        .putString("access", j.getString("access_token"))
        .putString("refresh", j.getString("refresh_token"))
        .putLong("expires", j.getLong("expires_at"))
    j.optJSONObject("athlete")?.let { e.putString("athlete", "${it.optString("firstname")} ${it.optString("lastname")}".trim()) }
    e.apply()
}

private fun token(c: Context): String {
    val p = sp(c)
    if (System.currentTimeMillis() / 1000 > p.getLong("expires", 0) - 60) {
        saveTokens(c, post("https://www.strava.com/oauth/token", mapOf(
            "client_id" to (p.getString("cid", "") ?: ""),
            "client_secret" to (p.getString("secret", "") ?: ""),
            "grant_type" to "refresh_token",
            "refresh_token" to (p.getString("refresh", "") ?: ""))))
    }
    return p.getString("access", "") ?: ""
}

fun stravaMode(type: String) = when (type) {
    "Walk", "Hike" -> "🚶 Marche"
    "Run", "TrailRun" -> "🏃 Course"
    "Ride", "GravelRide", "MountainBikeRide", "EBikeRide", "EMountainBikeRide" -> "🚲 Vélo"
    else -> "➕ Autre"
}

/** Importe dans TrackWay les activités Strava récentes (pas de doublon). Retourne le nombre importé. */
suspend fun stravaSync(c: Context, dao: TripDao): Int = syncLock.withLock {
    withContext(Dispatchers.IO) {
        val p = sp(c)
        val done = (p.getStringSet("done", emptySet()) ?: emptySet()).toMutableSet()
        val after = p.getLong("last", System.currentTimeMillis() / 1000 - 30L * 86400) // 1re synchro : 30 derniers jours
        val list = JSONArray(get(c, "https://www.strava.com/api/v3/athlete/activities?after=$after&per_page=50"))
        var n = 0
        var newest = after
        for (i in 0 until list.length()) {
            val a = list.getJSONObject(i)
            val id = a.getLong("id").toString()
            val startSec = Instant.parse(a.getString("start_date")).epochSecond
            newest = maxOf(newest, startSec)
            if (id in done) continue
            val s = try {
                JSONObject(get(c, "https://www.strava.com/api/v3/activities/$id/streams?keys=latlng,time,velocity_smooth&key_by_type=true"))
            } catch (e: StravaHttp) { if (e.code == 404) null else throw e }
            val ll = s?.optJSONObject("latlng")?.optJSONArray("data")
            if (ll != null && ll.length() >= 2) {
                val tm = s?.optJSONObject("time")?.optJSONArray("data")
                val v = s?.optJSONObject("velocity_smooth")?.optJSONArray("data")
                val t0 = startSec * 1000
                val tid = dao.insertTrip(Trip(
                    name = "Strava · " + a.optString("name", "Activité"),
                    mode = stravaMode(a.optString("sport_type", a.optString("type"))),
                    startMs = t0,
                    endMs = t0 + a.optLong("elapsed_time") * 1000,
                    distance = a.optDouble("distance", 0.0),
                    maxSpeed = a.optDouble("max_speed", 0.0) * 3.6))
                for (j in 0 until ll.length()) {
                    val q = ll.getJSONArray(j)
                    dao.insertPt(Pt(tripId = tid, lat = q.getDouble(0), lon = q.getDouble(1),
                        t = t0 + (tm?.optLong(j) ?: j.toLong()) * 1000,
                        speed = (v?.optDouble(j, 0.0) ?: 0.0).toFloat()))
                }
                n++
            }
            done += id
            p.edit().putStringSet("done", HashSet(done)).apply()
        }
        p.edit().putLong("last", newest + 1).apply()
        n
    }
}

// ───────── HTTP minimal (aucune dépendance en plus) ─────────
private fun get(c: Context, url: String): String {
    val h = URL(url).openConnection() as HttpURLConnection
    h.setRequestProperty("Authorization", "Bearer ${token(c)}")
    return h.body()
}

private fun post(url: String, form: Map<String, String>): JSONObject {
    val h = URL(url).openConnection() as HttpURLConnection
    h.requestMethod = "POST"
    h.doOutput = true
    h.outputStream.use { o -> o.write(form.entries.joinToString("&") { (k, v) -> "$k=${Uri.encode(v)}" }.toByteArray()) }
    return JSONObject(h.body())
}

private fun HttpURLConnection.body(): String {
    val ok = responseCode in 200..299
    val txt = (if (ok) inputStream else errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
    if (!ok) throw StravaHttp(responseCode, txt.take(120))
    return txt
}

// ───────── Onglet Strava ─────────
@Composable
fun StravaTab(dao: TripDao) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val tick = stravaTick
    val p = remember { sp(ctx) }
    var cid by remember { mutableStateOf(p.getString("cid", "") ?: "") }
    var secret by remember { mutableStateOf(p.getString("secret", "") ?: "") }
    var busy by remember { mutableStateOf(false) }
    val connected = remember(tick) { stravaConnected(ctx) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Strava", style = MaterialTheme.typography.headlineSmall)
        if (!connected) {
            Text("1. Sur strava.com/settings/api, crée une application (domaine de rappel : strava).\n" +
                "2. Colle ci-dessous son « ID client » et son « Code secret du client ».\n" +
                "3. Appuie sur Connecter et autorise l'accès.")
            OutlinedTextField(value = cid, onValueChange = { cid = it }, label = { Text("ID client") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = secret, onValueChange = { secret = it }, label = { Text("Code secret du client") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            Button(onClick = {
                p.edit().putString("cid", cid.trim()).putString("secret", secret.trim()).apply()
                stravaLogin(ctx)
            }, enabled = cid.isNotBlank() && secret.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Connecter mon compte Strava") }
        } else {
            Text("✅ Connecté : " + (p.getString("athlete", "") ?: ""))
            Button(onClick = {
                busy = true
                scope.launch {
                    try { toast(ctx, "${stravaSync(ctx, dao)} trajet(s) Strava importé(s)") }
                    catch (e: Exception) { toast(ctx, "Erreur Strava : ${e.message}") }
                    finally { busy = false }
                }
            }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(if (busy) "Synchronisation…" else "Synchroniser maintenant") }
            OutlinedButton(onClick = { stravaLogout(ctx) }, modifier = Modifier.fillMaxWidth()) { Text("Déconnecter Strava") }
            Text("Les activités Strava avec GPS (marche, course, vélo…) sont importées à l'ouverture de l'app et via le bouton ci-dessus. " +
                "Elles apparaissent dans « Trajets » avec le préfixe « Strava · ».")
        }
    }
}
