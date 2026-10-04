package com.trackway

import android.location.Location
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

typealias TP = Pair<Trip, List<Pt>>

private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

fun gpxAll(l: List<TP>): String {
    val f = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
    val sb = StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
    sb.append("<gpx version=\"1.1\" creator=\"TrackWay\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
    l.forEach { (t, p) ->
        sb.append("<trk><name>${esc(t.name)}</name><trkseg>\n")
        p.forEach { sb.append("<trkpt lat=\"${it.lat}\" lon=\"${it.lon}\"><time>${f.format(java.util.Date(it.t))}</time></trkpt>\n") }
        sb.append("</trkseg></trk>\n")
    }
    return sb.append("</gpx>\n").toString()
}

fun toJson(l: List<TP>): String {
    val arr = JSONArray()
    l.forEach { (t, p) ->
        val pts = JSONArray()
        p.forEach { pts.put(JSONArray().put(it.lat).put(it.lon).put(it.t).put(it.speed.toDouble())) }
        arr.put(JSONObject().put("name", t.name).put("mode", t.mode).put("startMs", t.startMs)
            .put("endMs", t.endMs ?: JSONObject.NULL).put("points", pts))
    }
    return JSONObject().put("app", "TrackWay").put("trips", arr).toString(1)
}

fun toCsv(l: List<TP>): String {
    val sb = StringBuilder("name,mode,startMs,endMs,lat,lon,t,speed\n")
    l.forEach { (t, p) ->
        p.forEach { sb.append("${t.name.replace(',', ' ')},${t.mode},${t.startMs},${t.endMs ?: ""},${it.lat},${it.lon},${it.t},${it.speed}\n") }
    }
    return sb.toString()
}

private fun parseJson(s: String): List<TP> {
    val arr = JSONObject(s).getJSONArray("trips")
    return (0 until arr.length()).map { i ->
        val o = arr.getJSONObject(i)
        val a = o.getJSONArray("points")
        val pts = (0 until a.length()).map { j ->
            val q = a.getJSONArray(j)
            Pt(tripId = 0, lat = q.getDouble(0), lon = q.getDouble(1), t = q.getLong(2), speed = q.getDouble(3).toFloat())
        }
        Trip(name = o.optString("name", "Import"), mode = o.optString("mode", "➕ Autre"), startMs = o.optLong("startMs", 0),
            endMs = if (o.isNull("endMs")) null else o.getLong("endMs")) to pts
    }
}

private fun parseCsv(s: String): List<TP> {
    val rows = s.lines().drop(1).filter { it.isNotBlank() }.map { it.split(",") }.filter { it.size >= 8 }
    return rows.groupBy { it[0] + "|" + it[2] }.map { (_, r) ->
        val f = r[0]
        Trip(name = f[0], mode = f[1], startMs = f[2].toLongOrNull() ?: 0, endMs = f[3].toLongOrNull()) to
            r.map { Pt(tripId = 0, lat = it[4].toDouble(), lon = it[5].toDouble(), t = it[6].toLong(), speed = it[7].toFloatOrNull() ?: 0f) }
    }
}

private fun parseGpx(s: String): List<TP> {
    val f = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
    val trkRe = Regex("<trk>(.*?)</trk>", RegexOption.DOT_MATCHES_ALL)
    val ptRe = Regex("<trkpt[^>]*lat=\"([-0-9.]+)\"[^>]*lon=\"([-0-9.]+)\"[^>]*>(.*?)</trkpt>", RegexOption.DOT_MATCHES_ALL)
    val timeRe = Regex("<time>([^<]+)</time>")
    return trkRe.findAll(s).map { m ->
        val body = m.groupValues[1]
        val name = Regex("<name>([^<]*)</name>").find(body)?.groupValues?.get(1) ?: "Import GPX"
        val pts = ptRe.findAll(body).map { p ->
            val ts = timeRe.find(p.groupValues[3])?.groupValues?.get(1)?.take(19)
                ?.let { runCatching { f.parse(it)!!.time }.getOrNull() } ?: 0L
            Pt(tripId = 0, lat = p.groupValues[1].toDouble(), lon = p.groupValues[2].toDouble(), t = ts, speed = 0f)
        }.toList()
        Trip(name = name, mode = "➕ Autre", startMs = pts.firstOrNull()?.t ?: 0, endMs = pts.lastOrNull()?.t) to pts
    }.toList()
}

private fun finalize(t: Trip, p: List<Pt>): Trip {
    var d = 0.0
    var mx = 0.0
    for (i in 1 until p.size) {
        val r = FloatArray(1)
        Location.distanceBetween(p[i - 1].lat, p[i - 1].lon, p[i].lat, p[i].lon, r)
        d += r[0]
        val dt = (p[i].t - p[i - 1].t) / 1000.0
        if (dt > 0) mx = maxOf(mx, r[0] / dt * 3.6)
    }
    val sp = p.maxOf { it.speed } * 3.6
    val st = if (t.startMs > 0) t.startMs else System.currentTimeMillis()
    val en = t.endMs?.takeIf { it > 0 } ?: p.last().t.takeIf { it > 0 } ?: st
    return t.copy(distance = d, maxSpeed = if (sp > 0) sp else minOf(mx, 400.0), startMs = st, endMs = en)
}

/** Importe GPX / JSON / CSV (détecté d'après le contenu). Retourne le nombre de trajets importés. */
suspend fun importText(txt: String, dao: TripDao): Int {
    val s = txt.trim()
    val groups = when {
        s.startsWith("{") -> parseJson(s)
        s.startsWith("<") -> parseGpx(s)
        else -> parseCsv(s)
    }.filter { it.second.isNotEmpty() }
    for ((t, pts) in groups) {
        val id = dao.insertTrip(finalize(t, pts).copy(id = 0))
        pts.forEach { dao.insertPt(it.copy(id = 0, tripId = id)) }
    }
    return groups.size
}
