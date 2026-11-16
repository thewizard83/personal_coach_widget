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
        private const val CANALE = "corsa_diretta"
        private const val FERMA = "it.personalcoach.widget.FERMA_CORSA"

        fun avvia(c: Context) {
            Gps.azzera()
            Gps.attivo = true
            ContextCompat.startForegroundService(c, Intent(c, CorsaService::class.java))
        }

        fun ferma(c: Context) {
            Gps.attivo = false
            Seduta.azzera()
            c.stopService(Intent(c, CorsaService::class.java))
        }

        /** Dati della seduta dal timer (gpsNotifica): la notifica li mostra con il GPS. */
        fun aggiornaSeduta(c: Context, m: Map<*, *>) {
            Seduta.titolo = m["titolo"] as? String ?: ""
            Seduta.pos = (m["pos"] as? Number)?.toInt() ?: 0
            Seduta.n = (m["n"] as? Number)?.toInt() ?: 0
            Seduta.nome = m["nome"] as? String ?: ""
            Seduta.label = m["label"] as? String ?: ""
            Seduta.fine = (m["fine"] as? Number)?.toLong() ?: 0L
            Seduta.inPausa = m["inPausa"] as? Boolean ?: false
            Seduta.finita = m["finita"] as? Boolean ?: false
            if (Gps.attivo) {
                val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.notify(ID, notifica(c))
            }
        }

        private fun passo(secKm: Double): String {
            val s = Math.round(secKm).toInt()
            return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
        }

        // Notifica della corsa: blocco, distanza e passo, anche dalla schermata
        // di blocco (pubblica); su Android 16 chiede di essere un
        // "aggiornamento in tempo reale" (in evidenza e con il chip in alto).
        private fun notifica(c: Context): Notification {
            val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // importanza normale ma senza suono né vibrazione: le notifiche
                // "silenziose" il Pixel non le mostra sulla schermata di blocco
                nm.deleteNotificationChannel("corsa")
                nm.createNotificationChannel(
                    NotificationChannel(CANALE, "Corsa in corso", NotificationManager.IMPORTANCE_DEFAULT).apply {
                        lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                        setSound(null, null)
                        enableVibration(false)
                        setShowBadge(false)
                    }
                )
            }
            val apri = c.packageManager.getLaunchIntentForPackage(c.packageName)?.let {
                PendingIntent.getActivity(c, ID, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            }
            val st = Gps.stato()
            val metri = Gps.metri
            val km = String.format(java.util.Locale.ITALY, "%.2f", metri / 1000.0)
            val v = (st["velocita"] as? Double) ?: 0.0
            val ora = if (v > 0.5) passo(1000.0 / v) else "–"
            val media = if (metri >= 50 && Gps.secondiInMovimento > 0) passo(Gps.secondiInMovimento * 1000.0 / metri) else null
            val dati = when {
                !Gps.fix -> "In attesa del segnale GPS…"
                else -> "$km km · ora $ora/km"
            }
            val conSeduta = Seduta.n > 0
            val titolo = when {
                !conSeduta -> "Corsa in corso"
                Seduta.finita -> "Seduta finita"
                else -> "${Seduta.pos + 1}/${Seduta.n} · ${Seduta.nome}"
            }
            val testo = if (Seduta.inPausa && !Seduta.finita) "In pausa · $dati" else dati
            val lungo = buildString {
                if (conSeduta && Seduta.label.isNotEmpty() && !Seduta.finita) append(Seduta.label).append('\n')
                append(testo)
                if (Gps.fix && media != null) append(" · media $media/km")
                if (conSeduta && Seduta.titolo.isNotEmpty()) append('\n').append(Seduta.titolo)
            }
            val b = NotificationCompat.Builder(c, CANALE)
                .setSmallIcon(R.drawable.coach_notifica)
                .setColor(0xFF2EAD6B.toInt())
                .setContentTitle(titolo)
                .setContentText(testo)
                .setStyle(NotificationCompat.BigTextStyle().bigText(lungo))
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setCategory(NotificationCompat.CATEGORY_WORKOUT)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .apply { if (apri != null) setContentIntent(apri) }
            // conto alla rovescia del blocco, che l'orologio di sistema fa scorrere da solo
            if (conSeduta && !Seduta.inPausa && !Seduta.finita && Seduta.fine > System.currentTimeMillis()) {
                b.setWhen(Seduta.fine).setShowWhen(true).setUsesChronometer(true).setChronometerCountDown(true)
            } else {
                b.setShowWhen(false)
            }
            b.addExtras(android.os.Bundle().apply {
                putBoolean("android.requestPromotedOngoing", true)
                putString("android.shortCriticalText", if (Gps.fix) "$km km" else "Corsa")
            })
            return b.build()
        }
    }
}

