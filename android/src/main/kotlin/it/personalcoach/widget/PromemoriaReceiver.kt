package it.personalcoach.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject

// Promemoria a orario fisso (borsa per la seduta) con AlarmManager:
// esatti se SCHEDULE_EXACT_ALARM è concesso, altrimenti inexact. La lista
// resta salvata per riprogrammarla dopo un riavvio o un aggiornamento.
class PromemoriaReceiver : BroadcastReceiver() {

    override fun onReceive(c: Context, i: Intent) {
        when (i.action) {
            AZIONE -> PersonalCoachWidgetPlugin.mostraNotifica(
                c,
                i.getIntExtra("id", 0),
                i.getStringExtra("titolo") ?: "PersonalCoach",
                i.getStringExtra("testo") ?: "",
                PersonalCoachWidgetPlugin.CANALE_PROMEMORIA
            )
            else -> programma(c, leggi(c)) // riavvio, aggiornamento, permesso cambiato
        }
    }

    companion object {
        const val AZIONE = "it.personalcoach.widget.PROMEMORIA"
        private const val PREFS = "coach_promemoria"
        private const val CHIAVE = "lista"

        fun esatti(c: Context): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
            return (c.getSystemService(Context.ALARM_SERVICE) as AlarmManager).canScheduleExactAlarms()
        }

        private fun leggi(c: Context): JSONArray = try {
            JSONArray(c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(CHIAVE, "[]"))
        } catch (_: Exception) {
            JSONArray()
        }

        private fun intento(c: Context, p: JSONObject?, id: Int, flag: Int): PendingIntent? {
            val i = Intent(c, PromemoriaReceiver::class.java).setAction(AZIONE)
            if (p != null) {
                i.putExtra("id", id).putExtra("titolo", p.optString("titolo")).putExtra("testo", p.optString("testo"))
            }
            return PendingIntent.getBroadcast(c, id, i, flag or PendingIntent.FLAG_IMMUTABLE)
        }

        /** Sostituisce i promemoria programmati con [lista]: [{id, quando (ms), titolo, testo}]. */
        fun sostituisci(c: Context, lista: JSONArray): Boolean {
            val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val vecchi = leggi(c)
            for (k in 0 until vecchi.length()) {
                val id = vecchi.optJSONObject(k)?.optInt("id") ?: continue
                intento(c, null, id, PendingIntent.FLAG_NO_CREATE)?.let { am.cancel(it); it.cancel() }
            }
            c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(CHIAVE, lista.toString()).apply()
            return programma(c, lista)
        }

        /** Programma gli elementi futuri di [lista]; true se gli allarmi sono esatti. */
        fun programma(c: Context, lista: JSONArray): Boolean {
            val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val esatti = esatti(c)
            val adesso = System.currentTimeMillis()
            for (k in 0 until lista.length()) {
                val p = lista.optJSONObject(k) ?: continue
                val quando = p.optLong("quando")
                if (quando <= adesso) continue
                val id = p.optInt("id")
                val pi = intento(c, p, id, PendingIntent.FLAG_UPDATE_CURRENT) ?: continue
                try {
                    if (esatti) {
                        am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, quando, pi)
                    } else {
                        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, quando, pi)
                    }
                } catch (_: SecurityException) {
                    am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, quando, pi)
                }
            }
            return esatti
        }
    }
}
