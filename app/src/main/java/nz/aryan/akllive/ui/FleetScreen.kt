package nz.aryan.akllive.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import nz.aryan.akllive.data.Images
import nz.aryan.akllive.data.Photo
import nz.aryan.akllive.data.Photos
import nz.aryan.akllive.AppViewModel
import nz.aryan.akllive.data.BusModel
import nz.aryan.akllive.data.Fleet
import nz.aryan.akllive.data.LiveBus
import nz.aryan.akllive.data.occupancyText

/** The model page id for buses whose fleet numbers aren't in the list yet. */
const val UNKNOWN_MODEL = "unknown"

/** Opens a model's page on the Fleet tab, from anywhere in the app. */
val LocalOpenModel = staticCompositionLocalOf<(String) -> Unit> { {} }

/** Every bus model in Auckland, with how many of each are out right now. */
@Composable
fun FleetScreen(vm: AppViewModel, modifier: Modifier) {
    DisposableEffect(Unit) {
        vm.watchLive(true)
        onDispose { vm.watchLive(false) }
    }
    val state by vm.fleet.collectAsStateWithLifecycle()
    val open by vm.fleetModel.collectAsStateWithLifecycle()
    val frameT = rememberFrameTime()
    open?.let { id ->
        ModelScreen(vm, id, state.buses, state.updated, frameT, modifier) { vm.fleetModel.value = null }
        return
    }
    var filter by rememberSaveable { mutableStateOf("all") }
    val byModel = remember(state.buses) { state.buses.groupBy { it.info.model?.id } }
    val models = remember(byModel, filter) {
        Fleet.models.filter {
            when (filter) {
                "electric" -> it.electric
                "diesel" -> !it.electric
                "double" -> it.doubleDeck
                else -> true
            }
        }.sortedWith(compareByDescending<BusModel> { byModel[it.id]?.size ?: 0 }.thenBy { it.name })
    }
    val unknown = byModel[null].orEmpty()

    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp),
               verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Column {
                val known = state.buses.size - unknown.size
                Text(if (state.loading) "Counting the buses on the road…"
                     else "${Fleet.models.size} models · %,d buses out right now".format(state.buses.size) +
                          if (state.buses.isNotEmpty()) " (%d%% identified)".format(known * 100 / state.buses.size) else "",
                     style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for ((key, label) in listOf("all" to "All", "electric" to "⚡ Electric", "diesel" to "Diesel",
                                                "double" to "Double-deckers")) {
                        ToggleChip(label, filter == key) { filter = key }
                    }
                }
            }
        }
        items(models, key = { it.id }) { m ->
            ModelCard(m, byModel[m.id].orEmpty()) { vm.fleetModel.value = m.id }
        }
        if (filter == "all" && unknown.isNotEmpty()) {
            item(key = UNKNOWN_MODEL) { UnknownCard(unknown) { vm.fleetModel.value = UNKNOWN_MODEL } }
        }
        item {
            Text("Models and fleet numbers from the AT Metro Wiki (atmetro.fandom.com, CC BY-SA), matched to the " +
                 "fleet number each bus reports. Live positions from Auckland Transport.",
                 style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                 modifier = Modifier.padding(top = 4.dp, bottom = 12.dp))
        }
    }
}

@Composable
private fun ToggleChip(label: String, on: Boolean, click: () -> Unit) {
    Box(Modifier.clip(RoundedCornerShape(50))
            .background(if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = click).padding(horizontal = 14.dp, vertical = 8.dp)) {
        Text(label, fontWeight = FontWeight.Bold, fontSize = 14.sp,
             color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A bus on a strip of road, for cards and headers. */
@Composable
private fun BusPortrait(model: BusModel?, modifier: Modifier, frameT: State<Float>? = null) {
    val look = BusLook.of(model)
    Canvas(modifier) {
        val road = size.height * 0.86f
        drawRect(Brush.verticalGradient(listOf(Color(0xFF7FB6F0), Color(0xFFCFE6FA)), 0f, road), Offset.Zero,
                 Size(size.width, road))
        drawRect(Color(0xFF3A414C), Offset(0f, road), Size(size.width, size.height - road))
        val bl = size.width * 0.8f
        val bh = if (look.doubleDeck) road * 0.52f else road * 0.56f
        val t = frameT?.value ?: 0f
        drawBus((size.width - bl) / 2, road + size.height * 0.02f, bl, bh, t, night = false, moving = frameT != null,
                cancelled = false, dest = null, dp = density, look = look)
    }
}

/**
 * A real photo of the model (from its AT Metro Wiki page, credited on it) over the
 * drawn bus, which shows until the photo's in, or if there isn't one.
 */
@Composable
private fun ModelPhoto(m: BusModel?, modifier: Modifier, frameT: State<Float>? = null) {
    val ctx = LocalContext.current
    val photo by produceState<Photo?>(null, m?.id) { value = m?.let { Photos.forModel(ctx, it) } }
    val bmp by produceState<ImageBitmap?>(null, photo?.url) { value = photo?.url?.let { Images.load(ctx, it)?.asImageBitmap() } }
    Box(modifier) {
        BusPortrait(m, Modifier.fillMaxSize(), frameT)
        androidx.compose.animation.AnimatedVisibility(bmp != null, Modifier.fillMaxSize(), enter = fadeIn(), exit = fadeOut()) {
            bmp?.let { Image(it, m?.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        }
        val p = photo
        if (bmp != null && p != null) {
            Text(p.credit, color = Color.White, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                 modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth()
                     .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f))))
                     .clickable { try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(p.page))) } catch (_: Exception) { } }
                     .padding(start = 10.dp, end = 10.dp, top = 10.dp, bottom = 4.dp))
        }
    }
}

