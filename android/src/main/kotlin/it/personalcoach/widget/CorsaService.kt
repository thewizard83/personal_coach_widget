package it.personalcoach.widget

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

// GPS della corsa in un servizio in primo piano (tipo "location"), così le
// posizioni arrivano anche a schermo spento e telefono in tasca. Distanza e
// velocità si calcolano qui; Dart le legge con gpsStato.
class CorsaService : Service(), LocationListener {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == FERMA) {
            stopSelf()
            return START_NOT_STICKY
        }
        val n = notifica(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(ID, n)
        }
        avviaGps()
        return START_NOT_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun avviaGps() {
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this, Looper.getMainLooper())
        } catch (_: Exception) {
            Gps.errore = true
        }
    }

    override fun onLocationChanged(l: Location) {
        Gps.aggiungi(l)
        if (System.currentTimeMillis() - ultimaNotifica > 10_000) {
            ultimaNotifica = System.currentTimeMillis()
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(ID, notifica(this))
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}

    override fun onDestroy() {
        (getSystemService(Context.LOCATION_SERVICE) as LocationManager).removeUpdates(this)
        Gps.attivo = false
        super.onDestroy()
    }

    private var ultimaNotifica = 0L

    companion object {
        private const val ID = 7001
        private const val CANALE = "corsa"
        private const val FERMA = "it.personalcoach.widget.FERMA_CORSA"

        fun avvia(c: Context) {
            Gps.azzera()
            Gps.attivo = true
            ContextCompat.startForegroundService(c, Intent(c, CorsaService::class.java))
        }

        fun ferma(c: Context) {
            Gps.attivo = false
            c.stopService(Intent(c, CorsaService::class.java))
        }

        private fun notifica(c: Context): Notification {
            val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                nm.createNotificationChannel(NotificationChannel(CANALE, "Corsa in corso", NotificationManager.IMPORTANCE_LOW))
            }
            val apri = c.packageManager.getLaunchIntentForPackage(c.packageName)?.let {
                PendingIntent.getActivity(c, ID, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            }
            val km = String.format(java.util.Locale.ITALY, "%.2f", Gps.metri / 1000.0)
            return NotificationCompat.Builder(c, CANALE)
                .setSmallIcon(R.drawable.coach_notifica)
                .setColor(0xFF2EAD6B.toInt())
                .setContentTitle("Corsa in corso")
                .setContentText(if (Gps.fix) "$km km · GPS attivo" else "In attesa del segnale GPS…")
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .apply { if (apri != null) setContentIntent(apri) }
                .build()
        }
    }
}

/** Stato della traccia: distanza, velocità e qualità del segnale. */
object Gps {
    @Volatile var attivo = false
    @Volatile var inPausa = false
    @Volatile var fix = false
    @Volatile var errore = false
    @Volatile var metri = 0.0
    @Volatile var velocita = 0.0 // m/s, media degli ultimi ~15"
    @Volatile var precisione = 0f
    @Volatile var secondiInMovimento = 0.0
    @Volatile private var ultimoMovimento = 0L
    private var ultima: Location? = null
    private val recenti = ArrayDeque<Pair<Long, Double>>() // (ms, metri progressivi)

    fun azzera() {
        fix = false; errore = false; inPausa = false
        metri = 0.0; velocita = 0.0; precisione = 0f; secondiInMovimento = 0.0
        ultima = null; recenti.clear(); ultimoMovimento = 0L
    }

    @Synchronized
    fun aggiungi(l: Location) {
        precisione = l.accuracy
        if (l.accuracy > 25f) return // punto troppo impreciso
        fix = true
        val prec = ultima
        ultima = l
        if (prec == null || inPausa) return
        val d = prec.distanceTo(l).toDouble()
        val dt = (l.time - prec.time) / 1000.0
        if (dt <= 0) return
        // scarta salti impossibili (> 8 m/s ≈ 2:05/km) e il rumore da fermo
        if (d / dt > 8.0 || d < 1.5) return
        metri += d
        secondiInMovimento += dt
        ultimoMovimento = System.currentTimeMillis()
        recenti.addLast(l.time to metri)
        while (recenti.size > 2 && l.time - recenti.first().first > 15_000) recenti.removeFirst()
        val (t0, m0) = recenti.first()
        val sec = (l.time - t0) / 1000.0
        velocita = if (sec >= 3) (metri - m0) / sec else d / dt
    }

    fun stato(): Map<String, Any> = mapOf(
        "attivo" to attivo,
        "fix" to fix,
        "errore" to errore,
        "metri" to metri,
        // fermo da più di 6": velocità zero
        "velocita" to if (System.currentTimeMillis() - ultimoMovimento < 6_000) velocita else 0.0,
        "precisione" to precisione.toDouble(),
        "secondiInMovimento" to secondiInMovimento,
    )
}