/** Seduta in corso, dal timer guidato (per la notifica). */
object Seduta {
    @Volatile var titolo = ""
    @Volatile var pos = 0
    @Volatile var n = 0
    @Volatile var nome = ""
    @Volatile var label = ""
    @Volatile var fine = 0L
    @Volatile var inPausa = false
    @Volatile var finita = false

    fun azzera() { titolo = ""; pos = 0; n = 0; nome = ""; label = ""; fine = 0L; inPausa = false; finita = false }
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
    // tracciato per la mappa: un punto ogni ~5 m (max 20000 ≈ 100 km)
    private val punti = ArrayList<DoubleArray>()

    fun azzera() {
        fix = false; errore = false; inPausa = false
        metri = 0.0; velocita = 0.0; precisione = 0f; secondiInMovimento = 0.0
        ultima = null; recenti.clear(); ultimoMovimento = 0L
        synchronized(punti) { punti.clear() }
    }

    @Synchronized
    fun aggiungi(l: Location) {
        precisione = l.accuracy
        if (l.accuracy > 25f) return // punto troppo impreciso
        fix = true
        val prec = ultima
        ultima = l
        if (prec == null && !inPausa) segna(l)
        if (prec == null || inPausa) return
        val d = prec.distanceTo(l).toDouble()
        val dt = (l.time - prec.time) / 1000.0
        if (dt <= 0) return
        // scarta salti impossibili (> 8 m/s ≈ 2:05/km) e il rumore da fermo
        if (d / dt > 8.0 || d < 1.5) return
        metri += d
        segna(l)
        secondiInMovimento += dt
        ultimoMovimento = System.currentTimeMillis()
        recenti.addLast(l.time to metri)
        while (recenti.size > 2 && l.time - recenti.first().first > 15_000) recenti.removeFirst()
        val (t0, m0) = recenti.first()
        val sec = (l.time - t0) / 1000.0
        velocita = if (sec >= 3) (metri - m0) / sec else d / dt
    }

    private fun segna(l: Location) {
        synchronized(punti) {
            val u = punti.lastOrNull()
            if (u != null) {
                val r = FloatArray(1)
                Location.distanceBetween(u[0], u[1], l.latitude, l.longitude, r)
                if (r[0] < 5f) return
            }
            if (punti.size < 20_000) punti.add(doubleArrayOf(l.latitude, l.longitude))
        }
    }

    /** Tracciato piatto: [lat0, lon0, lat1, lon1, …]. */
    fun traccia(): List<Double> = synchronized(punti) { punti.flatMap { listOf(it[0], it[1]) } }

    fun stato(): Map<String, Any> = mapOf(
        "attivo" to attivo,
        "fix" to fix,
        "errore" to errore,
        "metri" to metri,
        // fermo da più di 6": velocità zero
        "velocita" to if (System.currentTimeMillis() - ultimoMovimento < 6_000) velocita else 0.0,
        "precisione" to precisione.toDouble(),
        "secondiInMovimento" to secondiInMovimento,
        "punti" to synchronized(punti) { punti.size },
    )
}
