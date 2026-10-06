<div align="center">

# 📡 AgentMujoGMS v1

<h3>Offline mesh network stack za obične, nerutovane Android telefone</h3>
<p><b>Bez interneta. Bez mobilne mreže. Bez Google servisa. Bez servera.</b></p>

![status](https://img.shields.io/badge/faza-1%20od%209-green)
![test](https://img.shields.io/badge/testovi-14%2F14-brightgreen)
![kotlin](https://img.shields.io/badge/kotlin-2.0.21-blue)
![gms](https://img.shields.io/badge/play--services-ne_treba-red)

</div>

---

<h2>🎯 Šta je ovo</h2>

<p><b>AgentMujoGMS</b> povezuje postojeće, neizmijenjene Android telefone u autonomnu mrežu koja radi potpuno offline.
Cilj nije samo messenger — gradimo <b>network stack</b> (biblioteka + foreground servis), a chat, SOS, mapa, glas i fajlovi su servisi iznad njega.
Ova verzija je temelj za kasniji <b>AgentMujoGMS OS</b>, zato je mesh logika čist Kotlin modul bez ijednog Android importa — kasnije se koristi nepromijenjen.</p>

<table>
<tr><th>Sloj</th><th>Naziv</th><th>Status</th><th>Opis</th></tr>
<tr><td>7</td><td>Servisi (chat, SOS, mapa, PTT, fajlovi)</td><td>⏳ Faza 6–7</td><td>Nikad ne pričaju direktno s transportom</td></tr>
<tr><td>6</td><td>Network API (bound service, AIDL)</td><td>⏳ Faza 6</td><td>Jedina tačka pristupa mreži za servise</td></tr>
<tr><td>5</td><td>Role manager + Capability Probe</td><td>⏳ Faza 2/4</td><td>Uloge: Coordinator, Relay, Messenger, GPS, Gateway, Storage, Voice</td></tr>
<tr><td>4</td><td>Security / Identity</td><td>⏳ Faza 3</td><td>libsodium/Noise, E2E + hop-by-hop, bez servera</td></tr>
<tr><td><b>3</b></td><td><b>Mesh / Routing (čisti Kotlin)</b></td><td><b>✅ Faza 1</b></td><td><b>Flooding+dedup+TTL, unicast rute, ACK/retry, store-and-forward, prioriteti</b></td></tr>
<tr><td>2</td><td>Link manager</td><td>⏳ Faza 5</td><td>Izbor transporta po susjedu i tipu saobraćaja</td></tr>
<tr><td>1</td><td>Transporti (BLE, Aware, Direct, LAN)</td><td>⏳ Faza 4–5</td><td>Iza <code>LinkTransport</code> interfejsa; v1 = BLE discovery + Wi-Fi za veće podatke</td></tr>
</table>

<h2>📦 Protokol v1</h2>

<pre>
 ver | type | msgId |  src  |  dst  | prio | TTL | hops | flags | timestamp | payLen | payload
 1B  |  1B  |  16B  |  32B  |  32B  |  1B   |  1B |  1B  |  1B   |    8B     |   2B   |   var
</pre>
<ul>
<li><b>Tipovi:</b> DATA_UNICAST, FLOOD_BROADCAST, SOS_BROADCAST, ACK, HELLO, ROUTE_HINT</li>
<li><b>Prioriteti:</b> SOS &gt; hitno &gt; normalno &gt; bulk — SOS preempcija, bulk anti-starvation (≥1/16), SOS rate limit 4/60 po identitetu</li>
<li><b>Satovi nisu sinhronizovani</b> — timestamp služi samo starosnoj evikciji, nikad redoslijedu</li>
<li><b>Idempotentna isporuka</b> — isti <code>(src, msgId)</code> aplikaciji max 1x, nema duplo isporučenih poruka</li>
<li>Puno u <a href="docs/PROTOCOL.md">docs/PROTOCOL.md</a>; potpis na žici dolazi u Fazi 3</li>
</ul>

<h2>🧪 Testovi (sve prolazi)</h2>

<table>
<tr><th>Komanda</th><th>Rezultat</th></tr>
<tr><td><code>gradle :core-mesh:test</code></td><td>✅ 5/5 JUnit (codec, dedup, rate-limit, android-import gate)</td></tr>
<tr><td><code>gradle :sim:run</code></td><td>✅ 9/9 deterministička scenarija (seedovi 11–77)</td></tr>
</table>

<details>
<summary><b>Simulator scenariji — klikni za detalje</b></summary>
<ul>
<li><b>relay-fail</b> — kill relaya nakon učenja rute → poruka ide drugim putem, tačno 1x, uz ACK</li>
<li><b>partition/merge</b> — poruka čeka u storeu tokom particije, nakon mergea stiže 1x, 0 gubitaka</li>
<li><b>sos-preempt</b> — SOS latencija 4 ticka uz puno bulk redove</li>
<li><b>storm</b> — broadcast na 20 čvorova: svaki prima 1x, niko ne reflooda dvaput</li>
<li><b>idem</b> — ista sirova kopija 3x → aplikaciji 1x</li>
<li><b>codec</b> — roundtrip + drop pravila (ver/tip/dužina)</li>
<li><b>scale</b> — 24 čvora, gubici 5%: 0 duplikata, SOS stiže svima</li>
</ul>
</details>

<h2>🚀 Build</h2>

<pre>
# potreban JDK 17+ (gradle koristi onaj koji ga pokreće)
gradle :core-mesh:test   # unit testovi
gradle :sim:run           # simulator acceptance
</pre>
<p>Testirano: kotlinc 2.0.21, Temurin JDK 21, Gradle 8.10.2, Linux x86_64.</p>

<h2>⚠️ Iskrena ograničenja</h2>
<ul>
<li>[TESTIRANO-UNIT] i [TESTIRANO-SIMULATOR] samo — <b>nema testova na stvarnim uređajima</b> ([TESTIRANO-UREĐAJ] tek od Faze 4)</li>
<li>Svi pragovi su <b>NEVALIDIRANI</b>: TTL 16, retry 4x, SOS 4/60, store kvota 64, dedup cap 4096</li>
<li>Metapodaci (src/dst) vidljivi relayu; E2E tek u Fazi 3 — <b>ne tvrdimo da je sistem siguran</b></li>
<li>Mreža u v1 je realno "BLE discovery + Wi-Fi za veće podatke", ne idealan mesh</li>
</ul>

<h2>🗺️ Faze</h2>
<p>✅ 0 Dizajn &nbsp; ✅ 1 Simulator-osnova &nbsp; ⏳ 2 Self-healing + koordinator &nbsp; ⏳ 3 Security &nbsp; ⏳ 4 BLE+Probe &nbsp; ⏳ 5 Wi-Fi+link manager &nbsp; ⏳ 6 API+chat+SOS &nbsp; ⏳ 7 Mape/fajlovi/PTT &nbsp; ⏳ 8 Agent+profili &nbsp; ⏳ 9 HW API + OS portabilnost</p>
<p>Detalji po fazi: <a href="docs/PHASE1_REPORT.md">docs/PHASE1_REPORT.md</a> · Arhitektura: <a href="docs/ARCHITECTURE.md">docs/ARCHITECTURE.md</a> · Odluke: <a href="docs/DECISIONS.md">docs/DECISIONS.md</a></p>
