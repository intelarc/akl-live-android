package nz.aryan.akllive.system

import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nz.aryan.akllive.data.Nz

/** A quick settings tile: pull down the shade, see your next bus. Tap it to refresh. */
class NextBusTile : TileService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onStartListening() {
        super.onStartListening()
        val cached = Glance.load(this)
        show(cached)
        if (cached == null || Nz.nowSec() - cached.updated > 120) refresh()
    }

    override fun onClick() {
        super.onClick()
        qsTile?.let {
            it.label = "Checking…"
            it.updateTile()
        }
        refresh()
    }

    private fun refresh() {
        scope.launch {
            val d = withContext(Dispatchers.IO) { Glance.fetch(this@NextBusTile) }
            show(d)
        }
    }

    private fun show(d: GlanceData?) {
        val t = qsTile ?: return
        val b = d?.next()
        val now = Nz.nowSec()
        if (b == null) {
            t.label = if (d == null) "AKL Live" else "No buses soon"
            if (Build.VERSION.SDK_INT >= 29) t.subtitle = if (d == null) "Open the app first" else "Tap to check again"
            t.state = Tile.STATE_INACTIVE
        } else {
            val secs = b.expected - now
            t.label = "${b.route} · " + if (secs < 45) "Due" else "${secs / 60} min"
            if (Build.VERSION.SDK_INT >= 29) t.subtitle = "to ${b.headsign}" + if (b.electric) " ⚡" else ""
            t.state = Tile.STATE_ACTIVE
        }
        t.updateTile()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
