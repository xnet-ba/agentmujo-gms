# AgentMujoGMS v1 — ARCHITECTURE (Faza 0)

Status modula u ovom dokumentu: [NETESTIRANO] osim gdje piše drugačije.
Cilj: offline mesh network stack kao APK na nerutovanim telefonima.
Princip: mreža radi bez interneta, bez mobilne mreže, bez GMS-a.

## Slojevi (samo ovim redom komuniciraju)

```
[Servisi: chat/SOS/mapa/glas/fajlovi]  (Sloj 7)
        |
[Network API: bound service, odluka §4] (Sloj 6)
        |
[Role manager + Capability Probe]       (Sloj 5)
[Security / Identity]                   (Sloj 4, Faza 3)
[Mesh / Routing — ČISTI KOTLIN, bez Android importa] (Sloj 3, Faza 1-2)
[Link manager]                          (Sloj 2, Faza 5)
[LinkTransport adapteri: BLE / Aware / Direct / LAN] (Sloj 1, Faze 4-5)
```

Pravila:
- Servisi NIKAD ne pričaju direktno s transportom. Samo preko Network API.
- `core-mesh` ne smije importati `android.*`. Provjera: `grep -r "android\." core-mesh/src` mora biti prazan (CI gate).
- Transporti implementiraju `LinkTransport` (discover, connect, send, receive, linkQuality, isAvailable, costProfile). U Fazi 1 postoji samo `InMemoryLink` za simulator.

## Moduli (Gradle)

- `core-mesh` — čisti Kotlin/JVM: frame, flooding+dedup+TTL, unicast rute, ACK/retry, store-and-forward, prioriteti. [Faza 1]
- `core-security` — skeleton u Fazi 1, implementacija u Fazi 3 (libsodium/lazysodium ili Noise — odluka u DECISIONS.md).
- `transport-*` — skeletoni do Faze 4/5 (`transport-api` interfejs + `transport-ble`, `transport-wifi`).
- `service-*`, `app-demo` — skeletoni do Faze 6.
- `sim` — JVM simulator na `core-mesh`-u: N virtualnih čvorova, gubici/latencija/nestanak/partition. [Faza 1]

## Protokol (sažetak, puno u docs/PROTOCOL.md)

Binarni frame: version(1B) | msgType(1B) | messageId(16B) | src(32B) | dst(32B/grupni/broadcast) |
priority(1B) | TTL(1B) | hopCount(1B) | timestamp(8B, samo relativno/causal, satovi NISU sinhronizovani) |
flags(1B) | payloadLen(2B) | payload | potpis (Faza 3, u Fazi 1 prazan + TODO).
MTU: protokol mora podnijeti fragmentaciju do par desetina bajtova (LoRa priprema, Faza 9). U Fazi 1 fragmentacija NIJE implementirana — TODO.

## Routing v1 (namjerno jednostavno)

- Broadcast/SOS: kontrolisani flooding + seen-dedup + TTL. Bez spanning-tree u v1.
- Unicast: tabela sljedeći-hop učena iz viđenih frame-ova (path-learning, ideja iz OLSR/BATMAN uzeta minimalno: samo "tko me zadnji čuo bliže"), fallback na flooding s ograničenim TTL ako rute nema.
- BATMAN/AODV/OLSR puna metrika TEK nakon što osnova prođe simulator (Faza 2). Zašto: jednostavno se da testirati deterministički; metrika bez mjerenja je nagađanje.
- Self-healing (Faza 2): heartbeat/timeout, invalidacija, mjerenje vremena oporavka. Faza 1 ima samo detekciju mrtvog susjeda preko simuliranog gubitka linka.

## Pozadinski rad (upozorenje, Faza 4 mjeri)

Foreground service + notifikacija. OEM-ubijanje procesa, Doze, BLE-scan throttling i Wi-Fi Direct dijalozi su poznate nepoznanice — Capability Probe ih mjeri u runtime-u. Ništa se ne tvrdi dok se ne izmjeri na uređaju.
