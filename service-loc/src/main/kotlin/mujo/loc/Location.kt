package mujo.loc

import mujo.chat.SosManager
import kotlin.math.*

/**
 * Lokacija (Faza 7a, čisti Kotlin): model + keš s politikom rijetkog osvježavanja.
 * Izvor na Androidu je FusedLocationProvider BEZ GMS-a (framework LocationManager) — iza interfejsa,
 * ovdje Fake za testove. GPS preciznost/baterija: NEVALIDIRANO do terena.
 */
data class Position(val lat: Double, val lon: Double, val accuracyM: Float, val atTick: Long)

interface LocationSource {
    fun last(): Position?
}

/** Keš: prihvata samo svježe i dovoljno pomaknute; current vraća null kad zastari. */
class PositionCache(val maxAgeTicks: Long = 30, val minMoveM: Double = 25.0) {
    private var cur: Position? = null
    fun update(p: Position, now: Long): Boolean {
        val c = cur
        if (c != null && now - c.atTick > maxAgeTicks * 2) { cur = null } // predugo ćutanje → reset
        val cc = cur
        if (cc != null && haversineM(cc.lat, cc.lon, p.lat, p.lon) < minMoveM) return false // šum, ne pomak
        cur = p; return true
    }
    fun current(now: Long): Position? {
        val c = cur ?: return null
        return if (now - c.atTick <= maxAgeTicks) c else null
    }
}

fun haversineM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 6371000.0
    val dLat = Math.toRadians(lat2 - lat1); val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2).pow(2.0) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2.0)
    return 2 * r * asin(sqrt(a))
}

/** SOS s keširanom pozicijom; null kad nema svježe (SOS ide i bez lokacije — bolje išta nego ništa). */
fun SosManager.startSosCached(text: String, cache: PositionCache, now: Long): ByteArray {
    val p = cache.current(now)
    return startSos(text, p?.lat ?: 0.0, p?.lon ?: 0.0, now)
}
