package it.personalcoach.widget

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

// Guida vocale del timer: sintesi in italiano (abbassa la musica mentre
// parla), verifica degli auricolari Bluetooth e wake lock parziale perché il
// timer continui a schermo spento.
object Voce {
    private var tts: TextToSpeech? = null
    private var pronta = false
    private var inAttesa: Pair<String, Boolean>? = null
    private var focus: AudioFocusRequest? = null
    private var wake: PowerManager.WakeLock? = null
    private var progressivo = 0

    private val attributi: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    /** True se tra le uscite audio c'è un dispositivo Bluetooth (A2DP, SCO, LE Audio, apparecchi acustici). */
    fun cuffieBluetooth(c: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false
        val am = c.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val tipi = mutableSetOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) tipi.add(AudioDeviceInfo.TYPE_HEARING_AID)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) tipi.add(AudioDeviceInfo.TYPE_BLE_HEADSET)
        return am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in tipi }
    }

    private fun prepara(c: Context) {
        if (tts != null) return
        val app = c.applicationContext
        tts = TextToSpeech(app) { stato ->
            pronta = stato == TextToSpeech.SUCCESS
            if (pronta) {
                tts?.language = Locale.ITALIAN
                tts?.setAudioAttributes(attributi)
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) {}
                    override fun onDone(id: String?) = rilascia(app)
                    @Deprecated("Deprecated in Java")
                    override fun onError(id: String?) = rilascia(app)
                })
                inAttesa?.let { (t, coda) -> dici(app, t, coda) }
            }
            inAttesa = null
        }
    }

    /** Pronuncia [testo]; con [coda] = false interrompe quello che sta dicendo. */
    fun parla(c: Context, testo: String, coda: Boolean, soloCuffie: Boolean): Boolean {
        if (soloCuffie && !cuffieBluetooth(c)) return false
        prepara(c)
        if (!pronta) {
            inAttesa = testo to coda
            return true
        }
        dici(c.applicationContext, testo, coda)
        return true
    }

    private fun dici(c: Context, testo: String, coda: Boolean) {
        val am = c.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && focus == null) {
            val f = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(attributi)
                .build()
            am.requestAudioFocus(f)
            focus = f
        }
        progressivo++
        tts?.speak(testo, if (coda) TextToSpeech.QUEUE_ADD else TextToSpeech.QUEUE_FLUSH, Bundle(), "coach$progressivo")
    }

    private fun rilascia(c: Context) {
        if (tts?.isSpeaking == true) return
        val am = c.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) focus?.let { am.abandonAudioFocusRequest(it) }
        focus = null
    }

    fun zitto(c: Context) {
        tts?.stop()
        rilascia(c)
    }

    /** Tiene sveglia la CPU mentre il timer corre (max 3 ore), così va avanti a schermo spento. */
    fun sveglio(c: Context, attivo: Boolean) {
        if (attivo) {
            if (wake?.isHeld == true) return
            val pm = c.getSystemService(Context.POWER_SERVICE) as PowerManager
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "personalcoach:timer").apply {
                setReferenceCounted(false)
                acquire(3 * 60 * 60 * 1000L)
            }
        } else {
            wake?.let { if (it.isHeld) it.release() }
            wake = null
        }
    }
}
