import 'dart:async';
import 'dart:convert';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

/// Ponte verso il widget Android della schermata home e le notifiche locali.
/// Su piattaforme diverse da Android tutte le chiamate sono no-op.
class PersonalCoachWidget {
  static const MethodChannel _ch = MethodChannel('personal_coach_widget');

  static bool get _android =>
      !kIsWeb && defaultTargetPlatform == TargetPlatform.android;

  static Future<void> Function(List<dynamic> tuple)? _suPebble;
  static Future<void> Function(String percorso, String json)? _suOrologio;
  static Future<void> Function(String giorno, String luogo)? _suLuogo;

  // un solo gestore per i messaggi che arrivano dal lato Android
  static void _gestore() {
    _ch.setMethodCallHandler((call) async {
      try {
        if (call.method == 'pebbleMessaggio' && _suPebble != null) {
          await _suPebble!(jsonDecode(call.arguments as String) as List<dynamic>);
        } else if (call.method == 'luogoRisposto' && _suLuogo != null) {
          final a = (call.arguments as Map).cast<String, dynamic>();
          await _suLuogo!(a['giorno'] as String, a['luogo'] as String);
        } else if (call.method == 'orologioMessaggio' && _suOrologio != null) {
          final a = (call.arguments as Map).cast<String, dynamic>();
          await _suOrologio!(a['percorso'] as String, a['json'] as String);
        }
      } catch (_) {}
    });
  }

  /// Salva i dati mostrati dal widget e lo ridisegna.
  static Future<void> aggiorna(Map<String, dynamic> dati) async {
    if (!_android) return;
    try {
      await _ch.invokeMethod('aggiorna', {'json': jsonEncode(dati)});
    } catch (_) {}
  }

  /// Mostra una notifica locale (canale "Coach").
  static Future<void> notifica(String titolo, String testo, {int id = 1}) async {
    if (!_android) return;
    try {
      await _ch.invokeMethod('notifica', {'id': id, 'titolo': titolo, 'testo': testo});
    } catch (_) {}
  }

  /// Sostituisce i promemoria a orario fisso con [lista]
  /// ({id, quando: DateTime, titolo, testo}), programmati con AlarmManager.
  /// Restituisce true se sono esatti (SCHEDULE_EXACT_ALARM concesso).
  static Future<bool> programmaPromemoria(List<Map<String, dynamic>> lista) async {
    if (!_android) return false;
    final json = [
      for (final p in lista)
        {...p, 'quando': (p['quando'] as DateTime).millisecondsSinceEpoch},
    ];
    try {
      return await _ch.invokeMethod<bool>('programmaPromemoria', {'json': jsonEncode(json)}) ?? false;
    } catch (_) {
      return false;
    }
  }

  /// Chiede il permesso di posizione precisa. True se concesso.
  static Future<bool> gpsPermesso() async {
    if (!_android) return false;
    try {
      return await _ch.invokeMethod<bool>('gpsPermesso') ?? false;
    } catch (_) {
      return false;
    }
  }

  /// Avvia la traccia GPS (servizio in primo piano). False se manca il permesso.
  static Future<bool> gpsAvvia() async {
    if (!_android) return false;
    try {
      return await _ch.invokeMethod<bool>('gpsAvvia') ?? false;
    } catch (_) {
      return false;
    }
  }

  /// In pausa la distanza non aumenta.
  static Future<void> gpsPausa(bool pausa) async {
    if (!_android) return;
    try {
      await _ch.invokeMethod('gpsPausa', {'pausa': pausa});
    } catch (_) {}
  }

  static Future<void> gpsFerma() async {
    if (!_android) return;
    try {
      await _ch.invokeMethod('gpsFerma');
    } catch (_) {}
  }

  /// {attivo, fix, errore, metri, velocita (m/s), precisione (m), secondiInMovimento, punti}
  static Future<Map<String, dynamic>> gpsStato() async {
    if (!_android) return {};
    try {
      return (await _ch.invokeMethod<Map>('gpsStato'))?.cast<String, dynamic>() ?? {};
    } catch (_) {
      return {};
    }
  }

  /// Tracciato della corsa in corso (o appena finita): [[lat, lon], …].
  static Future<List<List<double>>> gpsTraccia() async {
    if (!_android) return const [];
    try {
      final v = (await _ch.invokeMethod<List>('gpsTraccia')) ?? const [];
      return [
        for (var i = 0; i + 1 < v.length; i += 2) [(v[i] as num).toDouble(), (v[i + 1] as num).toDouble()],
      ];
    } catch (_) {
      return const [];
    }
  }

  /// True se sono collegati auricolari (o cuffie) Bluetooth.
  static Future<bool> cuffieBluetooth() async {
    if (!_android) return false;
    try {
      return await _ch.invokeMethod<bool>('cuffieBluetooth') ?? false;
    } catch (_) {
      return false;
    }
  }

  /// Pronuncia [testo] in italiano abbassando la musica. Con [soloCuffie]
  /// parla solo se ci sono auricolari Bluetooth; con [coda] non interrompe
  /// la frase in corso. Restituisce true se ha parlato.
  static Future<bool> parla(String testo, {bool coda = false, bool soloCuffie = true}) async {
    if (!_android) return false;
    try {
      return await _ch.invokeMethod<bool>('parla', {'testo': testo, 'coda': coda, 'soloCuffie': soloCuffie}) ?? false;
    } catch (_) {
      return false;
    }
  }

  /// Interrompe la guida vocale.
  static Future<void> zitto() async {
    if (!_android) return;
    try {
      await _ch.invokeMethod('zitto');
    } catch (_) {}
  }

