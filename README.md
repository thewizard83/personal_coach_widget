# personal_coach_widget

Plugin Flutter (solo Android) per l'app **PersonalCoach**:

- **Widget della schermata home**: settimana del piano, fase, barra di avanzamento, sedute fatte e saltate, streak e prossima seduta (tipo, giorno, ora, luogo, riassunto dei blocchi, nota di ripianificazione). Toccandolo si apre l'app.
- **Notifiche locali** (canale "Coach") per le modifiche al piano e il promemoria del mattino.

Il plugin non contiene dati personali: riceve un JSON dall'app.

## API Dart

```dart
await PersonalCoachWidget.aggiorna({...});            // dati del widget
await PersonalCoachWidget.notifica('Titolo', 'Testo');
await PersonalCoachWidget.richiediPermessoNotifiche(); // Android 13+
await PersonalCoachWidget.aggiungiAllaHome();          // Android 8+
```

Su piattaforme diverse da Android tutte le chiamate sono no-op.

## Uso come dipendenza git (FlutterFlow)

```yaml
personal_coach_widget:
  git:
    url: https://github.com/thewizard83/personal_coach_widget.git
    ref: <commit>
```