/** A small photo of the model for a list row; the drawn bus until (or unless) there's a photo. */
@Composable
internal fun ModelThumb(m: BusModel?, modifier: Modifier) {
    val ctx = LocalContext.current
    val photo by produceState<Photo?>(null, m?.id) { value = m?.let { Photos.forModel(ctx, it) } }
    val bmp by produceState<ImageBitmap?>(null, photo?.url) { value = photo?.url?.let { Images.load(ctx, it)?.asImageBitmap() } }
    Box(modifier.clip(RoundedCornerShape(10.dp))) {
        val b = bmp
        if (b != null) Image(b, m?.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else BusGlyph(m, Modifier.fillMaxSize().padding(horizontal = 4.dp, vertical = 6.dp))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModelCard(m: BusModel, live: List<LiveBus>, click: () -> Unit) {
    Card(onClick = click, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp),
         colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Box(Modifier.fillMaxWidth().height(150.dp)) {
            ModelPhoto(m, Modifier.fillMaxSize())
            Row(Modifier.align(Alignment.TopEnd).padding(10.dp).background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(50))
                    .padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (live.isNotEmpty()) {
                    Box(Modifier.size(7.dp).background(Pal.Live, CircleShape))
                    Spacer(Modifier.width(5.dp))
                }
                Text(if (live.isEmpty()) "None out" else "${live.size} out now", color = Color.White, fontSize = 12.sp,
                     fontWeight = FontWeight.Bold)
            }
        }
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(m.name, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium, maxLines = 1,
                 overflow = TextOverflow.Ellipsis)
            Text(m.kind, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for ((op, _) in Fleet.numbers(m)) OperatorTag(op)
            }
        }
    }
}

@Composable
private fun OperatorTag(name: String) {
    val code = Fleet.OPERATORS.entries.firstOrNull { it.value == name }?.key ?: ""
    val c = operatorColor(code)
    Row(Modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).background(c, CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(name, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
    }
}

@Composable
private fun UnknownCard(buses: List<LiveBus>, click: () -> Unit) {
    val byOp = buses.groupingBy { it.info.operator ?: it.info.code }.eachCount().entries.sortedByDescending { it.value }
    Card(Modifier.fillMaxWidth().clickable(onClick = click), shape = RoundedCornerShape(22.dp),
         colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Not identified yet", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                Text("Buses whose fleet numbers aren't in the app's list yet: " +
                     byOp.joinToString(", ") { "${it.key} ${it.value}" },
                     style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(8.dp))
            Text("${buses.size}", fontWeight = FontWeight.Black, fontSize = 26.sp)
        }
    }
}

