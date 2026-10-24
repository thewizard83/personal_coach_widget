package it.personalcoach.widget

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * Riceve il ritorno dall'autorizzazione Strava (personalcoach://localhost?code=...),
 * lo consegna al plugin e riporta in primo piano l'app.
 */
class StravaCallbackActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PersonalCoachWidgetPlugin.consegnaStrava(intent?.data?.toString())
        packageManager.getLaunchIntentForPackage(packageName)?.let {
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            startActivity(it)
        }
        finish()
    }
}
