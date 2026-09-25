package nz.aryan.akllive.system

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import nz.aryan.akllive.FavStop
import nz.aryan.akllive.MainActivity
import nz.aryan.akllive.R

/** Long-press the app icon: your favourite stops, straight to their departures. */
object Shortcuts {
    fun syncFavs(ctx: Context, favs: List<FavStop>) {
        try {
            val list = favs.take(3).mapIndexed { i, f ->
                ShortcutInfoCompat.Builder(ctx, "fav_${f.id}")
                    .setShortLabel(f.title.take(24))
                    .setLongLabel("Departures from ${f.title}".take(40))
                    .setIcon(IconCompat.createWithResource(ctx, R.drawable.ic_sc_search))
                    .setIntent(Intent(Intent.ACTION_VIEW, Uri.parse("akllive://stop/${Uri.encode(f.id)}"), ctx, MainActivity::class.java))
                    .setRank(i)
                    .build()
            }
            ShortcutManagerCompat.setDynamicShortcuts(ctx, list)
        } catch (_: Exception) { /* launcher without shortcuts */ }
    }

    /** Put a stop on the home screen as its own icon. False if the launcher can't. */
    fun pin(ctx: Context, id: String, title: String): Boolean = try {
        ShortcutManagerCompat.isRequestPinShortcutSupported(ctx) &&
            ShortcutManagerCompat.requestPinShortcut(ctx, ShortcutInfoCompat.Builder(ctx, "pin_$id")
                .setShortLabel(title.take(24))
                .setLongLabel("Departures from $title".take(40))
                .setIcon(IconCompat.createWithResource(ctx, R.drawable.ic_sc_search))
                .setIntent(Intent(Intent.ACTION_VIEW, Uri.parse("akllive://stop/${Uri.encode(id)}"), ctx, MainActivity::class.java))
                .build(), null)
    } catch (_: Exception) {
        false
    }
}
