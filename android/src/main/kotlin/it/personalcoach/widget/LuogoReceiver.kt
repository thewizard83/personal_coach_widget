package it.personalcoach.widget

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

// Domanda "Dove sei quel giorno?" con tre pulsanti (Lecco / Casa / Milano).
// La risposta va nelle SharedPreferences di Flutter ("flutter.coach_luogo_<giorno>")
// e, se l'app è aperta, a Dart per ripianificare subito.
class LuogoReceiver : BroadcastReceiver() {

    override fun onReceive(c: Context, i: Intent) {
        if (i.action != AZIONE) return
        val giorno = i.getStringExtra("giorno") ?: return
        val luogo = i.getStringExtra("luogo") ?: return
        c.getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE)
            .edit().putString("flutter.coach_luogo_$giorno", luogo).apply()
        NotificationManagerCompat.from(c).cancel(i.getIntExtra("id", 0))
        PersonalCoachWidgetPlugin.luogoRisposto(giorno, luogo)
    }

    companion object {
        const val AZIONE = "it.personalcoach.widget.LUOGO"

        fun chiedi(c: Context, id: Int, giorno: String, titolo: String, testo: String) {
            val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        PersonalCoachWidgetPlugin.CANALE_PROMEMORIA,
                        c.getString(R.string.coach_canale_promemoria),
                        NotificationManager.IMPORTANCE_HIGH
                    )
                )
            }
            val apri = c.packageManager.getLaunchIntentForPackage(c.packageName)?.let {
                PendingIntent.getActivity(c, id, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            }
            val b = NotificationCompat.Builder(c, PersonalCoachWidgetPlugin.CANALE_PROMEMORIA)
                .setSmallIcon(R.drawable.coach_notifica)
                .setColor(0xFF1F6FEB.toInt())
                .setContentTitle(titolo)
                .setContentText(testo)
                .setStyle(NotificationCompat.BigTextStyle().bigText(testo))
                .setAutoCancel(true)
                .apply { if (apri != null) setContentIntent(apri) }
            listOf("lecco" to "Lecco", "casa" to "Casa", "milano" to "Milano").forEachIndexed { k, (valore, etichetta) ->
                val r = Intent(c, LuogoReceiver::class.java).setAction(AZIONE)
                    .putExtra("id", id).putExtra("giorno", giorno).putExtra("luogo", valore)
                val pi = PendingIntent.getBroadcast(
                    c, id * 10 + k, r, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                b.addAction(0, etichetta, pi)
            }
            try {
                NotificationManagerCompat.from(c).notify(id, b.build())
            } catch (_: SecurityException) {
                // permesso notifiche non concesso
            }
        }
    }
}