  /// Tiene sveglia la CPU (wake lock parziale) mentre il timer corre.
  static Future<void> sveglio(bool attivo) async {
    if (!_android) return;
    try {
      await _ch.invokeMethod('sveglio', {'attivo': attivo});
    } catch (_) {}
  }

  /// Notifica "Dove sei [giorno]?" con i pulsanti Lecco / Casa / Milano. La
  /// risposta finisce in SharedPreferences ('coach_luogo_<giorno>': lecco,
  /// casa o milano) e, ad app aperta, arriva a [ascoltaLuogo].
  static Future<void> chiediLuogo(int id, String giorno, String titolo, String testo) async {
    if (!_android) return;
    try {
      await _ch.invokeMethod('chiediLuogo', {'id': id, 'giorno': giorno, 'titolo': titolo, 'testo': testo});
    } catch (_) {}
  }

  /// Riceve le risposte alla domanda di [chiediLuogo] mentre l'app è aperta.
  static void ascoltaLuogo(Future<void> Function(String giorno, String luogo) suRisposta) {
    if (!_android) return;
    _suLuogo = suRisposta;
    _gestore();
  }

  /// True se l'app può programmare allarmi esatti (sempre sotto Android 12).
  static Future<bool> allarmiEsatti() async {
    if (!_android) return false;
    try {
      return await _ch.invokeMethod<bool>('allarmiEsatti') ?? false;
    } catch (_) {
      return false;
    }
  }

  /// Apre l'impostazione di sistema "Sveglie e promemoria" (Android 12+).
  static Future<void> consentiAllarmiEsatti() async {
    if (!_android) return;
    try {
      await _ch.invokeMethod('consentiAllarmiEsatti');
    } catch (_) {}
  }

  /// Chiede il permesso per le notifiche (Android 13+). Restituisce true se concesso.
  static Future<bool> richiediPermessoNotifiche() async {
    if (!_android) return false;
    try {
      return await _ch.invokeMethod<bool>('richiediPermessoNotifiche') ?? false;
    } catch (_) {
      return false;
    }
  }

  /// True se le notifiche dell'app sono abilitate.
  static Future<bool> notificheAbilitate() async {
    if (!_android) return false;
    try {
      return await _ch.invokeMethod<bool>('notificheAbilitate') ?? false;
    } catch (_) {
      return false;
    }
  }

  /// Chiede al launcher di aggiungere il widget alla schermata home (Android 8+).
  static Future<bool> aggiungiAllaHome() async {
    if (!_android) return false;
    try {
      return await _ch.invokeMethod<bool>('aggiungiAllaHome') ?? false;
    } catch (_) {
      return false;
    }
  }

  /// Ascolta i messaggi della watchapp Pebble [uuid] (PebbleKit Android).
  /// [suMessaggio] riceve le tuple: [{key, type, length, value}, ...].
  static Future<void> pebbleAscolta(String uuid, Future<void> Function(List<dynamic> tuple) suMessaggio) async {
    if (!_android) return;
    _suPebble = suMessaggio;
    _gestore();
    try {
      await _ch.invokeMethod('pebbleAscolta', {'uuid': uuid});
    } catch (_) {}
  }

  /// Apre la watchapp Pebble [uuid] sull'orologio (se è già aperta non cambia nulla).
  static Future<void> pebbleApri(String uuid) async {
    if (!_android) return;
    try {
      await _ch.invokeMethod('pebbleApri', {'uuid': uuid});
    } catch (_) {}
  }

  /// Seduta in diretta per la watchapp: chi gestisce il timer la aggiorna a
  /// ogni cambio (null quando il timer si chiude), chi parla con l'orologio
  /// la ascolta.
  static final pebbleDiretta = ValueNotifier<Map<String, dynamic>?>(null);

  /// Comandi arrivati dall'orologio per il timer in diretta.
  static final pebbleComandi = StreamController<int>.broadcast();

  /// Ascolta l'app Coach per Wear OS: [suMessaggio] riceve il percorso
  /// ('/coach/comando' o '/coach/conferma') e il JSON.
  static Future<void> orologioAscolta(Future<void> Function(String percorso, String json) suMessaggio) async {
    if (!_android) return;
    _suOrologio = suMessaggio;
    _gestore();
    try {
      await _ch.invokeMethod('orologioAscolta');
    } catch (_) {}
  }

  /// Pubblica [dati] per l'orologio Wear OS sul percorso del Data Layer.
  static Future<void> orologioPubblica(String percorso, Map<String, dynamic> dati) async {
    if (!_android) return;
    try {
      await _ch.invokeMethod('orologioPubblica', {'percorso': percorso, 'json': jsonEncode(dati)});
    } catch (_) {}
  }

  /// Apre l'app Coach sugli orologi Wear OS collegati.
  static Future<void> orologioApri() async {
    if (!_android) return;
    try {
      await _ch.invokeMethod('orologioApri');
    } catch (_) {}
  }

  /// Invia tuple alla watchapp Pebble [uuid] (deve essere aperta sull'orologio).
  static Future<void> pebbleInvia(String uuid, List<Map<String, dynamic>> tuple) async {
    if (!_android) return;
    try {
      await _ch.invokeMethod('pebbleInvia', {'uuid': uuid, 'json': jsonEncode(tuple)});
    } catch (_) {}
  }

  /// Apre l'autorizzazione Strava e restituisce l'indirizzo di ritorno
  /// (personalcoach://localhost?code=...), o null se annullata.
  static Future<String?> stravaAutorizza(String url) async {
    if (!_android) return null;
    try {
      return await _ch.invokeMethod<String>('stravaAutorizza', {'url': url});
    } catch (_) {
      return null;
    }
  }
}