/** One model: what it is, the numbers it carries, and every one of them on a live map. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModelScreen(vm: AppViewModel, id: String, all: List<LiveBus>, updated: Long, frameT: State<Float>,
                        modifier: Modifier,
                        close: () -> Unit) {
    BackHandler(onBack = close)
    val model = Fleet.model(id)
    val basemap by vm.basemap.collectAsStateWithLifecycle()
    val linzKey by vm.linzKey.collectAsStateWithLifecycle()
    val now by rememberNow()
    val live = remember(all, id) {
        all.filter { if (model == null) it.info.model == null else it.info.model === model }
            .sortedWith(compareBy<LiveBus> { it.route == null }.thenBy { it.route ?: "" }.thenBy { it.info.fleetNo })
    }
    var selected by rememberSaveable(id) { mutableStateOf<String?>(null) }
    var focus by remember(id) { mutableStateOf<MapFocus?>(null) }
    val sel = live.firstOrNull { it.v.id == selected }
    // frame the fleet once, when it first comes in, not on every refresh
    var fit by remember(id) { mutableStateOf<List<Pair<Double, Double>>?>(null) }
    LaunchedEffect(id, live.isNotEmpty()) {
        if (fit == null && live.isNotEmpty()) fit = live.map { it.v.lat to it.v.lon }
    }
    val crowd = remember(live, selected) { live.filter { it.v.id != selected }.map { it.dot() } }
    val scroll = rememberScrollState()

    Column(modifier.fillMaxSize().verticalScroll(scroll)) {
        Box(Modifier.fillMaxWidth().height(230.dp)) {
            ModelPhoto(model, Modifier.fillMaxSize(), frameT)
            IconButton(onClick = close, modifier = Modifier.padding(8.dp)
                .background(Color.Black.copy(alpha = 0.35f), CircleShape)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
            }
        }
        Column(Modifier.padding(16.dp)) {
            Text(model?.name ?: "Not identified yet", style = MaterialTheme.typography.headlineSmall,
                 fontWeight = FontWeight.Black)
            Text(model?.let { "${it.maker} · ${it.kind}" } ?: "Buses the app's fleet list doesn't cover yet",
                 style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            if (model != null) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatTile("Out now", "${live.size}")
                    for ((k, v) in model.specs) StatTile(k, v)
                }
                Spacer(Modifier.height(14.dp))
                Text(model.about, style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(16.dp))
                Text("Fleet numbers", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                for ((op, nums) in Fleet.numbers(model)) {
                    Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.Top) {
                        OperatorTag(op)
                        Spacer(Modifier.width(8.dp))
                        Text(nums, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    }
                }
            } else {
                Text("Their operators are known from the fleet-number prefix, but not the model. " +
                     "The app's fleet list grows as more ranges are confirmed.",
                     style = MaterialTheme.typography.bodyLarge)
            }
            Spacer(Modifier.height(18.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Where they are now", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                     modifier = Modifier.weight(1f))
                LiveBadge(updated, now)
            }
            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth().height(360.dp).clip(RoundedCornerShape(20.dp))) {
                LiveMap(basemap, linzKey, emptyList(), emptyList(), listOfNotNull(sel?.marker()), fit ?: AKL_BOUNDS,
                        frameT, Modifier.fillMaxSize(), selected = selected, crowd = crowd, focus = focus,
                        onCrowd = { selected = it }, onMarker = { selected = it }, onBackground = { selected = null })
                Box(Modifier.align(Alignment.TopEnd).padding(8.dp)) { BasemapToggle(basemap, vm::setBasemap) }
                sel?.let { b ->
                    Surface(Modifier.align(Alignment.BottomCenter).padding(10.dp).fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)) {
                        LiveRow(b, now, big = true) { selected = null }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(if (live.isEmpty()) "None of them are out right now."
                 else "${live.size} on the road. Tap one to find it on the map.",
                 style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            for ((i, b) in live.withIndex()) {
                if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                LiveRow(b, now) {
                    selected = b.v.id
                    focus = MapFocus(b.v.lat, b.v.lon)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun StatTile(label: String, value: String) {
    Column(Modifier.clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
               .padding(horizontal = 12.dp, vertical = 8.dp)) {
        Text(value, fontWeight = FontWeight.Black, fontSize = 17.sp)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A bus in a model's list: fleet number, operator, route and how it's moving. */
@Composable
private fun LiveRow(b: LiveBus, now: Long, big: Boolean = false, click: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = click).padding(vertical = 10.dp, horizontal = if (big) 12.dp else 0.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(12.dp).background(operatorColor(b.info.code), CircleShape).border(1.5.dp, Color.White, CircleShape))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text("${b.info.fleetNo}  ·  ${b.info.operator ?: b.info.code}", fontWeight = FontWeight.Bold,
                 style = MaterialTheme.typography.bodyLarge)
            Text(listOfNotNull(
                    b.v.speedKmh?.let { if (it < 2) "Stopped" else "${it.toInt()} km/h" },
                    occupancyText(b.v.occupancy),
                    "seen ${(now - b.v.timestamp).coerceAtLeast(0)}s ago",
                 ).joinToString("  ·  "),
                 style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Box(Modifier.background(if (b.route != null) operatorColor(b.info.code) else MaterialTheme.colorScheme.surfaceVariant,
                                RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 2.dp)) {
            Text(b.route ?: "Not in service", fontWeight = FontWeight.Black, fontSize = if (b.route != null) 15.sp else 12.sp,
                 color = if (b.route != null) Color.White else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
