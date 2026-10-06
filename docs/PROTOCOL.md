# PROTOCOL v1 (Faza 3: potpis + seq + ključevi; nepotpisani frameovi iz Faze 1 se DROP-aju — bio je sim-only format, bez kompatibilnosti)

```
 0      1      2        18       50       82      114      146      147   148   149   150      158     162       164        payloadLen+164
+------+------+--------+--------+--------+--------+--------+-------+-----+-----+-----+--------+-------+---------+-----------++------------+
| ver  | type | msgId  |  src   |  dst   | edPub  |  xPub  | prio  | TTL |hops |flags| tstamp |  seq  | payLen  | payload   || sigLen+sig |
|  1B  |  1B  |  16B   |  32B   |  32B   |  32B   |  32B   |  1B   | 1B  | 1B  | 1B  |   8B   |  4B   |   2B    |   var     ||  2B + var  |
+------+------+--------+--------+--------+--------+--------+-------+-----+-----+-----+--------+-------+---------+-----------++------------+
```

Sva polja big-endian. Satovi NISU sinhronizovani: `timestampMs` je samo
pošiljateljev wall-clock za starosno izbacivanje iz store-and-forward i
NE koristi se za redoslijed isporuke (redoslijed = per-(src,messageId)).

```
 0      1      2        18       50       82       83    84    85    86       94      96        98        payloadLen+98
+------+------+--------+--------+--------+-------+-----+-----+-----+--------+-------+---------+-----------+
| ver  | type | msgId  |  src   |  dst   | prio  | TTL | hops|flags| tstamp | payLen| frag*   | payload   |
|  1B  |  1B  |  16B   |  32B   |  32B   |  1B   | 1B  | 1B  | 1B  |  8B    |  2B   | 2B(opt) | var       |
+------+------+--------+--------+--------+-------+-----+-----+-----+--------+-------+---------+-----------+
```

- `ver`=1. Nepoznat ver → drop + brojač (ne ruši čvor).
- `type`: 0x01 DATA_UNICAST, 0x02 FLOOD_BROADCAST, 0x03 SOS_BROADCAST, 0x04 ACK, 0x05 HELLO, 0x06 ROUTE_HINT. Nepoznat tip → drop + brojač.
- `msgId`: 128-bit random. Dedup ključ = (src, msgId). Idempotentna isporuka: isti ključ se aplikaciji predaje max 1x.
- `dst`: za broadcast `0xFF*32`; grupni: `0x00 || sha256(grupa)[0..30]` (prefiks 0x00 rezerviran, dokumentuje kolizijski rizik: 248 bita — prihvatljivo, zapiši kao poznato ograničenje).
- `prio`: 0 SOS, 1 hitno, 2 normalno, 3 bulk. Preempcija: redovi su striktno prioritetni uz anti-starvation (bulk dobija ≥1 slot na svakih N=16 poslanih frame-ova; N je NEVALIDIRANO, mjeri se u simu).
- `TTL`: max 16, svaki relay smanjuje za 1; 0 → drop. `hops` se povećava radi dijagnostike (ne za routing odluke u v1).
- `flags`: bit0 FRAG, bit1 ACK_REQ, bit2 COMPRESSED, **bit3 SEALED (Faza 3: payload je E2E box)**,
  ostalo rezervirano=0.
- `seq` (Faza 3): monoton brojač originatora (svi tipovi dijele brojač, vrata vrijede za DATA/FLOOD/SOS);
  replay prozor 8; Int wrap i reboot-perzistencija su TODO (vidi THREAT_MODEL.md).
- `edPub`/`xPub` (Faza 3): originatorovi ključevi U SVAKOM frameu (multi-hop bez key-servera);
  prijemnik provjerava sha256(edPub)==src + potpis + kontinuitet ključa (rotacija → drop do uparivanja).
- Potpis (Faza 3, OBAVEZAN): `sigLen(2B)+sig(64B Ed25519)` nad kanonskim bajtovima
  (sve osim ttl/hops/sig — relay smije dirati samo ta dva; TTL-tamper je poznato ograničenje).
- E2E box (Faza 3): `ephPub(32B) | nonce(12B) | ct+tag`, X25519+HKDF-SHA256+ChaCha20-Poly1305,
  AAD = src nodeId. Prva poruka u smjeru ide plaintext (ključ se uči iz overheard saobraćaja) + brojač `no-e2e-key`.
- `frag*`: prisutno samo ako FRAG=1: fragIndex(1B)+fragTotal(1B). Implementacija tek Faza 9; v1 čvor FRAG frame s nepoznatim totalom DROP-a i broji.
- Potpis: u Fazi 1 NEMA ga na žici (frame završava payloadom). Faza 3 dodaje `sigLen(2B)+sig(var)` + replay nonce.
- ACK: `type=0x04`, payload = acked (src,msgId), šalje se unicastom prema prethodnom hopu (path-learning unatrag).
- HELLO: `type=0x05`, **ttl=1, link-local, nikad se ne relayuje**, dst=BROADCAST, prioritet URGENT.
  Payload 42B: batt(1B, 0–100) | flags(1B: bit0 charging, bit1 noRelay) | epoch(4B BE) |
  coordSeq(4B BE, napreduje samo kod koordinatora — liveness) | coordId(32B, nule = nijedan).
  Malformiran HELLO → drop + brojač, ne ruši čvor. (Faza 2)
- Rate limit protiv zloupotrebe prioriteta: max 4 SOS/min po src (Faza 1: broji + dropa višak; prag NEVALIDIRAN).

Ograničenja v1: metapodaci (src/dst/hops) vidljivi relayu; nema povjerljivosti do Faze 3.
