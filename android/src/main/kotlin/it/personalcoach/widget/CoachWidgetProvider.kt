package it.personalcoach.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.graphics.Color
import android.view.View
import android.widget.RemoteViews
import org.json.JSONObject

/** Widget della schermata home: avanzamento del piano e prossima seduta. */
class CoachWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        for (id in ids) manager.updateAppWidget(id, costruisci(context))
    }

    companion object {
        const val PREFS = "personal_coach_widget"
        const val CHIAVE = "dati"

        fun aggiornaTutti(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, CoachWidgetProvider::class.java))
            if (ids.isEmpty()) return
            val views = costruisci(context)
            for (id in ids) manager.updateAppWidget(id, views)
        }

        private fun coloreTipo(tipo: String): Int = Color.parseColor(
            when (tipo) {
                "piscina" -> "#1FA2E0"
                "qualita" -> "#FF8A3D"
                "test" -> "#8E44AD"
                "gara" -> "#E5484D"
                "scarico" -> "#14B8A6"
                else -> "#2EAD6B"
            }
        )

        private fun iconaTipo(tipo: String): Int = when (tipo) {
            "piscina" -> R.drawable.coach_tipo_piscina
            "qualita" -> R.drawable.coach_tipo_qualita
            "test" -> R.drawable.coach_tipo_test
            "gara" -> R.drawable.coach_tipo_gara
            "scarico" -> R.drawable.coach_tipo_scarico
            else -> R.drawable.coach_tipo_corsa
        }

        fun costruisci(context: Context): RemoteViews {
            val v = RemoteViews(context.packageName, R.layout.coach_widget)
            val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(CHIAVE, null)

            // Tocco sul widget → apre l'app
            context.packageManager.getLaunchIntentForPackage(context.packageName)?.let { intent ->
                val pi = PendingIntent.getActivity(
                    context, 0, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                v.setOnClickPendingIntent(R.id.coach_radice, pi)
            }
            if (raw == null) return v

            try {
                val d = JSONObject(raw)
                v.setTextViewText(R.id.coach_settimana, "Settimana ${d.optInt("settimana", 1)}/${d.optInt("totale", 22)}")
                v.setTextViewText(R.id.coach_streak, "🔥 ${d.optInt("streak", 0)}")
                v.setTextViewText(R.id.coach_fase, "Fase ${d.optInt("fase", 1)} · ${d.optString("nomeFase", "Base")} · obiettivo 10 km in 1 ora")
                v.setProgressBar(R.id.coach_barra, 100, d.optInt("percentuale", 0).coerceIn(2, 100), false)
                v.setTextViewText(
                    R.id.coach_statistiche,
                    "✓ ${d.optInt("fatte", 0)} fatte · ✕ ${d.optInt("saltate", 0)} saltate · ${d.optInt("percentuale", 0)}% del piano"
                )

                val p = d.optJSONObject("prossima")
                if (p == null) {
                    v.setTextViewText(R.id.coach_etichetta, "PIANO COMPLETATO")
                    v.setTextViewText(R.id.coach_titolo, "🎉 Hai finito tutte le sedute!")
                    v.setTextViewText(R.id.coach_quando, "")
                    v.setTextViewText(R.id.coach_riassunto, "")
                    v.setViewVisibility(R.id.coach_nota, View.GONE)
                } else {
                    val tipo = p.optString("tipo", "corsa")
                    v.setImageViewResource(R.id.coach_icona, iconaTipo(tipo))
                    val oggi = if (p.optBoolean("oggi", false)) "OGGI · " else "PROSSIMA · "
                    v.setTextViewText(R.id.coach_etichetta, oggi + p.optString("nomeTipo", "Corsa").uppercase())
                    v.setTextColor(R.id.coach_etichetta, coloreTipo(tipo))
                    v.setTextViewText(R.id.coach_titolo, p.optString("titolo", ""))
                    v.setTextViewText(R.id.coach_quando, "${p.optString("quando", "")} · ${p.optString("luogo", "")}")
                    v.setTextViewText(R.id.coach_riassunto, p.optString("riassunto", ""))
                    val nota = p.optString("nota", "")
                    if (nota.isNotEmpty()) {
                        v.setTextViewText(R.id.coach_nota, "↻ $nota")
                        v.setViewVisibility(R.id.coach_nota, View.VISIBLE)
                    } else {
                        v.setViewVisibility(R.id.coach_nota, View.GONE)
                    }
                }
            } catch (_: Exception) {
                // dati non validi: resta il layout di default
            }
            return v
        }
    }
}
