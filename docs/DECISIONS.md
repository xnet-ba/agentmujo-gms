# DECISIONS (Faza 0) — svaka s obrazloženjem; NEVALIDIRANO dok nema mjerenja

1. Jezik: Kotlin za sve module. Zašto: jedan jezik za core + Android, coroutines za link manager, kasnije prenosivo u OS verziju.
2. Network API: Android bound service (AIDL) — NE loopback HTTP. Zašto: bez TCP stack overhead-a, tipizirane metode, perzistira kroz procese, radi bez mrežnog stoga. Loopback HTTP odbačen: port-konflikti + firewall/OEM + nepotreban HTTP overhead za IPC. [NETESTIRANO — odluka se revidira u Fazi 6 ako AIDL postane problem]
8. Kripto (Faza 3, potvrđeno): PURE-JDK provideri — Ed25519/X25519 (SunEC), ChaCha20-Poly1305 (SunJCE),
HKDF-SHA256 složen od JCE Hmac (RFC 5869 konstrukcija, dokumentovana). Zašto ne lazysodium/Noise lib:
bez JNI/native .so (prenosivo na OS verziju, radi na JRE-u bez nativnog koda),
nula novih zavisnosti (supply-chain), a primitivi su audited implementacije JDK providera —
ne pišemo svoje primitve, samo ih komponujemo (box + potpis + sekvence).
TODO (otvoreno pitanje): provjeriti Ed25519/X25519/ChaCha20 dostupnost na Android API 26+ (Conscrypt);
nisam siguran za minimalni API — validacija u Fazi 4 na stvarnom uređaju, fallback je SpongyCastle iza interfejsa.
4. Koordinator: lease-based s heartbeatom (ne puni Raft). Zašto: mreža MORA raditi bez koordinatora; lease ističe sam pa nema trajno dupliranih koordinatora nakon merge-a ako se lease provjerava uz viši epoch. Bully odbija jer generira oluje poruka na nestabilnim linkovima. [NEVALIDIRANO, test u Fazi 2: kill koordinatora, partition/merge]
5. Mapa: MapLibre (fork Mapbox, offline tile paketi, bez GMS) — kandidat; osmdroid alternativa ako MapLibre APK postane pretežak. Odluka u Fazi 7 nakon mjerenja veličine APK-a. Distribucija tile-ova kroz mesh kao bulk transfer niskog prioriteta s kvotom.
6. Glas: Opus kodek. Koliko hopova upotrebljivo — MJERI SE u Fazi 7, ne tvrdi se unaprijed.
7. Fragmenacija za MTU ~50B (LoRa): protokol nosi fragId/fragIndex/totalFrags u flags-ekstenziji — dizajn u Fazi 1, implementacija u Fazi 9 s LoRa mockom.

## Blokirajuća pitanja za korisnika: NEMA (Faza 0/1 ne treba odluke).
Sve iznad je reverzibilno i označeno za validaciju.
