package nz.aryan.akllive.gtfs

import java.io.InputStream

/**
 * CSV straight from bytes, a line at a time. GTFS files are big (stop_times.txt
 * is over a million rows), so nothing is decoded until it's asked for: fields
 * are byte ranges into [line].
 */
internal class CsvReader(private val input: InputStream) {
    private val buf = ByteArray(1 shl 16)
    private var pos = 0
    private var lim = 0
    private var line = ByteArray(512)
    private var len = 0
    private var start = IntArray(32)
    private var end = IntArray(32)
    var cols = 0
        private set
    private var first = true

    private fun fill(): Boolean {
        lim = input.read(buf)
        pos = 0
        return lim > 0
    }

    private fun append(src: ByteArray, from: Int, n: Int) {
        if (n <= 0) return
        if (len + n > line.size) line = line.copyOf(maxOf(line.size * 2, len + n))
        System.arraycopy(src, from, line, len, n)
        len += n
    }

    /** Reads the next non-empty line and splits it into fields; false at the end of the file. */
    fun next(): Boolean {
        while (true) {
            len = 0
            var any = false
            while (true) {
                if (pos >= lim && !fill()) break
                any = true
                var i = pos
                while (i < lim) {
                    val c = buf[i].toInt()
                    if (c == 10 || c == 13) break
                    i++
                }
                append(buf, pos, i - pos)
                if (i < lim) {
                    pos = i + 1
                    break
                }
                pos = i
            }
            if (len == 0) {
                if (!any) return false
                continue                                  // a blank line, or the \n of a \r\n
            }
            var from = 0
            if (first) {
                first = false
                if (len >= 3 && line[0] == 0xEF.toByte() && line[1] == 0xBB.toByte() && line[2] == 0xBF.toByte()) from = 3
            }
            split(from)
            return true
        }
    }

    private fun split(from: Int) {
        var k = 0
        var quoted = false
        start[0] = from
        for (i in from until len) {
            val c = line[i].toInt()
            if (c == 34) quoted = !quoted
            else if (c == 44 && !quoted) {
                end[k] = i
                k++
                if (k + 1 >= start.size) {
                    start = start.copyOf(start.size * 2)
                    end = end.copyOf(end.size * 2)
                }
                start[k] = i + 1
            }
        }
        end[k] = len
        cols = k + 1
    }

    /** Column name -> index, from the current (header) line. */
    fun header(): Map<String, Int> = (0 until cols).associateBy { str(it).trim() }

    fun str(k: Int): String {
        if (k < 0 || k >= cols) return ""
        var a = start[k]
        var b = end[k]
        if (b - a >= 2 && line[a].toInt() == 34 && line[b - 1].toInt() == 34) {
            a++
            b--
            val s = String(line, a, b - a, Charsets.UTF_8)
            return if (s.contains("\"\"")) s.replace("\"\"", "\"") else s
        }
        return if (b > a) String(line, a, b - a, Charsets.UTF_8) else ""
    }

    /** A plain unsigned integer (stop_sequence, pickup_type); 0 if empty. */
    fun int(k: Int): Int {
        if (k < 0 || k >= cols) return 0
        var v = 0
        for (i in start[k] until end[k]) {
            val c = line[i] - 48
            if (c in 0..9) v = v * 10 + c
        }
        return v
    }

    /** "HH:MM:SS" (hours can pass 24) as seconds; -1 if empty. */
    fun hms(k: Int): Int {
        if (k < 0 || k >= cols) return -1
        var i = start[k]
        val b = end[k]
        while (i < b && (line[i].toInt() == 32 || line[i].toInt() == 34)) i++
        if (i >= b) return -1
        var h = 0
        var m = 0
        var s = 0
        while (i < b && line[i] in 48..57) { h = h * 10 + (line[i] - 48); i++ }
        if (i < b && line[i].toInt() == 58) {
            i++
            while (i < b && line[i] in 48..57) { m = m * 10 + (line[i] - 48); i++ }
        }
        if (i < b && line[i].toInt() == 58) {
            i++
            while (i < b && line[i] in 48..57) { s = s * 10 + (line[i] - 48); i++ }
        }
        return h * 3600 + m * 60 + s
    }

    /** A decimal like "-36.84872" without making a String. */
    fun double(k: Int): Double {
        if (k < 0 || k >= cols) return 0.0
        var i = start[k]
        val b = end[k]
        while (i < b && (line[i].toInt() == 32 || line[i].toInt() == 34)) i++
        var neg = false
        if (i < b && line[i].toInt() == 45) {
            neg = true
            i++
        }
        var whole = 0L
        while (i < b && line[i] in 48..57) {
            whole = whole * 10 + (line[i] - 48)
            i++
        }
        var frac = 0.0
        if (i < b && line[i].toInt() == 46) {
            i++
            var scale = 0.1
            while (i < b && line[i] in 48..57) {
                frac += (line[i] - 48) * scale
                scale *= 0.1
                i++
            }
        }
        val v = whole + frac
        return if (neg) -v else v
    }

    /** Is field [k] exactly these bytes (compare a trip id without decoding it)? */
    fun sameAs(k: Int, bytes: ByteArray, n: Int): Boolean {
        if (k < 0 || k >= cols) return false
        val a = start[k]
        if (end[k] - a != n) return false
        for (i in 0 until n) if (line[a + i] != bytes[i]) return false
        return true
    }

    /** Copies field [k]'s bytes into [into] (grown as needed); returns the new array and the length. */
    fun copy(k: Int, into: ByteArray): Pair<ByteArray, Int> {
        val n = end[k] - start[k]
        val out = if (into.size >= n) into else ByteArray(n * 2)
        System.arraycopy(line, start[k], out, 0, n)
        return out to n
    }

    /** The first byte of field [k] as a digit (pickup_type), or -1 if the field is empty. */
    fun digit(k: Int): Int {
        if (k < 0 || k >= cols || end[k] <= start[k]) return -1
        return line[start[k]] - 48
    }
}

/** A growable int list without boxing. */
internal class IntList(cap: Int = 16) {
    var a = IntArray(cap)
    var n = 0
    fun add(v: Int) {
        if (n == a.size) a = a.copyOf(a.size * 2)
        a[n++] = v
    }
    operator fun get(i: Int) = a[i]
    fun toArray(): IntArray = a.copyOf(n)
}
