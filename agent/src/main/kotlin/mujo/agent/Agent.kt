package mujo.agent

import mujo.mesh.MeshNode

/**
 * Mujo Network Agent, nivo 1: deterministički, pravila-baziran, bez LLM-a (Faza 8a, PC-testabilno).
 * - Samo posmatra + smije dirati isključivo vlastiti noRelay (uz povratak), sve ostalo je savjet/log.
 * - Rate limit: max 1 akcija / 30 tickova. Mreža radi identično i kad je agent ugašen.
 * - Nesigurnost se prijavljuje iskreno ("nemam dovoljno mjerenja"), ne izmišlja.
 */
class NetworkAgent(val node: MeshNode, var profile: AgentProfile = Profiles.EXPEDITION) {
    data class Decision(val tick: Long, val action: String, val reason: String, val certainty: String)
    val log = mutableListOf<Decision>()
    private var lastActionAt = Long.MIN_VALUE / 2
    private var autoRelayOff = false // agent ga ugasio → agent ga i vraća

    fun tick(now: Long): List<Decision> {
        val fresh = mutableListOf<Decision>()
        fun decide(action: String, reason: String, certainty: String, apply: () -> Unit = {}) {
            if (action != "noop" && action != "report") {
                if (now - lastActionAt < 30) return // rate limit akcija (izvještaji uvijek smiju)
                lastActionAt = now; apply()
            }
            val d = Decision(now, action, reason, certainty)
            log.add(d); fresh.add(d)
        }
        if (now < 50) { // premalo mjerenja za bilo kakvu akciju
            decide("report", "nemam dovoljno mjerenja (uptime < 50 tickova)", "n/a"); return fresh
        }
        val lowBatt = node.battery < profile.batterySavePct && !node.charging
        if (lowBatt && !node.noRelayEff()) {
            decide("set-norelay", "baterija ${node.battery}% ispod praga ${profile.batterySavePct}% (SOS i dalje relayujem)", "visoka") {
                node.manualNoRelay = true; autoRelayOff = true
            }
            return fresh
        }
        if (autoRelayOff && (node.battery >= profile.batterySavePct + 10 || node.charging)) {
            decide("set-relay", "baterija se oporavila (${node.battery}%), vraćam relay", "visoka") {
                node.manualNoRelay = false; autoRelayOff = false
            }
            return fresh
        }
        if (node.neighbors.isEmpty()) {
            decide("report", "izolovan: 0 susjeda, store-and-forward čeka (poruke se ne gube)", "srednja"); return fresh
        }
        val noAck = node.drops["no-ack"] ?: 0
        if (noAck >= 5) {
            decide("report", "moguća particija ili mrtvi relay: $noAck nepotvrđenih (retry+store aktivni)", "srednja"); return fresh
        }
        decide("noop", "sve nominalno: ${node.neighbors.size} susjeda, baterija ${node.battery}%", "visoka")
        return fresh
    }
}
