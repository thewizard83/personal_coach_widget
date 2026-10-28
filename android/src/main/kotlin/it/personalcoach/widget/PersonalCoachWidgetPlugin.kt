package it.personalcoach.widget

import android.Manifest
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.wear.remote.interactions.RemoteActivityHelper
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataItem
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.PluginRegistry
import java.util.UUID
import java.util.concurrent.Executors

class PersonalCoachWidgetPlugin : FlutterPlugin, MethodChannel.MethodCallHandler, ActivityAware,
    PluginRegistry.RequestPermissionsResultListener {

    private lateinit var channel: MethodChannel
    private lateinit var context: Context
    private var activity: Activity? = null
    private var binding: ActivityPluginBinding? = null
    private var permessoPendente: MethodChannel.Result? = null
    // Pebble (PebbleKit Android "classic", supportato dall'app Pebble di Core)
    private var pebbleUuid: UUID? = null
    private var pebbleRicevitore: BroadcastReceiver? = null
    private var pebbleTransazione = 0
    // Wear OS (app Coach sull'orologio, stesso applicationId): Data Layer
    private var orologioAttivo = false
    private val orologioDati = DataClient.OnDataChangedListener { eventi ->
        for (e in eventi) {
            if (e.type == DataEvent.TYPE_CHANGED) orologioLeggi(e.dataItem)
        }
        eventi.release()
    }
    private val orologioMessaggi = MessageClient.OnMessageReceivedListener { m ->
        if (m.path == "/coach/comando" && m.data.isNotEmpty()) {
            orologioADart(m.path, "{\"comando\":${m.data[0].toInt()}}")
        }
    }

    override fun onAttachedToEngine(b: FlutterPlugin.FlutterPluginBinding) {
        context = b.applicationContext
        channel = MethodChannel(b.binaryMessenger, "personal_coach_widget")
        channel.setMethodCallHandler(this)
    }

    override fun onDetachedFromEngine(b: FlutterPlugin.FlutterPluginBinding) {
        channel.setMethodCallHandler(null)
        if (istanza === this) istanza = null
        pebbleRicevitore?.let { try { context.unregisterReceiver(it) } catch (_: Exception) {} }
        pebbleRicevitore = null
        if (orologioAttivo) {
            Wearable.getDataClient(context).removeListener(orologioDati)
            Wearable.getMessageClient(context).removeListener(orologioMessaggi)
            orologioAttivo = false
        }
    }

    // Conferme dall'orologio (DataItem /coach/conferma): passano a Dart e poi
    // si cancellano, così ognuna conta una volta sola; quelle arrivate ad app
    // chiusa si leggono all'avvio.
    private fun orologioAscolta() {
        if (orologioAttivo) return
        orologioAttivo = true
        val dati = Wearable.getDataClient(context)
        dati.addListener(orologioDati)
        Wearable.getMessageClient(context).addListener(orologioMessaggi)
        dati.dataItems.addOnSuccessListener { items ->
            for (i in items) orologioLeggi(i)
            items.release()
        }
    }

    private fun orologioLeggi(item: DataItem) {
        if (item.uri.path != "/coach/conferma") return
        val json = try { DataMapItem.fromDataItem(item).dataMap.getString("json") } catch (_: Exception) { null } ?: return
        orologioADart("/coach/conferma", json)
        Wearable.getDataClient(context).deleteDataItems(item.uri)
    }

    private fun orologioADart(percorso: String, json: String) {
        Handler(Looper.getMainLooper()).post {
            channel.invokeMethod("orologioMessaggio", mapOf("percorso" to percorso, "json" to json))
        }
    }

    private fun orologioPubblica(percorso: String, json: String) {
        val r = PutDataMapRequest.create(percorso)
        r.dataMap.putString("json", json)
        Wearable.getDataClient(context).putDataItem(r.asPutDataRequest().setUrgent())
    }

    // Apre l'app Coach sugli orologi collegati.
    private fun orologioApri() {
        Wearable.getNodeClient(context).connectedNodes.addOnSuccessListener { nodi ->
            if (nodi.isEmpty()) return@addOnSuccessListener
            val helper = RemoteActivityHelper(context, Executors.newSingleThreadExecutor())
            val i = Intent(Intent.ACTION_VIEW)
                .addCategory(Intent.CATEGORY_BROWSABLE)
                .setData(android.net.Uri.parse("personalcoach-wear://diretta"))
            for (n in nodi) {
                try { helper.startRemoteActivity(i, n.id) } catch (_: Exception) {}
            }
        }
    }

    // Messaggi dalla watchapp: ACK subito all'app Pebble, poi a Dart.
    private fun pebbleAscolta(uuid: UUID) {
        pebbleUuid = uuid
        if (pebbleRicevitore != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                @Suppress("DEPRECATION")
                val u = (i.getSerializableExtra(PEBBLE_UUID) as? UUID)
                    ?: i.getStringExtra(PEBBLE_UUID)?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                if (u == null || u != pebbleUuid) return
                val tid = i.getIntExtra(PEBBLE_TRANSAZIONE, -1)
                c.sendBroadcast(Intent(PEBBLE_ACK).putExtra(PEBBLE_TRANSAZIONE, tid))
                val json = i.getStringExtra(PEBBLE_DATI) ?: return
                Handler(Looper.getMainLooper()).post { channel.invokeMethod("pebbleMessaggio", json) }
            }
        }
        ContextCompat.registerReceiver(context, r, IntentFilter(PEBBLE_RICEVI), ContextCompat.RECEIVER_EXPORTED)
        pebbleRicevitore = r
    }

    // Apre la watchapp sull'orologio (idempotente se è già aperta).
    private fun pebbleApri(uuid: UUID) {
        context.sendBroadcast(Intent(PEBBLE_APRI).putExtra(PEBBLE_UUID, uuid))
    }

    private fun pebbleInvia(uuid: UUID, json: String) {
        pebbleTransazione = (pebbleTransazione + 1) % 256
        context.sendBroadcast(
            Intent(PEBBLE_INVIA)
                .putExtra(PEBBLE_UUID, uuid)
                .putExtra(PEBBLE_TRANSAZIONE, pebbleTransazione)
                .putExtra(PEBBLE_DATI, json)
        )
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "aggiorna" -> {
                val json = call.argument<String>("json") ?: "{}"
                context.getSharedPreferences(CoachWidgetProvider.PREFS, Context.MODE_PRIVATE)
                    .edit().putString(CoachWidgetProvider.CHIAVE, json).apply()
                CoachWidgetProvider.aggiornaTutti(context)
                result.success(true)
            }
            "notifica" -> {
                mostraNotifica(
                    context,
                    call.argument<Int>("id") ?: 1,
                    call.argument<String>("titolo") ?: "PersonalCoach",
                    call.argument<String>("testo") ?: ""
                )
                result.success(true)
            }
            "programmaPromemoria" -> {
                val lista = try { org.json.JSONArray(call.argument<String>("json") ?: "[]") } catch (_: Exception) { org.json.JSONArray() }
                result.success(PromemoriaReceiver.sostituisci(context, lista))
            }
            "chiediLuogo" -> {
                LuogoReceiver.chiedi(
                    context,
                    call.argument<Int>("id") ?: 4000,
                    call.argument<String>("giorno") ?: "",
                    call.argument<String>("titolo") ?: "Dove sei?",
                    call.argument<String>("testo") ?: ""
                )
                result.success(true)
            }
            "allarmiEsatti" -> result.success(PromemoriaReceiver.esatti(context))
            "consentiAllarmiEsatti" -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val i = Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                        .setData(android.net.Uri.parse("package:${context.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    try { context.startActivity(i) } catch (_: Exception) {}
                }
                result.success(PromemoriaReceiver.esatti(context))
            }
            "notificheAbilitate" -> result.success(NotificationManagerCompat.from(context).areNotificationsEnabled())
            "richiediPermessoNotifiche" -> richiediPermesso(result)
            "aggiungiAllaHome" -> {
                val manager = AppWidgetManager.getInstance(context)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && manager.isRequestPinAppWidgetSupported) {
                    result.success(manager.requestPinAppWidget(ComponentName(context, CoachWidgetProvider::class.java), null, null))
                } else {
                    result.success(false)
                }
            }
            "stravaAutorizza" -> {
                // apre la pagina (o l'app) Strava; il risultato arriva da StravaCallbackActivity
                stravaInAttesa?.success(null)
                stravaInAttesa = result
                val i = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(call.argument<String>("url")))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try {
                    context.startActivity(i)
                } catch (e: Exception) {
                    stravaInAttesa = null
                    result.success(null)
                }
            }
            "pebbleAscolta" -> {
                pebbleAscolta(UUID.fromString(call.argument<String>("uuid")))
                result.success(true)
            }
            "pebbleApri" -> {
                pebbleApri(UUID.fromString(call.argument<String>("uuid")))
                result.success(true)
            }
            "orologioAscolta" -> {
                orologioAscolta()
                result.success(true)
            }
            "orologioPubblica" -> {
                orologioPubblica(call.argument<String>("percorso") ?: "/coach/seduta", call.argument<String>("json") ?: "{}")
                result.success(true)
            }
            "orologioApri" -> {
                orologioApri()
                result.success(true)
            }
            "pebbleInvia" -> {
                pebbleInvia(UUID.fromString(call.argument<String>("uuid")), call.argument<String>("json") ?: "[]")
                result.success(true)
            }
            else -> result.notImplemented()
        }
    }

    private fun richiediPermesso(result: MethodChannel.Result) {
        if (Build.VERSION.SDK_INT < 33) {
            result.success(NotificationManagerCompat.from(context).areNotificationsEnabled()); return
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            result.success(true); return
        }
        val a = activity
        if (a == null) { result.success(false); return }
        permessoPendente = result
        ActivityCompat.requestPermissions(a, arrayOf(Manifest.permission.POST_NOTIFICATIONS), CODICE_PERMESSO)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray): Boolean {
        if (requestCode != CODICE_PERMESSO) return false
        permessoPendente?.success(grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED)
        permessoPendente = null
        return true
    }

    override fun onAttachedToActivity(b: ActivityPluginBinding) {
        activity = b.activity; binding = b; b.addRequestPermissionsResultListener(this)
        istanza = this
    }

    override fun onDetachedFromActivityForConfigChanges() = onDetachedFromActivity()
    override fun onReattachedToActivityForConfigChanges(b: ActivityPluginBinding) = onAttachedToActivity(b)
    override fun onDetachedFromActivity() {
        binding?.removeRequestPermissionsResultListener(this); binding = null; activity = null
        if (istanza === this) istanza = null
    }

    companion object {
        private var stravaInAttesa: MethodChannel.Result? = null
        // plugin dell'engine con l'activity aperta (per inoltrare le risposte)
        private var istanza: PersonalCoachWidgetPlugin? = null

        fun luogoRisposto(giorno: String, luogo: String) {
            val p = istanza ?: return
            Handler(Looper.getMainLooper()).post {
                try {
                    p.channel.invokeMethod("luogoRisposto", mapOf("giorno" to giorno, "luogo" to luogo))
                } catch (_: Exception) {}
            }
        }

        fun consegnaStrava(uri: String?) {
            val r = stravaInAttesa ?: return
            stravaInAttesa = null
            Handler(Looper.getMainLooper()).post { r.success(uri) }
        }

        const val CANALE = "coach"
        const val CANALE_PROMEMORIA = "promemoria"

        fun mostraNotifica(context: Context, id: Int, titolo: String, testo: String, canale: String = CANALE) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                nm.createNotificationChannel(
                    if (canale == CANALE_PROMEMORIA) {
                        NotificationChannel(canale, context.getString(R.string.coach_canale_promemoria), NotificationManager.IMPORTANCE_HIGH)
                    } else {
                        NotificationChannel(canale, context.getString(R.string.coach_canale), NotificationManager.IMPORTANCE_DEFAULT)
                    }
                )
            }
            val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            val pi = intent?.let {
                PendingIntent.getActivity(context, id, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            }
            val n = NotificationCompat.Builder(context, canale)
                .setSmallIcon(R.drawable.coach_notifica)
                .setColor(0xFF1F6FEB.toInt())
                .setContentTitle(titolo)
                .setContentText(testo)
                .setStyle(NotificationCompat.BigTextStyle().bigText(testo))
                .setAutoCancel(true)
                .apply { if (pi != null) setContentIntent(pi) }
                .build()
            try {
                NotificationManagerCompat.from(context).notify(id, n)
            } catch (_: SecurityException) {
                // permesso notifiche non concesso
            }
        }

        private const val CODICE_PERMESSO = 4711
        private const val PEBBLE_RICEVI = "com.getpebble.action.app.RECEIVE"
        private const val PEBBLE_INVIA = "com.getpebble.action.app.SEND"
        private const val PEBBLE_ACK = "com.getpebble.action.app.ACK"
        private const val PEBBLE_APRI = "com.getpebble.action.app.START"
        private const val PEBBLE_UUID = "uuid"
        private const val PEBBLE_TRANSAZIONE = "transaction_id"
        private const val PEBBLE_DATI = "msg_data"
    }
}
