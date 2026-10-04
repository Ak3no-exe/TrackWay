package com.trackway

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity
data class Trip(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val mode: String,
    val startMs: Long,
    val endMs: Long? = null,
    val distance: Double = 0.0,
    val maxSpeed: Double = 0.0
)

@Entity(indices = [Index("tripId")])
data class Pt(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tripId: Long,
    val lat: Double,
    val lon: Double,
    val t: Long,
    val speed: Float
)

@Dao
interface TripDao {
    @Insert suspend fun insertTrip(t: Trip): Long
    @Update suspend fun updateTrip(t: Trip)
    @Insert suspend fun insertPt(p: Pt)
    @Query("SELECT * FROM Trip ORDER BY startMs DESC") fun trips(): Flow<List<Trip>>
    @Query("SELECT * FROM Pt WHERE tripId = :id ORDER BY t") suspend fun pts(id: Long): List<Pt>
    @Query("SELECT * FROM Pt WHERE tripId = :id ORDER BY t") fun ptsFlow(id: Long): Flow<List<Pt>>
    @Query("SELECT * FROM Trip WHERE endMs IS NULL LIMIT 1") suspend fun active(): Trip?
    @Query("DELETE FROM Trip WHERE id = :id") suspend fun delTrip(id: Long)
    @Query("DELETE FROM Pt WHERE tripId = :id") suspend fun delPts(id: Long)
}

@Database(entities = [Trip::class, Pt::class], version = 1, exportSchema = false)
abstract class Db : RoomDatabase() {
    abstract fun dao(): TripDao
    companion object {
        @Volatile private var inst: Db? = null
        fun get(c: Context): Db = inst ?: synchronized(this) {
            inst ?: Room.databaseBuilder(c.applicationContext, Db::class.java, "trackway.db")
                .build().also { inst = it }
        }
    }
}

fun gpx(t: Trip, pts: List<Pt>): String {
    val f = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
        .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
    val sb = StringBuilder()
    sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
    sb.append("<gpx version=\"1.1\" creator=\"TrackWay\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
    sb.append("<trk><name>").append(t.name).append("</name><trkseg>\n")
    for (p in pts) {
        sb.append("<trkpt lat=\"${p.lat}\" lon=\"${p.lon}\"><time>${f.format(java.util.Date(p.t))}</time></trkpt>\n")
    }
    sb.append("</trkseg></trk></gpx>\n")
    return sb.toString()
}
