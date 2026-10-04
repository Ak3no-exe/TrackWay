package com.trackway

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.google.android.gms.location.*
import kotlinx.coroutines.*
import java.util.concurrent.Executors

class TrackingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Executors.newSingleThreadExecutor().asCoroutineDispatcher())
    private lateinit var client: FusedLocationProviderClient
    private var trip: Trip? = null
    private var last: Location? = null
    private var cur = 0f

    private val cb = object : LocationCallback() {
        override fun onLocationResult(r: LocationResult) {
            r.locations.forEach { l -> scope.launch { onLoc(l) } }
        }
    }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
        if (i?.action == "STOP") { finish(); return START_NOT_STICKY }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("trk", "Trajet en cours", NotificationManager.IMPORTANCE_LOW))
        ServiceCompat.startForeground(this, 1, notif("Démarrage…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        client = LocationServices.getFusedLocationProviderClient(this)
        scope.launch {
            val t = Db.get(this@TrackingService).dao().active()
            if (t == null) { stopSelf(); return@launch }
            trip = t
            withContext(Dispatchers.Main) {
                try {
                    val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2000)
                        .setMinUpdateDistanceMeters(3f).build()
                    client.requestLocationUpdates(req, cb, Looper.getMainLooper())
                } catch (e: SecurityException) { stopSelf() }
            }
        }
        return START_STICKY
    }

    private suspend fun onLoc(l: Location) {
        var t = trip ?: return
        if (l.hasAccuracy() && l.accuracy > 25f) return
        var d = 0.0
        last?.let {
            val dt = (l.time - it.time) / 1000.0
            if (dt <= 0) return
            val dd = it.distanceTo(l).toDouble()
            val vmax = if (t.mode.contains("Avion")) 350.0 else 100.0
            if (dd / dt > vmax) return
            if (dd < 3) return
            d = dd
        }
        last = l
        cur = if (l.hasSpeed()) l.speed else 0f
        t = t.copy(distance = t.distance + d, maxSpeed = maxOf(t.maxSpeed, cur * 3.6))
        trip = t
        val dao = Db.get(this).dao()
        dao.insertPt(Pt(tripId = t.id, lat = l.latitude, lon = l.longitude, t = l.time, speed = cur))
        dao.updateTrip(t)
        val s = (System.currentTimeMillis() - t.startMs) / 1000
        val txt = "%d:%02d:%02d · %.2f km · %.0f km/h".format(s / 3600, s % 3600 / 60, s % 60, t.distance / 1000, cur * 3.6)
        getSystemService(NotificationManager::class.java).notify(1, notif(txt))
    }

    private fun notif(text: String): Notification =
        NotificationCompat.Builder(this, "trk")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("🚗 Trajet en cours")
            .setContentText(text)
            .setOngoing(true)
            .build()

    private fun finish() {
        scope.launch {
            val dao = Db.get(this@TrackingService).dao()
            val t = trip ?: dao.active()
            if (t != null) dao.updateTrip(t.copy(endMs = System.currentTimeMillis()))
            withContext(Dispatchers.Main) {
                if (::client.isInitialized) client.removeLocationUpdates(cb)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }
}
