# IZVJEŠTAJ — Faza 0 + Faza 1 (2026-10-06)

## (1) Šta je urađeno

- `docs/ARCHITECTURE.md`, `docs/DECISIONS.md`, `docs/PROTOCOL.md` (frame v1 bez potpisa).
- Gradle multi-module: `:core-mesh` (čisti Kotlin, 0 Android importa — provjereno greppom + unit gate) + `:sim`.
- `core-mesh`: binarni frame codec (strogi drop: ver/tip/dužina), kontrolisani flooding + dedup + TTL,
  unicast s path-learning tabelom + fallback na ograničeni flood, ACK s prosljeđivanjem preko relaya,
  retry s eksponencijalnim backoffom + eskalacijom na flood nakon 2 neuspjeha,
  store-and-forward s kvotom (prioritet+starost evikcija, flush na promjenu susjeda),
  SOS preempcija + anti-starvation bulk-a (≥1/16) + SOS rate limit 4/60 po identitetu.
- `sim`: deterministička mreža (seedovi 11/22/33/44/55/77, gubici/latencija/partition/kill) + 7 scenarija.
- Pronađena i popravljena 4 buga (redom): gutanje tuđeg ACK-a na relayu; retry bez rute;
  retry/ACK utrka u istom ticku; relay bez storea u particiji. Svi uzroci dokumentovani u kodu.

## (2) Rezultati testova

- `gradle :core-mesh:test` → 5/5 JUnit [TESTIRANO-UNIT].
- `gradle :sim:run` → 9/9 scenarija, deterministički seedovi [TESTIRANO-SIMULATOR]:
  relay-fail, partition/merge (0 duplikata, 0 izgubljenih), SOS latencija 4 ticka uz zasićenje,
  broadcast 20 čvorova (svaki tačno 1x, bez reflooda), idempotentnost, codec, skala 24 čvora + SOS svima.
- Alatni lanac: kotlinc 2.0.21, Temurin JDK 21, Gradle 8.10.2 (sve u /tmp — NIJE dio repoa; ponoviti `gradle :core-mesh:test :sim:run`).

## (3) Ograničenja i netestirane pretpostavke [sve NETESTIRANO izvan simulatora]

- Nema Android uređaja, nema BLE/Wi-Fi koda, nema kriptografije (potpis dolazi u Fazi 3 — frame ga još nema na žici).
- Pragovi NEVALIDIRANI: TTL max 16, retry 4x/backoff 5, anti-starvation N=16, SOS 4/60, store kvota 64, dedup cap 4096.
- Pretpostavka: tick ≈ slobodna apstraktna jedinica; stvarne latencije/gubici BLE/Wi-Fi nepoznati do Faze 4.
- `NodeId` nema content-equals (radi jer sim koristi kanonske instance) — TODO prije Androida.
- `core-security`, `transport-*`, `service-*`, `app-demo` NE POSTOJE (namjerno: ništa unaprijed; dolaze u svojim fazama).
- Mreža radi BEZ koordinatora po dizajnu; izbor koordinatora je Faza 2.

## (4) Sljedeći korak

Faza 2: self-healing (heartbeat/timeout, invalidacija ruta, mjerenje vremena oporavka), uloge + lease-based
koordinator, battery-aware metrika — sve u simulatoru s novim scenarijima (kill koordinatora, partition/merge
bez duplih koordinatora). Ne počinjati Fazu 3 dok Faza 2 nema prolazne testove.
