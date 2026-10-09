# Tinnisoft

Android-app (Kotlin, Jetpack Compose) die ruis en geluiden genereert om tinnitus (oorsuizen) te maskeren.
Werkt volledig offline: geen account, geen tracking, geen advertenties.

> Tinnisoft is geen medisch hulpmiddel en vervangt geen advies van een huisarts, KNO-arts of audioloog.

## Installeren (zonder Play Store)

1. Open **Releases** (of het **Actions**-tabblad › laatste run › *Artifacts*) en download `Tinnisoft-x.y.z.apk`.
2. Open het bestand op je telefoon en sta *Installeren uit onbekende bronnen* toe voor je browser/bestandsbeheerder.
3. Updates: installeer een nieuwere APK over de oude heen (moet met dezelfde sleutel ondertekend zijn, zie hieronder).

## Functies

- Realtime ruis (AudioTrack, PCM float, 48 kHz, stereo, eigen audio-thread) – nooit een hoorbare loop:
  wit, roze, bruin, blauw, violet en grijs (psycho-akoestisch gelijk luid)
- **Notched noise** rond je tinnitusfrequentie (250 Hz–16 kHz, breedte instelbaar, standaard 1 octaaf)
- **Frequentie-matcher** (sinus of smalband, grof + fijn, octaaf-check, start altijd zacht)
- Smalband-ruis op de tinnitusfrequentie
- Natuurgeluiden: regen, golven, beek, wind, krekels, ventilator
- Mixer met max. 3 lagen, 8-bands equalizer, trage amplitude-modulatie, balans L/R
- Harde volumegrens op app-niveau (apart voor koptelefoon en luidspreker), minimumvolume, fades,
  waarschuwing bij hoog volume, dagelijkse luistertijdlimiet, pincode-slot, veilig volume na apparaatwissel
- Bluetooth: huidig apparaat zichtbaar, mediaknoppen, direct pauzeren bij loskoppelen, optioneel automatisch hervatten,
  audiofocus (ducken/pauzeren en hervatten), grotere buffers als latency niet uitmaakt
- Foreground media-service (Media3) met melding, vergrendelscherm-bediening, slaaptimer, planning, hervatten na herstart
- Presets, startscherm-widget, Quick Settings-tegel, logboek met grafiek

### Natuurgeluiden

De natuurgeluiden worden standaard **realtime gesynthetiseerd** (dus nooit een herhaling en geen licentiekwesties).
Wil je echte opnames gebruiken: zet een CC0-bestand in `app/src/main/assets/nature/` met de naam
`rain`, `waves`, `brook`, `wind`, `crickets` of `fan` (bijv. `rain.ogg`, minimaal 6 s). De app decodeert het,
normaliseert het en maakt er een naadloze loop van met een crossfade van 3 s.

## Bouwen

```bash
./gradlew testDebugUnitTest   # unit tests (spectrum van de ruisgeneratoren, volumebegrenzer, mixer)
./gradlew assembleRelease     # app/build/outputs/apk/release/app-release.apk
```

### Ondertekenen

Maak één keer een keystore aan en **bewaar die goed** (kwijt = geen updates meer mogelijk):

```bash
keytool -genkeypair -v -keystore tinnisoft.jks -alias tinnisoft -keyalg RSA -keysize 4096 -validity 10000
```

Lokaal: maak `keystore.properties` in de projectmap (staat in `.gitignore`):

```properties
storeFile=tinnisoft.jks
storePassword=...
keyAlias=tinnisoft
keyPassword=...
```

GitHub Actions: voeg onder *Settings › Secrets and variables › Actions* toe:
`KEYSTORE_BASE64` (`base64 -w0 tinnisoft.jks`), `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.
Zonder secrets wordt de APK met een tijdelijke debug-sleutel ondertekend.

### Release publiceren

```bash
git tag v1.0.0 && git push origin v1.0.0
```

De workflow bouwt de APK en zet hem als download bij een GitHub Release.

## Toestemmingen

| Toestemming | Waarvoor |
|---|---|
| `POST_NOTIFICATIONS` | mediamelding, meldingen (limiet, hervatten) |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | doorspelen op de achtergrond / scherm uit |
| `WAKE_LOCK` | CPU wakker houden tijdens afspelen |
| `BLUETOOTH_CONNECT` | alleen de naam van verbonden oordopjes tonen |
| `RECEIVE_BOOT_COMPLETED` | planning herstellen en optioneel hervatten na herstart |
| `SCHEDULE_EXACT_ALARM` | planning op de minuut nauwkeurig (gebruiker geeft toestemming) |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | één-tik-dialoog voor de accu-uitzondering |

Geen internettoegang.
