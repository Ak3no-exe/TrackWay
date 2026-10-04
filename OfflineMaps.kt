package com.trackway

import org.osmdroid.tileprovider.modules.SqlTileWriter
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.MapTileIndex
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan

private fun tx(lon: Double, z: Int): Int = floor((lon + 180.0) / 360.0 * (1 shl z)).toInt()

private fun ty(lat: Double, z: Int): Int {
    val r = Math.toRadians(lat.coerceIn(-85.0, 85.0))
    return floor((1.0 - ln(tan(r) + 1.0 / cos(r)) / PI) / 2.0 * (1 shl z)).toInt()
}

fun countTiles(bb: BoundingBox, zMin: Int, zMax: Int): Int {
    var n = 0
    for (z in zMin..zMax) {
        n += (tx(bb.lonEast, z) - tx(bb.lonWest, z) + 1) * (ty(bb.latSouth, z) - ty(bb.latNorth, z) + 1)
    }
    return n
}

/** Télécharge les tuiles de la zone dans le cache hors ligne d'osmdroid. Retourne (réussies, échecs). */
fun downloadArea(bb: BoundingBox, zMin: Int, zMax: Int, userAgent: String, progress: (Int, Int) -> Unit): Pair<Int, Int> {
    val src = TileSourceFactory.MAPNIK
    val writer = SqlTileWriter()
    val total = countTiles(bb, zMin, zMax)
    var ok = 0
    var ko = 0
    for (z in zMin..zMax) {
        val x0 = tx(bb.lonWest, z); val x1 = tx(bb.lonEast, z)
        val y0 = ty(bb.latNorth, z); val y1 = ty(bb.latSouth, z)
        for (x in x0..x1) for (y in y0..y1) {
            try {
                val idx = MapTileIndex.getTileIndex(z, x, y)
                val c = URL(src.getTileURLString(idx)).openConnection() as HttpURLConnection
                c.setRequestProperty("User-Agent", userAgent)
                c.connectTimeout = 10000
                c.readTimeout = 10000
                if (c.responseCode == 200) {
                    val saved = c.inputStream.use { writer.saveFile(src, idx, it, System.currentTimeMillis() + 365L * 24 * 3600 * 1000) }
                    if (saved) ok++ else ko++
                } else ko++
                c.disconnect()
            } catch (e: Exception) { ko++ }
            progress(ok + ko, total)
            Thread.sleep(50)
        }
    }
    return ok to ko
}
