package nz.aryan.akllive.data

import android.content.Context

/** A bus's make and model (see assets/fleet.tsv). */
class BusModel(val name: String, val electric: Boolean, val doubleDeck: Boolean) {
    /** "CRRC eT12 MAX" -> "eT12 MAX", for tight spots. */
    val short: String get() = MAKES.firstOrNull { name.startsWith(it) }?.let { name.removePrefix(it).trim() } ?: name

    private companion object {
        val MAKES = listOf("Alexander Dennis", "ADL", "BCI", "CRRC", "Geely", "Scania", "Yutong")
    }
}

/** Who runs a bus and what it is, worked out from AT's vehicle label ("TR3884"). */
class BusInfo(val operator: String?, val fleetNo: String, val model: BusModel?)

object Fleet {
    /** AT's operator codes, as they appear at the front of a vehicle label. */
    private val OPERATORS = mapOf(
        "NB" to "NZ Bus", "GB" to "Go Bus", "RT" to "Ritchies", "HE" to "Howick & Eastern",
        "TR" to "Tranzurban", "BA" to "Bayes", "WB" to "Waiheke Bus Co",
    )
    private val LABEL = Regex("^([A-Za-z]{1,3})\\s*0*(\\d+)$")

    private class Range(val prefix: String, val first: Int, val last: Int, val model: BusModel)

    @Volatile private var ranges: List<Range> = emptyList()

    fun load(ctx: Context) {
        if (ranges.isNotEmpty()) return
        ranges = try {
            ctx.assets.open("fleet.tsv").bufferedReader().useLines { lines ->
                lines.filter { it.isNotBlank() && !it.startsWith("#") }.mapNotNull { line ->
                    val p = line.split('\t')
                    if (p.size < 6) return@mapNotNull null
                    Range(p[0].uppercase(), p[1].toInt(), p[2].toInt(),
                          BusModel(p[3], p[4] == "electric", p[5].trim() == "2"))
                }.toList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Operator and model for a vehicle label; null if it isn't a bus fleet number. */
    fun info(label: String): BusInfo? {
        val m = LABEL.find(label.trim()) ?: return null
        val prefix = m.groupValues[1].uppercase()
        val num = m.groupValues[2].toIntOrNull() ?: return null
        val model = ranges.firstOrNull { it.prefix == prefix && num in it.first..it.last }?.model
        return BusInfo(OPERATORS[prefix], label.trim(), model)
    }
}
