# Schulnews-Monitor

Die Spring-Boot-Anwendung beobachtet vier Schulwebseiten und meldet neue Beiträge mit Titel und Link in einer Telegram-Gruppe. Ein Titel mit `Anmeldung` wird unabhängig von Groß- und Kleinschreibung als **❗ WICHTIG** markiert. Die Anwendung archiviert die verlinkten Seiten lokal.

| Schule | Newsquelle | Telegram-Befehle |
| --- | --- | --- |
| Robert-Schuman-IGS Frankenthal | [Startseite](https://igs-frankenthal.de/) | `STOP IGS` · `START IGS` |
| IGS Edigheim | [News](https://igs-edigheim.de/news) | `STOP EDIGHEIM` · `START EDIGHEIM` |
| Albert-Einstein-Gymnasium Frankenthal | [Startseite](https://aeg-frankenthal.de/) | `STOP AEG` · `START AEG` |
| Karolinen-Gymnasium Frankenthal | [Startseite](https://karolinen-gymnasium.de/) | `STOP KG` · `START KG` |

Die Befehle gelten für die jeweilige Schule. `START` löst die nächste Prüfung sofort aus. Der Bot beantwortet Befehle aus der konfigurierten Gruppe. `/stop IGS` und `/start IGS` funktionieren ebenfalls, bei Bedarf mit Bot-Namen wie `/stop@DeinBot IGS`.

## Zeitplan

Sämtliche Uhrzeiten gelten für `Europe/Berlin`.

| Zeitraum | Prüfung | Störungsmeldung ab dem ersten fehlgeschlagenen Abruf |
| --- | --- | --- |
| Bis 24. Dezember 2026 | Zur vollen Stunde | Nach 6 Stunden |
| 25. Dezember 2026 bis 3. Januar 2027 | Alle 15 Minuten | Nach 6 Stunden |
| Ab 4. Januar 2027 | Jede Minute | Nach 30 Minuten |

- Beim Start prüft die Anwendung alle aktiven Quellen sofort. Sie archiviert beim ersten erfolgreichen Abruf die sichtbaren Beiträge und meldet davon die Beiträge mit `Anmeldung` im Titel. Danach meldet sie neue Links und geänderte Überschriften.
- Am 4. Januar 2027 um 8 Uhr erinnert der Bot an die mögliche Freischaltung der IGS-Anmeldung. Sonntags um 20 Uhr sendet er den Status aller Quellen und die Befehle. Bei einem späteren Anwendungsstart werden diese Nachrichten am selben Tag nachgeholt.
- Der Bot meldet eine anhaltende Störung einmal pro Quelle zwischen 6 und 22 Uhr und gibt bei einem erfolgreichen Abruf nach einer gemeldeten Störung Entwarnung. News und Entwarnungen sind grundsätzlich rund um die Uhr möglich. Eine zurückgehaltene Störungsmeldung wird vor der zugehörigen Entwarnung zugestellt.
- Der Monitor wertet die verlinkten Überschriften der vier Newslisten aus. Änderungen, die nur im Text eines bestehenden Artikels oder auf einer anderen Seite erscheinen, lösen keine Meldung aus.

## Telegram einrichten

1. Erstelle bei `@BotFather` mit `/newbot` einen Bot und bewahre das Token auf.
2. Stelle für Klartextbefehle wie `STOP IGS` bei `@BotFather` unter `/setprivacy` den Datenschutzmodus auf `Disable`. Falls der Bot bereits in der Gruppe war, entferne ihn und füge ihn erneut hinzu. Anschließend kann jedes Gruppenmitglied die Quellen steuern. [Telegram-Dokumentation](https://core.telegram.org/bots/features#privacy-mode)
3. Füge den Bot zur gewünschten Gruppe hinzu, erlaube ihm das Senden von Nachrichten und sende dort `/start@DeinBot`. Ermittle die negative Gruppen-ID über `getUpdates`, bevor du die Anwendung startest. Betreibe pro Bot nur eine Instanz; die Telegram-Abfrage benötigt einen Bot ohne aktiven Webhook. [Telegram Bot API](https://core.telegram.org/bots/api#getupdates)

So liest du die Gruppen-ID in PowerShell aus und setzt sie für den lokalen Start.

```powershell
$token = Read-Host 'Telegram-Bot-Token' -AsSecureString
$env:MONITOR_TELEGRAM_BOT_TOKEN = [pscredential]::new('bot', $token).GetNetworkCredential().Password
$updates = Invoke-RestMethod "https://api.telegram.org/bot$($env:MONITOR_TELEGRAM_BOT_TOKEN)/getUpdates"
$updates.result | ForEach-Object { $_.message.chat } |
    Where-Object { $_.type -in 'group', 'supergroup' } | Select-Object id,title -Unique
$env:MONITOR_TELEGRAM_CHAT_ID = Read-Host 'Negative Gruppen-ID'
```

## Mit Maven starten

Voraussetzungen sind Java 27 und Maven 3.9 oder neuer. Nach dem Setzen der beiden Telegram-Variablen startest du die Anwendung so.

```powershell
mvn verify
java -jar target/laura-schule.jar
```

Ohne Telegram-Zugangsdaten bleiben Abruf und Archiv aktiv. Ausgehende Nachrichten warten bis zur Einrichtung von Telegram. Der HTTP-Endpunkt `/actuator/health` prüft den Prozessstatus.

## Docker

GitHub Actions baut das Image für `linux/amd64` und `linux/arm64` und veröffentlicht beide Varianten unter `ghcr.io/jensgiehl/weiterfuehrende-schule:latest`. Das Image ist öffentlich abrufbar. Docker wählt die passende Variante. Verwende auf dem Raspberry Pi ein 64-Bit-Betriebssystem. [GitHub Actions](https://github.com/jensGiehl/weiterfuehrende-schule/actions)

Ersetze `HIER_DEIN_BOT_TOKEN` und `-1001234567890` in den `--env`-Parametern durch dein Bot-Token und deine Gruppen-ID. Das Skript zieht bei jedem Aufruf das aktuelle Image, ersetzt den Container und bindet dasselbe Datenvolume wieder ein.

```bash
docker rm -f laura-schule 2>/dev/null

docker run -d \
  --name laura-schule \
  --pull=always \
  -p 8089:8080 \
  --restart unless-stopped \
  --env 'MONITOR_TELEGRAM_BOT_TOKEN=HIER_DEIN_BOT_TOKEN' \
  --env 'MONITOR_TELEGRAM_CHAT_ID=-1001234567890' \
  --mount type=volume,source=laura-schule-data,target=/app/data \
  ghcr.io/jensgiehl/weiterfuehrende-schule:latest
```

`8089` ist der Port am Host, `8080` der Port im Container. Unter `http://localhost:8089/actuator/health` erreichst du den Prozessstatus. Das Volume `laura-schule-data` enthält die Datenbank und das Seitenarchiv. Verwende für Updates weiterhin dieses Volume. Für einen Host-Ordner kannst du stattdessen `--mount type=bind,source="$(pwd)/data",target=/app/data` verwenden. Lege den Ordner vorher an und gib der Container-UID `10001` Schreibzugriff.

Für einen lokalen Build kannst du `docker build -t laura-schule:local .` ausführen und das Skript mit `--pull=never` und `laura-schule:local` verwenden.

## Zustand und Archiv

Lokal landen die Daten standardmäßig in `./data`. `MONITOR_DATA_DIR` legt den Ordner fest. Die eingebettete H2-Datenbank `monitor.mv.db` speichert aktive Quellen, erkannte Beiträge, ausstehende Archivierungen und Nachrichten sowie den Telegram-Abfragestand. Die Datenbank läuft im Anwendungsprozess und braucht keinen eigenen Dienst.

Beim ersten Start mit der neuen Version übernimmt die Anwendung eine vorhandene `state.json` aus demselben Datenordner in die Datenbank. Belasse beim Update das bisherige Volume beziehungsweise den bisherigen Host-Ordner. Für eine Sicherung stoppe den Container und kopiere den gesamten Datenordner einschließlich Archiv.

Wenn Telegram vorübergehend nicht erreichbar ist, versucht die Anwendung die Zustellung erneut. Bei einem Abbruch genau zwischen Telegram-Zustellung und lokalem Speichern ist eine doppelte Nachricht möglich.

Jeder erfasste Beitrag landet unter `archive/<Schule>/<Revision>/`. Öffne `index.html` für die Offline-Ansicht. Das Archiv enthält die Originalantwort, statisch referenzierte Ressourcen wie Bilder, CSS, Schriftarten, Medien, eingebettete Seiten und verlinkte Dokumentdateien. Das `manifest.tsv` hält fehlgeschlagene Ressourcenabrufe fest. Die Offline-Ansicht führt kein JavaScript aus. Inhalte, die erst durch JavaScript geladen werden, fehlen. Pro Archiv gelten Grenzen von 200 Ressourcen, 32 MiB je Antwort, 128 MiB insgesamt und zwei Minuten Abrufzeit.
