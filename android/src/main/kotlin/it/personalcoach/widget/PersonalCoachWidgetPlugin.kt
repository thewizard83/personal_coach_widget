package it.personalcoach.widget

import android.Manifest
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.PluginRegistry

class PersonalCoachWidgetPlugin : FlutterPlugin, MethodChannel.MethodCallHandler, ActivityAware,
    PluginRegistry.RequestPermissionsResultListener {

    private lateinit var channel: MethodChannel
    private lateinit var context: Context
    private var activity: Activity? = null
    private var binding: ActivityPluginBinding? = null
    private var permessoPendente: MethodChannel.Result? = null

    override fun onAttachedToEngine(b: FlutterPlugin.FlutterPluginBinding) {
        context = b.applicationContext
        channel = MethodChannel(b.binaryMessenger, "personal_coach_widget")
        channel.setMethodCallHandler(this)
    }

    override fun onDetachedFromEngine(b: FlutterPlugin.FlutterPluginBinding) {
        channel.setMethodCallHandler(null)
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
                    call.argument<Int>("id") ?: 1,
                    call.argument<String>("titolo") ?: "PersonalCoach",
                    call.argument<String>("testo") ?: ""
                )
                result.success(true)
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

    private fun mostraNotifica(id: Int, titolo: String, testo: String) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CANALE, context.getString(R.string.coach_canale), NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val pi = intent?.let {
            PendingIntent.getActivity(context, id, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
        val n = NotificationCompat.Builder(context, CANALE)
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

    override fun onAttachedToActivity(b: ActivityPluginBinding) {
        activity = b.activity; binding = b; b.addRequestPermissionsResultListener(this)
    }

    override fun onDetachedFromActivityForConfigChanges() = onDetachedFromActivity()
    override fun onReattachedToActivityForConfigChanges(b: ActivityPluginBinding) = onAttachedToActivity(b)
    override fun onDetachedFromActivity() {
        binding?.removeRequestPermissionsResultListener(this); binding = null; activity = null
    }

    companion object {
        private const val CANALE = "coach"
        private const val CODICE_PERMESSO = 4711
    }
}
