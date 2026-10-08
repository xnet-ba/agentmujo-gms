<div align="center">

# 📡 AgentMujoGMS v1

<h3>Offline mesh network stack za obične, nerutovane Android telefone</h3>
<p><b>Bez interneta. Bez mobilne mreže. Bez Google servisa. Bez servera.</b></p>

![status](https://img.shields.io/badge/faza-4a%20od%209-yellowgreen)
![ci](https://github.com/xnet-ba/agentmujo-gms/actions/workflows/ci.yml/badge.svg)
![test](https://img.shields.io/badge/testovi-56%2F56-brightgreen)
![kotlin](https://img.shields.io/badge/kotlin-2.0.21-blue)
![gms](https://img.shields.io/badge/play--services-ne_treba-red)
![android](https://img.shields.io/badge/android--imports_u_core--mesh-0-green)

</div>

---

<h2>🎯 Šta je ovo</h2>

<p><b>AgentMujoGMS</b> povezuje postojeće, neizmijenjene Android telefone u autonomnu mrežu koja radi potpuno offline.
Cilj nije samo messenger — gradimo <b>network stack</b> (biblioteka + foreground servis), a chat, SOS, mapa, glas i fajlovi su servisi iznad njega.
Ova verzija je temelj za kasniji <b>AgentMujoGMS OS</b>, zato je mesh logika <b>čist Kotlin modul bez ijednog Android importa</b> (provjereno greppom + unit gateom) — kasnije se koristi nepromijenjen.</p>

<details>
<summary><b>🧱 Arhitektura slojeva — klikni za tabelu</b></summary>
<table>
<tr><th>Sloj</th><th>Naziv</th><th>Status</th><th>Opis</th></tr>
<tr><td>7</td><td>Servisi (chat, SOS, mapa, PTT, fajlovi)</td><td>🔶 chat+SOS+fajlovi jezgro (PC)</td><td>Grupni chat, SOS s potvrdama, file transfer s resumeom; Android UI kasnije; nikad direktno na transport</td></tr>
<tr><td>6</td><td>Network API (bound service, AIDL)</td><td>🔶 AIDL + servis se kompajlira</td><td><code>IMujoMesh</code>: status/chat/SOS/poll; vezivanje na uređaju NETESTIRANO</td></tr>
<tr><td>5</td><td>Role manager + Capability Probe</td><td>🔶 model gotov</td><td>Uloge: Coordinator, Relay, Messenger, GPS, Gateway, Storage, Voice</td></tr>
<tr><td>4</td><td>Security / Identity</td><td>✅ Faza 3</td><td>Ed25519 potpis, E2E box, replay vrata — pure-JDK, bez servera</td></tr>
<tr><td><b>3</b></td><td><b>Mesh / Routing (čisti Kotlin)</b></td><td><b>✅ Faza 1–2</b></td><td><b>Flooding+dedup+TTL, unicast rute, ACK/retry, store-and-forward, prioriteti, heartbeat/timeout, lease-koordinator, battery metrika</b></td></tr>
<tr><td>2</td><td>Link manager</td><td>🔶 jezgro gotovo (PC)</td><td>Izbor transporta po klasi saobraćaja + fallback; mjerenje na uređajima čeka hardver</td></tr>
<tr><td>1</td><td>Transporti (BLE, Aware, Direct, LAN)</td><td>🔶 BLE se kompajlira + demo APK</td><td><code>BleTransport</code> iza <code>LinkTransport</code>; ponašanje na uređaju NETESTIRANO (čeka hardver)</td></tr>
</table>
</details>

<details>
<summary><b>📦 Protokol v1 (binarni frame) — klikni za detalje</b></summary>
<pre>
 ver | type | msgId |  src  |  dst  | edPub |  xPub | prio | TTL |hops|flags| tstamp | seq | payLen | payload || sigLen+sig
 1B  |  1B  |  16B  |  32B  |  32B  |  32B  |  32B  |  1B  | 1B  | 1B | 1B  |   8B   |  4B |   2B   |   var   ||  2B + 64B
</pre>
<ul>
<li><b>Tipovi:</b> DATA_UNICAST, FLOOD_BROADCAST, SOS_BROADCAST, ACK, HELLO (ttl=1, link-local), ROUTE_HINT</li>
<li><b>Identitet:</b> nodeId = sha256(Ed25519 ključa); ključevi putuju u frameu (multi-hop bez servera); rotacija → drop do uparivanja</li>
<li><b>Potpis obavezan</b> (nepotpisan frame se dropa); E2E box za unicast (X25519+HKDF+ChaCha20-Poly1305)</li>
<li><b>Prioriteti:</b> SOS &gt; hitno &gt; normalno &gt; bulk — SOS preempcija, bulk anti-starvation (≥1/16), SOS rate limit 4/60 po identitetu</li>
<li><b>Satovi nisu sinhronizovani</b> — timestamp samo za starost, nikad za redoslijed; replay štiti monoton seq (prozor 8)</li>
<li><b>Idempotentna isporuka</b> — isti <code>(src, msgId)</code> aplikaciji max 1x</li>
<li>Puno u <a href="docs/PROTOCOL.md">docs/PROTOCOL.md</a> · Prijetnje u <a href="docs/THREAT_MODEL.md">docs/THREAT_MODEL.md</a></li>
</ul>
</details>

<details>
<summary><b>🧪 Testovi — klikni za matricu (sve prolazi)</b></summary>
<table>
<tr><th>Komanda</th><th>Opseg</th><th>Rezultat</th></tr>
<tr><td><code>gradle :core-mesh:test</code></td><td>12 JUnit: codec, dedup, rate-limit, potpis/tamper, seal/open, lease, fuzz dekodera (2000 mutacija), android-import gate</td><td>✅ 12/12</td></tr>
<tr><td><code>gradle :transport-api:test</code></td><td>5 JUnit: loopback send/receive, probe modeli, izbor transporta po klasi saobraćaja, fallback, android-import gate</td><td>✅ 5/5</td></tr>
<tr><td><code>gradle :service-chat:test</code></td><td>3 JUnit: grupni chat, SOS potvrde, SOS odustajanje nakon 10 pokušaja</td><td>✅ 3/3</td></tr>
<tr><td><code>gradle :service-files:test</code></td><td>3 JUnit: transfer 1:1, resume kroz gubitke, kompresija</td><td>✅ 3/3</td></tr>
<tr><td><code>gradle :service-web:test</code></td><td>4 JUnit: status/chat/SOS endpointi, samo loopback</td><td>✅ 4/4</td></tr>
<tr><td><code>gradle :agent:test</code></td><td>5 JUnit: profili, baterija off/on, rate limit, izolacija, rad bez agenta</td><td>✅ 5/5</td></tr>
<tr><td><code>gradle :service-loc:test</code></td><td>2 JUnit: haversine, keš politika (stale/min-move)</td><td>✅ 2/2</td></tr>
<tr><td><code>gradle :sim:run</code></td><td>21 deterministički scenarij (seedovi 11–110)</td><td>✅ 21/21</td></tr>
</table>
<p>Simulator pokriva: nestanak relaya, partition/merge (0 duplikata, 0 gubitaka), SOS preempciju (4 ticka),
broadcast oluju (20 čvorova tačno-1x), idempotentnost, skalu (24 čvora, gubici 5%), kill koordinatora,
oporavak rute (19 tickova), battery gate/prefer, E2E bez curenja, spoof/replay drop.</p>
</details>

<details>
<summary><b>🗺️ Faze projekta — klikni za status</b></summary>
<p>✅ 0 Dizajn &nbsp; ✅ 1 Simulator-osnova &nbsp; ✅ 2 Self-healing + koordinator &nbsp; ✅ 3 Security &nbsp; 🔶 4a Transport API + Probe model (PC) &nbsp; ⏳ 4-device BLE na hardveru (blokirano na uređajima) &nbsp; ⏳ 5 Link manager &nbsp; ⏳ 6 API+chat+SOS &nbsp; ⏳ 7 Mape/fajlovi/PTT &nbsp; ⏳ 8 Agent+profili &nbsp; ⏳ 9 HW API + OS portabilnost</p>
<p>Izvještaji: <a href="docs/PHASE1_REPORT.md">Faza 1</a> · <a href="docs/PHASE2_REPORT.md">Faza 2</a> · <a href="docs/PHASE3_REPORT.md">Faza 3</a> · Arhitektura: <a href="docs/ARCHITECTURE.md">ARCHITECTURE</a> · Odluke: <a href="docs/DECISIONS.md">DECISIONS</a></p>
</details>

<h2>🚀 Build</h2>

<pre>
# potreban JDK 17+ (gradle koristi onaj koji ga pokreće)
gradle :core-mesh:test :transport-api:test :service-chat:test :service-files:test :service-web:test :service-loc:test :agent:test   # unit (34)
gradle :sim:run                              # simulator acceptance (19 scenarija)
gradle :transport-ble:assembleDebug :app-demo:assembleDebug  # APK (traži Android SDK, vidi docs/ANDROID_TOOLCHAIN.md)
</pre>
<p>Testirano: kotlinc 2.0.21, Temurin JDK 21, Gradle 8.10.2, Linux x86_64.</p>

<h2>⚠️ Iskrena ograničenja</h2>
<ul>
<li>[TESTIRANO-UNIT] i [TESTIRANO-SIMULATOR] samo — <b>nema testova na stvarnim uređajima</b> ([TESTIRANO-UREĐAJ] tek s hardverom)</li>
<li>Svi pragovi su <b>NEVALIDIRANI</b>: TTL 16, retry 4x, SOS 4/60, store kvota 64, dedup cap 4096, replay prozor 8, RX 64</li>
<li>Metapodaci vidljivi relayu; prva poruka u smjeru plaintext; TOFU bez verifikacije — <b>ne tvrdimo da je sistem siguran</b> (vidi THREAT_MODEL.md)</li>
<li>Mreža u v1 je realno "BLE discovery + Wi-Fi za veće podatke", ne idealan mesh; Android kripto API nivoi neprovjereni</li>
</ul>
