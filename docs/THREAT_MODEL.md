# THREAT MODEL — Faza 3 (šta je pokriveno, šta NIJE; sistem se NE tvrdi "sigurnim")

## Pretpostavke o napadaču
- Napadač je u radio dometu: sluša, snima, reinjektira, šalje svoje frameove, relayuje selektivno (zloćudni relay).
- Napadač NEMA privatne ključeve žrtava. Nema globalnog MitM izvan dometa (nema infrastrukture za napad).
- Kvantni napadač van opsega v1 (Ed25519/X25519 nisu PQC — zabilježeno, ne rješava se sad).

## Pokriveno (testirano u simu)
| Prijetnja | Mehanizam | Test |
|---|---|---|
| Spoofing identiteta | nodeId MORA = sha256(edPub) + Ed25519 potpis svakog framea | `spoof/drop` (id-spoof + bad-sig) |
| Tamper sadržaja/rute | potpis nad nepromjenjivim poljima; relay smije dirati samo TTL/hops | `signVerifyTamper` |
| Replay | monoton seq po originatoru, prozor 8 (reorder tolerancija), dedup za tačne kopije | `replay/drop` |
| Čitanje sadržaja na relayu | E2E box (X25519+HKDF+ChaCha20-Poly1305, AAD=src) za unicast | `e2e/chat` (leak=false) + `sealOpenWrongKey` |
| Flood/DoS | RX budget 64/tick/susjed, SOS rate limit 4/60/identitet, prioritetni redovi | `sosRateLimit`, `rx-flood` brojač |
| Nepoznat key-downgrade | `no-e2e-key` brojač svake plaintext isporuke bez ključa (vidljivo, ne tiho) | `e2e/chat` |

## NIJE pokriveno (poznata ograničenja)
- **Metapodaci vidljivi**: src/dst/hops/baterija/koord čitljivi svakom relayu. Nema onion routinga u v1.
- **TTL/hops nisu potpisani** (relay ih mora dirati) — napadač može gasiti frameove (TTL=0) na svom hopu. Ublažava se šifrovanim transportima (Faza 4/5), ne ovdje.
- **Prva poruka u smjeru ide plaintext** dok se ključ ne nauči iz overheard saobraćaja (nema KEY_ADVERT flooda — TODO Faza 5+). Potpisana je, ali čitljiva.
- **Reboot bez perzistencije**: seq kreće od 0, seen-set prazan → peerovi dropaju do seq>last; replay prozor se širi. Treba boot-epoch + perzistentni seen (Faza 6+).
- **TOFU bez verifikacije**: ključ se vjeruje na prvom viđenju; QR/NFC uparivanje + key-change UI tek dolaze (rotacija se zasad DROP-a).
- **SOS namjerno čitljiv svima** (dizajn kompromis: svako mora moći pomoći).
- **Zloćudni relay i dalje može**: dropati (blackhole — ublaženo retry+alternativnim rutama, ne riješeno), lagati o bateriji/rutama, analizirati saobraćaj.
- **Android API nivoi kripto providera neprovjereni** (TODO Faza 4, fallback SpongyCastle iza interfejsa).
- Kvantna otpornost: nema je.
