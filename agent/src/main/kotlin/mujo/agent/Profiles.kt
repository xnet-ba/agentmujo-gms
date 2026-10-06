package mujo.agent

/**
 * Deklarativni profili načina rada (§7). Vrijednosti NEVALIDIRANE (uz svaku zašto, dok teren ne potvrdi).
 * File binding (JSON/YAML) dolazi s aplikacijom; ovdje tip + gotove vrijednosti, testabilno na PC-u.
 */
data class AgentProfile(
    val name: String,
    val heartbeatIntervalTicks: Long, // HELLO ritam: gušće = brže otkrivanje, više potrošnje
    val relayAggressive: Boolean,     // true = relayuj i sumnjive rute (katastrofa), false = štedi bateriju
    val batterySavePct: Int,          // ispod ovoga ne-relay (osim SOS)
    val sosQuorum: Int,               // koliko potvrda gasi SOS sesiju
    val sosRepeatEveryTicks: Long,
    val sosMaxTries: Int,
    val storeQuota: Int,              // store-and-forward kvota (uloga Storage je diže)
    val note: String,                 // zašto ove vrijednosti
)

object Profiles {
    val EMERGENCY = AgentProfile("emergency", 5, true, 10, 1, 10, 20, 128,
        "Život prije baterije: gust heartbeat za brzo otkrivanje, SOS često do prve potvrde, veliki store.")
    val WILD = AgentProfile("wild", 20, false, 25, 1, 30, 8, 32,
        "Planina: rijetki susreti, štednja baterije (spori heartbeat, visok prag), SOS umjeren.")
    val EXPEDITION = AgentProfile("expedition", 10, true, 15, 2, 20, 10, 64,
        "Ekspedicija: grupa se kreće zajedno, kvorum 2 potvrde, srednja agresivnost.")
    val DISASTER = AgentProfile("disaster", 5, true, 5, 1, 10, 30, 256,
        "Katastrofa: mreža je kritična infrastruktura, sve na maksimum, baterija se žrtvuje do 5%.")
    val TACTICAL = AgentProfile("tactical", 30, false, 30, 1, 40, 5, 16,
        "Taktički: minimalna emisija (rijetki HELLO = teže otkrivanje), malo ponavljanja, mali store.")
    val ALL = listOf(EMERGENCY, WILD, EXPEDITION, DISASTER, TACTICAL)
}
