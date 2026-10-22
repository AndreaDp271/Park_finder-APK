# ParkMap per Android

App nativa di [ParkMap](https://andreadp271.github.io/Park_finder/): trova aree di sosta, stalli speciali, colonnine di ricarica, parcheggi per moto e bici e parcometri con i dati di OpenStreetMap.

**Scarica l'APK:** [ultima release](https://github.com/AndreaDp271/Park_finder-APK/releases/latest). L'app si aggiorna da sola dalla stessa pagina.

Per computer e iPhone c'è la [versione web](https://andreadp271.github.io/Park_finder/), che resta la versione completa per chi non ha Android.

## Funzioni

- **Mappa** OpenStreetMap con parcheggi colorati per costo (pagamento, gratuito, disco orario, accesso limitato), filtri (gratis adesso, gratuiti, disco orario, disabili, ricarica EV, moto, bici) e stili Standard, Umanitario, Ciclabile, Rilievo, Grigio, Notte, Satellite e Ibrida
- **Scheda del parcheggio**: costo adesso, fasce orarie di pagamento, tariffa, limiti di sosta, accesso, modalità di pagamento, prese e potenza delle colonnine, **costo stimato** per la durata scelta
- **Ricerca** di luoghi e indirizzi in tutto il mondo; scegliendo un indirizzo si apre l'elenco dei parcheggi ordinato per distanza a piedi
- **Vicini e preferiti**, salvati solo sul telefono
- **La mia auto**: posizione, nota, foto, promemoria 10 minuti prima della scadenza e alla scadenza, storico delle soste, navigazione a piedi, condivisione
- **Bluetooth dell'auto**: quando il telefono si scollega l'auto si salva da sola (anche con l'app chiusa), quando si ricollega la sosta finisce
- Widget "La mia auto" e scorciatoie dell'icona (ho parcheggiato, la mia auto, parcheggi vicini)
- Segnalazione di errori e parcheggi mancanti come note su OpenStreetMap
- Link della web app (`?lat=…&lon=…`, `?car=…`) e `geo:` aperti direttamente dall'app
- Italiano e inglese, tema chiaro/scuro, colori dallo sfondo (Material You, Android 12+)
- Nessun servizio Google, nessuna pubblicità, nessun account

## Da dove arrivano i dati

1. **Dati precalcolati** pubblicati ogni notte con la web app ([`scripts/build_tiles.py`](https://github.com/AndreaDp271/Park_finder/blob/main/scripts/build_tiles.py)): `https://andreadp271.github.io/Park_finder/data/` (copertura: Italia)
2. **Postpass** (Geofabrik), in tempo reale, fuori dall'area coperta o se i file non rispondono
3. **Overpass** (cinque mirror interrogati in parallelo) come riserva

Le tessere da 0,01° scaricate restano sul telefono (fino a 400) e si aggiornano dopo 6 ore. La ricerca usa Nominatim e Postpass. Dati © OpenStreetMap contributors, [ODbL](https://www.openstreetmap.org/copyright).

## Compilazione

Serve JDK 17 e l'Android SDK (platform 35).

```sh
./gradlew assembleDebug                  # app/build/outputs/apk/debug/
./gradlew assembleRelease                # firmata solo se esiste keystore.properties
./gradlew testDebugUnitTest              # regole OSM, parser, confronto versioni
LIVE_API=1 ./gradlew testDebugUnitTest   # anche su dati veri (celle statiche, Postpass, Overpass)
```

`keystore.properties` (non versionato) per firmare la release in locale:

```
storeFile=/percorso/release.p12
storeType=pkcs12
storePassword=...
keyAlias=parkmap
keyPassword=...
```

## Pubblicare una versione

1. Aumenta `versionCode` e `versionName` in `app/build.gradle.kts` e scrivi `fastlane/metadata/android/it-IT/changelogs/<versionCode>.txt`.
2. `git tag vX.Y.Z && git push --tags`: il workflow *Release* compila, firma e pubblica `ParkMap-X.Y.Z.apk`.

La firma usa i secret `KEYSTORE_BASE64` (il file `.p12` in base64), `KEYSTORE_PASSWORD`, `KEY_ALIAS` (`parkmap`) e `KEY_PASSWORD`. **La chiave non deve mai cambiare**: l'app ha lo stesso identificativo (`io.github.andreadp271.parkmap`) della vecchia versione basata su Capacitor e si installa sopra solo se è firmata con la stessa chiave.

## Struttura

| Cartella | Contenuto |
|---|---|
| `data/` | regole OSM (orari, filtri, tariffe, costo), caricamento dati a tessere, ricerca, auto e Bluetooth, preferiti, aggiornamenti |
| `ui/` | Jetpack Compose (Material 3): mappa osmdroid, schede, elenco, auto, impostazioni |
| `widget/` | widget "La mia auto" |
| `assets/en.json` | dizionario inglese (le stesse chiavi italiane della web app) |

Kotlin + Jetpack Compose, mappa con [osmdroid](https://github.com/osmdroid/osmdroid).
