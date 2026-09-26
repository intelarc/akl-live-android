package nz.aryan.akllive.data

import android.content.Context
import org.json.JSONArray

/** A bus model (see assets/models.json). */
class BusModel(
    val id: String,
    val name: String,
    val maker: String,
    val electric: Boolean,
    val decks: Int,
    /** null when the fleet list doesn't say */
    val axles: Int?,
    /** label -> value, e.g. "Length" -> "12.7 m" */
    val specs: List<Pair<String, String>>,
    val about: String,
    /** its page(s) on the AT Metro Wiki, best first */
    val wiki: List<String> = emptyList(),
    /** what a photo or page title has to mention to be this bus, e.g. "eT12" */
    val key: String = "",
) {
    val doubleDeck: Boolean get() = decks >= 2

    /** "CRRC eT12 MAX" -> "eT12 MAX", for tight spots. */
    val short: String get() = if (name.startsWith("$maker ")) name.removePrefix(maker).trim() else name

    /** "Electric double-decker", "Diesel, three axles" */
    val kind: String get() = buildString {
        append(if (electric) "Electric" else "Diesel")
        append(if (doubleDeck) " double-decker" else " single-decker")
        if (axles == 3) append(", three axles")
    }
}

/** Who runs a bus and what it is, worked out from AT's vehicle label ("TR3884"). */
class BusInfo(val code: String, val operator: String?, val fleetNo: String, val model: BusModel?)

object Fleet {
    /** AT's operator codes, as they appear at the front of a vehicle label. */
    val OPERATORS = linkedMapOf(
        "NB" to "NZ Bus", "RT" to "Ritchies", "GB" to "Go Bus", "HE" to "Howick & Eastern",
        "TR" to "Tranzurban", "WB" to "Waiheke Bus Co", "BA" to "Bayes", "PC" to "Pavlovich",
    )
    private val LABEL = Regex("^([A-Za-z]{1,3})\\s*0*(\\d+)$")

    private class Range(val prefix: String, val first: Int, val last: Int, val model: BusModel)

    @Volatile private var ranges: List<Range> = emptyList()
    @Volatile var models: List<BusModel> = emptyList()
        private set

    fun load(ctx: Context) {
        if (models.isNotEmpty()) return
        try {
            val arr = JSONArray(ctx.assets.open("models.json").bufferedReader().use { it.readText() })
            val ms = (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val specs = o.optJSONArray("specs") ?: JSONArray()
                BusModel(
                    id = o.getString("id"), name = o.getString("name"), maker = o.optString("maker"),
                    electric = o.optBoolean("electric"), decks = o.optInt("decks", 1),
                    axles = if (o.has("axles")) o.getInt("axles") else null,
                    specs = (0 until specs.length()).map { k -> specs.getJSONArray(k).let { it.getString(0) to it.getString(1) } },
                    about = o.optString("about"),
                    wiki = o.optJSONArray("wiki")?.let { w -> (0 until w.length()).map { w.getString(it) } } ?: emptyList(),
                    key = o.optString("key"),
                )
            }
            val byId = ms.associateBy { it.id }
            ranges = ctx.assets.open("fleet.tsv").bufferedReader().useLines { lines ->
                lines.filter { it.isNotBlank() && !it.startsWith("#") }.mapNotNull { line ->
                    val p = line.split('\t')
                    val model = byId[p.getOrNull(3)?.trim()] ?: return@mapNotNull null
                    Range(p[0].uppercase(), p[1].toInt(), p[2].toInt(), model)
                }.toList()
            }
            models = ms
        } catch (e: Exception) {
            ranges = emptyList()
        }
    }

    fun model(id: String?): BusModel? = models.firstOrNull { it.id == id }

    /** Operator and model for a vehicle label; null if it isn't a bus fleet number we know the operator of. */
    fun info(label: String): BusInfo? {
        val m = LABEL.find(label.trim()) ?: return null
        val prefix = m.groupValues[1].uppercase()
        val operator = OPERATORS[prefix] ?: return null
        val num = m.groupValues[2].toIntOrNull() ?: return null
        val model = ranges.firstOrNull { it.prefix == prefix && num in it.first..it.last }?.model
        return BusInfo(prefix, operator, label.trim().replace(" ", ""), model)
    }

    /** A model's fleet numbers by operator: "NZ Bus" -> "NB5752–5786, NB5800–5842". */
    fun numbers(model: BusModel): List<Pair<String, String>> =
        ranges.filter { it.model === model }.groupBy { it.prefix }.map { (prefix, rs) ->
            (OPERATORS[prefix] ?: prefix) to rs.joinToString(", ") {
                if (it.first == it.last) "$prefix${it.first}" else "$prefix${it.first}–${it.last}"
            }
        }
}
