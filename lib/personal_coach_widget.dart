import 'dart:convert';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

/// Ponte verso il widget Android della schermata home e le notifiche locali.
/// Su piattaforme diverse da Android tutte le chiamate sono no-op.
class PersonalCoachWidget {
  static const MethodChannel _ch = MethodChannel('personal_coach_widget');

  static bool get _android =>
      !kIsWeb && defaultTargetPlatform == TargetPlatform.android;

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
    _ch.setMethodCallHandler((call) async {
      if (call.method == 'pebbleMessaggio') {
        try {
          await suMessaggio(jsonDecode(call.arguments as String) as List<dynamic>);
        } catch (_) {}
      }
    });
    try {
      await _ch.invokeMethod('pebbleAscolta', {'uuid': uuid});
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
