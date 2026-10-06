# IZVJEŠTAJ — Faza 3: Security (2026-10-06)

## (1) Šta je urađeno

- **Identitet**: Ed25519 + X25519 par, nodeId = sha256(edPub), bez servera. Deterministički iz sjemena (sim).
- **Kripto izbor**: pure-JDK provideri (SunEC/SunJCE) + HKDF-SHA256 kompozicija — bez JNI, bez novih
  zavisnosti (obrazloženje + Android TODO u DECISIONS.md t.8).
- **Potpis**: Ed25519 nad nepromjenjivim poljima, OBAVEZAN na svakom frameu; ključevi putuju u frameu
  (multi-hop bez key-servera); vezanost id=sha256(ključ) + kontinuitet ključa (rotacija → drop).
- **E2E**: sealed box za unicast kad je ključ poznat, inače plaintext + `no-e2e-key` brojač (nikad tiho).
  SOS namjerno plaintext+potpisan.
- **Replay**: monoton seq, prozor 8; RX budget 64/tick/susjed protiv flooda.
- `docs/THREAT_MODEL.md`: pokriveno vs. poznata ograničenja (metapodaci, TTL-tamper, prva-poruka-plaintext,
  reboot-seq, TOFU bez verifikacije, bez PQC). Sistem se NE tvrdi sigurnim.
- Popravljena 2 bug-a (#9 xPriv/xPub iz različitih parova; #10 zajednički seq HELLO+DATA rusio replay vrata).

## (2) Rezultati testova

- `gradle :core-mesh:test` → **11/11 JUnit** [TESTIRANO-UNIT] (novo: signVerifyTamper, sealOpenWrongKey,
  hello codec s ključevima, lease stale-copy).
- `gradle :sim:run` → **19/19 scenarija** [TESTIRANO-SIMULATOR] (novo: `e2e/chat` plaintext 1x bez curenja
  na relayu; `spoof/drop` id-spoof+bad-sig; `replay/drop` star drop + reorder-tolerancija).
- Ukupno: **30/30 zeleno**. Napomena: popravak #10 spustio `scale` missing 16→1 (hello/data trka je
  ranije tiho jela poruke i kod 5% gubitaka — uhvaćeno tek security vratima).

## (3) Ograničenja i netestirane pretpostavke

- Vidi THREAT_MODEL.md (metapodaci, TTL-tamper, prva poruka plaintext, reboot, TOFU, PQC).
- Android Conscrypt API nivoi neprovjereni; SpongyCastle fallback samo zapisan, ne implementiran.
- Pragovi i dalje NEVALIDIRANI (prozor 8, RX 64, kvote). Bez uređaja, bez BLE/Wi-Fi.

## (4) Sljedeći korak

Faza 4: BLE transport na stvarnim uređajima + Capability Probe + foreground servis; izvještaj
domet/pouzdanost/potrošnja/preživljavanje po OEM-u. Zahtijeva hardver — blokira se na testnim uređajima.
