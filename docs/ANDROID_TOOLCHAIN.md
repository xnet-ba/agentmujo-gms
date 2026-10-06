# ANDROID TOOLCHAIN (reprodukcija)

Status Android modula: [KOMPAJLIRA-SE] ✅ / ponašanje [NETESTIRANO] (nema uređaja).

## Šta je instalirano (lokalno, NIJE dio repoa)
- Android cmdline-tools 11076708 + `platforms;android-34` + `build-tools;34.0.0`
- Temurin JDK 21, Gradle 8.10.2, kotlinc 2.0.21, AGP 8.5.2
- `local.properties` (gitignorean) pokazuje na lokalni SDK: `sdk.dir=/tmp/kt/android`

## Reprodukcija na drugoj mašini
1. JDK 17+, Gradle 8.10+, Android SDK (cmdline-tools → prihvati licence → instaliraj gore navedeno).
2. Napravi `local.properties` s `sdk.dir=<tvoj SDK>`.
3. `gradle :transport-ble:assembleDebug :app-demo:assembleDebug`
4. JVM moduli ne traže SDK: `gradle :core-mesh:test :transport-api:test :service-chat:test :agent:test :sim:run`

## Šta Android moduli sadrže
- `:transport-ble` — `BleTransport : LinkTransport` (GATT server+client, scan+advertise, MTU pregovori).
  API nivoi: minSdk 26, target/compile 34. Dozvole po API nivou (S+ vs starije) su razgranate u kodu.
- `:app-demo` — `MainActivity` (dozvole → start servisa) + `MeshService` (foreground, `connectedDevice` tip).
  AIDL binding + mesh wiring = Faza 6.

## Šta se čeka od hardvera (Faza 4-device, blokirano)
BLE advertising/scanning domet i throttling, MTU u praksi, OEM ubijanje servisa,
potrošnja, preživljavanje u pozadini — Capability Probe (`FakeProbe` zasad) to mjeri na uređaju.
