# IZVJEŠTAJ — Faza 2: self-healing, uloge, koordinator, battery-aware metrika (2026-10-06)

## (1) Šta je urađeno

- **Heartbeat/timeout**: periodični HELLO (ttl=1, link-local, nikad relay), timeout 35 tickova →
  izbacivanje iz `neighbors` + invalidacija svih ruta preko mrtvog hop-a.
- **Lease-based koordinator** (`LeaseTracker`, čisti Kotlin): viši epoch pobjeđuje, tie → manji id,
  liveness preko `coordSeq` (napreduje samo kod izvora). Mreža radi bez koordinatora po dizajnu.
- **Battery-aware metrika**: HELLO nosi bateriju/punjenje/noRelay; trošak linka =
  gubitak×100 + (100−baterija)/10 + (noRelay ? 1000 : 0); greedy min-cost izbor sljedećeg hop-a;
  kritična baterija (<15%, ne puni se) ne relayuje tuđi ne-SOS saobraćaj (SOS uvijek prolazi).
- **Uloge**: `roles()` = Messenger (uvijek) + Relay (ako može) + Coordinator (ako izabran);
  GPS/Gateway/Storage/Voice tek iz Capability Probe (Faza 4).
- Popravljena 4 bug-a (#5 NodeId referentni equals; #6 self-lease inflacija epohe;
  #7 lease isticao na stabilnom koordinatoru; #8 re-advertise držao mrtvog koordinatora živim).

## (2) Rezultati testova

- `gradle :core-mesh:test` → **9/9 JUnit** [TESTIRANO-UNIT] (codec, dedup, rate-limit, hello codec,
  lease tie-break/expiry/stale-copy, android-import gate).
- `gradle :sim:run` → **16/16 scenarija** [TESTIRANO-SIMULATOR], staro + novo:
  - `coord/initial-single`: 6 čvorova → 1 koordinator (K4 = min-hash, ne min-ime — dokumentovano).
  - `coord/kill-reelect`: kill K4 → novi jedini koordinator K3, bez particije mozga.
  - `coord/partition-merge`: particija → privremeno 2 (dozvoljeno), merge → opet 1;
    3/3 poruke tačno 1x, 0 gubitaka, 0 duplikata.
  - `heal/recovery`: utišani relay → oporavak preko C za 19 tickova, ruta invalidirana.
  - `battery/gate`: bulk zaustavljen (`battery-skip`), SOS prošao.
  - `battery/prefer`: ruta do D ide preko C (90%) umjesto B (10%).

## (3) Ograničenja i netestirane pretpostavke

- Greedy metrika je **po hopu, ne end-to-end optimum** (frame nema polje akumuliranog troška —
  svjesna odluka za v1; prava path-metrika tek kad mjerenja pokažu da greedy nije dovoljan).
- Pragovi NEVALIDIRANI: hello 10, timeout 35, lease 60, baterijski prag 15%.
- Gubitak linka u metrici puni sim; pravi `linkQuality` dolazi s LinkManagerom (Faza 5).
- Koordinator u v1 ne radi ništa funkcionalno (zastavica za buduće faze) — mreža ga ne treba.
- I dalje: bez uređaja, bez BLE/Wi-Fi, bez kripto. Sve [NETESTIRANO] izvan simulatora.

## (4) Sljedeći korak

Faza 3: identitet (ključni par, nodeId iz ključa), potpis na frame, hop-by-hop + E2E enkripcija
(lazysodium/Noise — odluka iz DECISIONS.md se tada potvrđuje), replay zaštita, rate limit;
`docs/THREAT_MODEL.md` (šta je pokriveno, šta nije).
