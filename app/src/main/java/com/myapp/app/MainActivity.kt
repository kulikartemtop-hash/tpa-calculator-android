// TPA CALCULATOR BUILD: 2026-10-06 — per-TPA reports + tube TPA
package com.myapp.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

private val TpaNames = (1..13).map { "ТПА $it" } + "Малыш" + "Трубы после Малыша"
private val Materials = listOf("ПВХ серый", "ПВХ коричневый", "АБС", "PP")

private const val PREFS = "tpa_premium_android"
private const val DATA_KEY = "calculator_data"
private const val COLLAPSED_KEY = "collapsed_data"
private const val THEME_KEY = "dark_theme"
private const val WALLPAPER_KEY = "wallpaper"
private const val HISTORY_KEY = "history"
private const val VISUAL_VERSION_KEY = "visual_version"
private const val MATERIAL_PRICES_KEY = "material_prices"
private const val TEMPLATES_KEY = "part_templates"
private const val COMPACT_KEY = "compact_mode"

private const val CYAN = 0xFF38BDF8
private const val CYAN_DARK = 0xFF0EA5E9
private const val BG_DARK_1 = 0xFF06152F
private const val BG_DARK_2 = 0xFF153B72
private const val CARD_DARK = 0xB5265D92
private const val FIELD_DARK = 0xFF0B1E42
private const val BORDER_BLUE = 0xFF2874E8
private const val SUCCESS = 0xFF55DFA1
private const val DANGER = 0xFFFF7777
private const val WARNING = 0xFFFFD43B
private const val TEXT_LIGHT = 0xFFE8F2FF
private const val MUTED_LIGHT = 0xFFBFD0E8

data class PartData(
    val name: String = "",
    val material: String = Materials.first(),
    val perBox: String = "",
    val boxes: String = "",
    val defect: String = "",
    val weight: String = "",
    val weightUnit: String = "г",
    val batchTotal: String = ""
)

data class TpaData(
    val enabled: Boolean = true,
    val parts: List<PartData> = listOf(PartData())
)

data class PartTemplate(
    val id: Long,
    val name: String,
    val material: String,
    val perBox: String,
    val boxes: String,
    val defect: String,
    val weight: String,
    val weightUnit: String = "г",
    val batchTotal: String,
    val archived: Boolean = false
)

data class HistoryEntry(
    val tpa: String,
    val partName: String,
    val material: String,
    val good: Double,
    val defect: Double,
    val weight: Double
)

data class HistoryReport(
    val date: String,
    val entries: List<HistoryEntry>
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.rgb(6, 21, 47)
        window.navigationBarColor = android.graphics.Color.rgb(6, 21, 47)
        setContent { PremiumTpaApp(this) }
    }
}

@Composable
private fun PremiumTpaApp(context: Context) {
    val prefs = remember { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    // Один раз переводим внешний вид на стиль старой web-версии:
    // тёмная тема + Galaxy. После этого пользовательские настройки сохраняются.
    val visualVersion = prefs.getInt(VISUAL_VERSION_KEY, 0)
    if (visualVersion < 3) {
        prefs.edit()
            .putBoolean(THEME_KEY, true)
            .putInt(WALLPAPER_KEY, 3)
            .putInt(VISUAL_VERSION_KEY, 3)
            .apply()
    }

    var darkTheme by remember { mutableStateOf(prefs.getBoolean(THEME_KEY, true)) }
    var wallpaper by remember { mutableStateOf(prefs.getInt(WALLPAPER_KEY, 3)) }
    var tab by remember { mutableStateOf(0) }
    var showReset by remember { mutableStateOf(false) }
    var showShare by remember { mutableStateOf(false) }
    var compactMode by remember { mutableStateOf(prefs.getBoolean(COMPACT_KEY, false)) }
    var showSplash by remember { mutableStateOf(true) }
    var undoSnapshot by remember { mutableStateOf<List<TpaData>?>(null) }
    var dirty by remember { mutableStateOf(false) }
    var saveMessage by remember { mutableStateOf("") }
    var showExit by remember { mutableStateOf(false) }

    val materialPrices = remember {
        mutableStateMapOf<String, String>().also { map ->
            val saved = prefs.getString(MATERIAL_PRICES_KEY, null)
            if (saved != null) {
                try {
                    val o = JSONObject(saved)
                    Materials.forEach { material -> map[material] = o.optString(material, "0") }
                } catch (_: Exception) {}
            }
            Materials.forEach { map.putIfAbsent(it, "0") }
        }
    }

    val templates = remember {
        mutableStateListOf<PartTemplate>().also { list ->
            val saved = prefs.getString(TEMPLATES_KEY, null)
            if (saved != null) {
                try {
                    val arr = JSONArray(saved)
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        list.add(
                            PartTemplate(
                                id = o.optLong("id", System.currentTimeMillis() + i),
                                name = o.optString("name"),
                                material = o.optString("material", Materials.first()),
                                perBox = o.optString("perBox"),
                                boxes = o.optString("boxes"),
                                defect = o.optString("defect"),
                                weight = o.optString("weight"),
                                weightUnit = o.optString("weightUnit", "г"),
                                batchTotal = o.optString("batchTotal"),
                                archived = o.optBoolean("archived", false)
                            )
                        )
                    }
                } catch (_: Exception) {}
            }
        }
    }

    val data = remember {
        mutableStateListOf<TpaData>().also { list ->
            val saved = prefs.getString(DATA_KEY, null)
            if (saved != null) {
                try {
                    val arr = JSONArray(saved)
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        val parts = mutableListOf<PartData>()
                        val partsArray = o.optJSONArray("parts")
                        if (partsArray != null && partsArray.length() > 0) {
                            for (j in 0 until partsArray.length()) {
                                val part = partsArray.getJSONObject(j)
                                parts.add(
                                    PartData(
                                        name = part.optString("name"),
                                        material = part.optString("material", Materials.first()),
                                        perBox = part.optString("perBox"),
                                        boxes = part.optString("boxes"),
                                        defect = part.optString("defect"),
                                        weight = part.optString("weight"),
                                        weightUnit = part.optString("weightUnit", "г"),
                                        batchTotal = part.optString("batchTotal")
                                    )
                                )
                            }
                        } else {
                            // Совместимость со старой версией: переносим старые поля в одну деталь.
                            parts.add(
                                PartData(
                                    material = o.optString("material", Materials.first()),
                                    perBox = o.optString("perBox"),
                                    boxes = o.optString("boxes"),
                                    defect = o.optString("defect"),
                                    weight = o.optString("weight"),
                                    weightUnit = o.optString("weightUnit", "г"),
                                    batchTotal = o.optString("batchTotal")
                                )
                            )
                        }
                        list.add(TpaData(o.optBoolean("enabled", true), parts))
                    }
                } catch (_: Exception) {}
            }
            while (list.size < TpaNames.size) list.add(TpaData())
        }
    }

    val collapsed = remember {
        mutableStateMapOf<String, Boolean>().also { map ->
            val saved = prefs.getString(COLLAPSED_KEY, null)
            if (saved != null) {
                try {
                    val o = JSONObject(saved)
                    TpaNames.forEach { map[it] = o.optBoolean(it, false) }
                } catch (_: Exception) {}
            }
            TpaNames.forEach { map.putIfAbsent(it, false) }
        }
    }

    val history = remember {
        mutableStateListOf<HistoryReport>().also { list ->
            val saved = prefs.getString(HISTORY_KEY, null)
            if (saved != null) {
                try {
                    val arr = JSONArray(saved)
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        val entries = mutableListOf<HistoryEntry>()
                        val entriesArray = o.optJSONArray("entries")
                        if (entriesArray != null) {
                            for (j in 0 until entriesArray.length()) {
                                val e = entriesArray.getJSONObject(j)
                                entries.add(
                                    HistoryEntry(
                                        tpa = e.optString("tpa"),
                                        partName = e.optString("partName"),
                                        material = e.optString("material", Materials.first()),
                                        good = e.optDouble("good"),
                                        defect = e.optDouble("defect"),
                                        weight = e.optDouble("weight")
                                    )
                                )
                            }
                        } else {
                            // Совместимость со старой историей: старые записи объединяем в один блок.
                            entries.add(
                                HistoryEntry(
                                    tpa = o.optString("tpa"),
                                    partName = "",
                                    material = o.optString("material", Materials.first()),
                                    good = o.optDouble("good"),
                                    defect = o.optDouble("defect"),
                                    weight = o.optDouble("weight")
                                )
                            )
                        }
                        if (entries.isNotEmpty()) {
                            list.add(HistoryReport(o.optString("date"), entries))
                        }
                    }
                } catch (_: Exception) {}
            }
        }
    }

    fun saveAll() {
        val arr = JSONArray()
        data.forEach {
            arr.put(
                JSONObject().apply {
                    put("enabled", it.enabled)
                    val partsArray = JSONArray()
                    it.parts.forEach { part ->
                        partsArray.put(
                            JSONObject().apply {
                                put("name", part.name)
                                put("material", part.material)
                                put("perBox", part.perBox)
                                put("boxes", part.boxes)
                                put("defect", part.defect)
                                put("weight", part.weight)
                                put("weightUnit", part.weightUnit)
                                put("batchTotal", part.batchTotal)
                            }
                        )
                    }
                    put("parts", partsArray)
                }
            )
        }

        val c = JSONObject()
        collapsed.forEach { (k, v) -> c.put(k, v) }

        val prices = JSONObject()
        materialPrices.forEach { (material, price) -> prices.put(material, price) }

        val h = JSONArray()
        history.forEach { report ->
            h.put(
                JSONObject().apply {
                    put("date", report.date)
                    val entries = JSONArray()
                    report.entries.forEach { entry ->
                        entries.put(
                            JSONObject().apply {
                                put("tpa", entry.tpa)
                                put("partName", entry.partName)
                                put("material", entry.material)
                                put("good", entry.good)
                                put("defect", entry.defect)
                                put("weight", entry.weight)
                            }
                        )
                    }
                    put("entries", entries)
                }
            )
        }

        val t = JSONArray()
        templates.forEach { template ->
            t.put(
                JSONObject().apply {
                    put("id", template.id)
                    put("name", template.name)
                    put("material", template.material)
                    put("perBox", template.perBox)
                    put("boxes", template.boxes)
                    put("defect", template.defect)
                    put("weight", template.weight)
                    put("weightUnit", template.weightUnit)
                    put("batchTotal", template.batchTotal)
                    put("archived", template.archived)
                }
            )
        }

        prefs.edit()
            .putString(DATA_KEY, arr.toString())
            .putString(COLLAPSED_KEY, c.toString())
            .putString(HISTORY_KEY, h.toString())
            .putString(MATERIAL_PRICES_KEY, prices.toString())
            .putString(TEMPLATES_KEY, t.toString())
            .putBoolean(THEME_KEY, darkTheme)
            .putInt(WALLPAPER_KEY, wallpaper)
            .putBoolean(COMPACT_KEY, compactMode)
            .apply()
    }

    LaunchedEffect(data.toList(), collapsed.toMap(), darkTheme, wallpaper, history.toList(), materialPrices.toMap(), templates.toList(), compactMode) {
        saveAll()
        dirty = false
        saveMessage = "✓ Сохранено"
        delay(1200)
        saveMessage = ""
    }

    LaunchedEffect(Unit) {
        delay(900)
        showSplash = false
    }

    BackHandler(enabled = dirty) { showExit = true }

    val colors = if (darkTheme) {
        AppColors(
            text = Color(TEXT_LIGHT),
            muted = Color(MUTED_LIGHT),
            primary = Color(CYAN),
            card = Color(CARD_DARK),
            field = Color(FIELD_DARK),
            border = Color(BORDER_BLUE),
            success = Color(SUCCESS),
            danger = Color(DANGER),
            warning = Color(WARNING)
        )
    } else {
        AppColors(
            text = Color(0xFF172554),
            muted = Color(0xFF4B5563),
            primary = Color(CYAN_DARK),
            card = Color(0xEFFFFFFF),
            field = Color(0xFFF8FAFC),
            border = Color(0xFFBAE6FD),
            success = Color(0xFF10B981),
            danger = Color(0xFFEF4444),
            warning = Color(0xFFF59E0B)
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundBrush(wallpaper, darkTheme))
    ) {
        Column(Modifier.fillMaxSize()) {
            Header(
                colors = colors,
                wallpaper = wallpaper,
                darkTheme = darkTheme,
                onWallpaper = {
                    wallpaper = it
                    prefs.edit().putInt(WALLPAPER_KEY, it).apply()
                },
                onTheme = {
                    darkTheme = !darkTheme
                    prefs.edit().putBoolean(THEME_KEY, darkTheme).apply()
                },
                onToggleAll = {
                    val all = TpaNames.all { collapsed[it] == true }
                    TpaNames.forEach { collapsed[it] = !all }
                }
            )

            AppTabs(
                tab = tab,
                colors = colors,
                onTab = { tab = it }
            )

            if (saveMessage.isNotBlank()) {
                Text(
                    saveMessage,
                    color = colors.success,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 2.dp)
                )
            }

            when (tab) {
                0 -> CalculatorScreen(
                    data = data,
                    templates = templates,
                    colors = colors,
                    onData = { index, value ->
                        undoSnapshot = data.toList()
                        data[index] = value
                        dirty = true
                    },
                    onSaveTemplate = { part ->
                        val cleanName = part.name.trim()
                        if (cleanName.isNotBlank()) {
                            templates.add(
                                PartTemplate(
                                    id = System.currentTimeMillis(),
                                    name = cleanName,
                                    material = part.material,
                                    perBox = part.perBox,
                                    boxes = part.boxes,
                                    defect = part.defect,
                                    weight = part.weight,
                                    weightUnit = part.weightUnit,
                                    batchTotal = part.batchTotal
                                )
                            )
                        }
                    },
                    onUseTemplate = { selectedTpa, template ->
                        val selected = selectedTpa
                        undoSnapshot = data.toList()
                        val updated = data[selected].parts + PartData(
                            name = template.name,
                            material = template.material,
                            perBox = template.perBox,
                            boxes = template.boxes,
                            defect = template.defect,
                            weight = template.weight,
                            weightUnit = template.weightUnit,
                            batchTotal = template.batchTotal
                        )
                        data[selected] = data[selected].copy(parts = updated)
                        dirty = true
                    },
                    onArchiveTemplate = { id, archived ->
                        val index = templates.indexOfFirst { it.id == id }
                        if (index >= 0) templates[index] = templates[index].copy(archived = archived)
                    },
                    onDeleteTemplate = { id -> templates.removeAll { it.id == id } },
                    onReset = { showReset = true },
                    onUndo = {
                        undoSnapshot?.let { snapshot ->
                            for (i in data.indices) data[i] = snapshot[i]
                            undoSnapshot = null
                            dirty = true
                        }
                    },
                    canUndo = undoSnapshot != null,
                    compactMode = compactMode,
                    onToggleCompact = {
                        compactMode = !compactMode
                        prefs.edit().putBoolean(COMPACT_KEY, compactMode).apply()
                    },
                    onSaveShift = {
                        val now = SimpleDateFormat(
                            "dd.MM.yyyy HH:mm",
                            Locale.getDefault()
                        ).format(Date())

                        val entries = mutableListOf<HistoryEntry>()
                        data.forEachIndexed { index, d ->
                            if (d.enabled) {
                                d.parts.forEach { part ->
                                    val good = if (index == TpaNames.lastIndex) num(part.perBox) else num(part.perBox) * num(part.boxes)
                                    val defect = num(part.defect)
                                    if (partHasData(part) && (good > 0 || defect > 0)) {
                                        entries.add(
                                            HistoryEntry(
                                                tpa = TpaNames[index],
                                                partName = part.name,
                                                material = part.material,
                                                good = good,
                                                defect = defect,
                                                weight = weightGrams(part)
                                            )
                                        )
                                    }
                                }
                            }
                        }
                        if (entries.isNotEmpty()) {
                            history.add(HistoryReport(now, entries))
                            tab = 2
                        }
                    }
                )

                1 -> ReportScreen(
                    data = data,
                    history = history,
                    materialPrices = materialPrices,
                    colors = colors,
                    onPriceChange = { material, price -> materialPrices[material] = price },
                    onShare = { showShare = true }
                )

                else -> HistoryScreen(
                    history = history,
                    colors = colors,
                    onClear = { history.clear() }
                )
            }
        }
    }

    if (showSplash) {
        Box(Modifier.fillMaxSize().background(backgroundBrush(wallpaper, darkTheme)), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("TPA Premium", color = colors.text, fontSize = 32.sp, fontWeight = FontWeight.ExtraBold)
                Spacer(Modifier.height(8.dp))
                Text("Производственный калькулятор", color = colors.muted, fontSize = 14.sp)
                Spacer(Modifier.height(14.dp))
                Text("GALAXY", color = colors.primary, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
            }
        }
    }

    if (showExit) {
        AlertDialog(
            onDismissRequest = { showExit = false },
            containerColor = if (darkTheme) Color(0xFF102B52) else colors.card,
            title = { Text("Есть изменения", color = colors.text, fontWeight = FontWeight.Bold) },
            text = { Text("Данные ещё сохраняются. Выйти из приложения?", color = colors.muted) },
            confirmButton = {
                TextButton(onClick = { showExit = false; (context as? MainActivity)?.finish() }) { Text("Выйти", color = colors.danger, fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { showExit = false }) { Text("Остаться", color = colors.primary, fontWeight = FontWeight.Bold) } }
        )
    }

    if (showReset) {
        AlertDialog(
            onDismissRequest = { showReset = false },
            containerColor = if (colors.primary == Color(CYAN)) Color(0xFF102B52) else colors.card,
            titleContentColor = colors.text,
            textContentColor = colors.muted,
            shape = RoundedCornerShape(24.dp),
            title = {
                Text(
                    "Очистить все данные?",
                    color = colors.text,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    "Все введённые данные калькулятора будут очищены. История смен останется сохранённой.",
                    color = colors.muted,
                    fontSize = 15.sp
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        undoSnapshot = data.toList()
                        for (i in data.indices) data[i] = TpaData()
                        dirty = true
                        showReset = false
                    }
                ) {
                    Text("Да, очистить", color = colors.danger, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showReset = false }) {
                    Text("Нет", color = colors.primary, fontWeight = FontWeight.Bold)
                }
            }
        )
    }

    if (showShare) {
        ShareDialog(
            text = buildReport(data),
            onDismiss = { showShare = false },
            onShare = { text ->
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, text)
                }
                context.startActivity(
                    Intent.createChooser(intent, "Поделиться отчётом")
                )
                showShare = false
            }
        )
    }

}

private data class AppColors(
    val text: Color,
    val muted: Color,
    val primary: Color,
    val card: Color,
    val field: Color,
    val border: Color,
    val success: Color,
    val danger: Color,
    val warning: Color
)

@Composable
private fun Header(
    colors: AppColors,
    wallpaper: Int,
    darkTheme: Boolean,
    onWallpaper: (Int) -> Unit,
    onTheme: () -> Unit,
    onToggleAll: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 10.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "TPA Premium",
                    color = colors.text,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 0.6.sp
                )
                Text(
                    "Производственный калькулятор",
                    color = colors.muted,
                    fontSize = 13.sp
                )
                Spacer(Modifier.height(5.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(colors.primary.copy(alpha = 0.12f))
                        .border(1.dp, colors.primary.copy(alpha = 0.22f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text("ПРОИЗВОДСТВО • GALAXY", color = colors.primary, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(Modifier.weight(1f)) {
                WallpaperButton(
                    wallpaper = wallpaper,
                    colors = colors,
                    onWallpaper = onWallpaper
                )
            }
            HeaderIconButton(
                text = if (darkTheme) "☀" else "☾",
                colors = colors,
                onClick = onTheme
            )
            HeaderIconButton(
                text = "⤢",
                colors = colors,
                onClick = onToggleAll
            )
        }
    }
}

@Composable
private fun HeaderIconButton(
    text: String,
    colors: AppColors,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .size(46.dp)
            .shadow(10.dp, RoundedCornerShape(14.dp))
            .clip(RoundedCornerShape(14.dp))
            .background(colors.card)
            .border(1.dp, colors.border, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = colors.text, fontSize = 25.sp)
    }
}

@Composable
private fun WallpaperButton(
    wallpaper: Int,
    colors: AppColors,
    onWallpaper: (Int) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val names = listOf("🎨 Ocean", "🌅 Sunrise", "🌌 Galaxy")

    Box {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(14.dp))
                .background(colors.card)
                .border(1.dp, colors.border, RoundedCornerShape(14.dp))
                .clickable { expanded = true }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Фон: ${names[(wallpaper - 1).coerceIn(0, 2)]}",
                color = colors.text,
                fontSize = 15.sp
            )
            Spacer(Modifier.width(10.dp))
            Text("⌄", color = colors.primary, fontSize = 20.sp)
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            names.forEachIndexed { index, name ->
                DropdownMenuItem(
                    text = { Text(name) },
                    onClick = {
                        onWallpaper(index + 1)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun AppTabs(
    tab: Int,
    colors: AppColors,
    onTab: (Int) -> Unit
) {
    val icons = listOf("🧮", "📋", "🕘")
    val names = listOf("Калькулятор", "Отчёт", "История")

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        names.forEachIndexed { index, name ->
            val selected = tab == index
            Column(
                modifier = Modifier
                    .weight(1f)
                    .shadow(8.dp, RoundedCornerShape(18.dp))
                    .clip(RoundedCornerShape(18.dp))
                    .background(
                        if (selected) colors.card.copy(alpha = 1f)
                        else colors.card.copy(alpha = 0.62f)
                    )
                    .border(
                        1.5.dp,
                        if (selected) colors.primary else colors.border.copy(alpha = 0.9f),
                        RoundedCornerShape(18.dp)
                    )
                    .clickable { onTab(index) }
                    .padding(horizontal = 5.dp, vertical = 7.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(
                            if (selected) colors.primary.copy(alpha = 0.18f)
                            else colors.card.copy(alpha = 0.45f)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(icons[index], fontSize = 18.sp)
                }
                Spacer(Modifier.height(3.dp))
                Text(
                    name,
                    color = if (selected) colors.primary else colors.muted,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    fontSize = 11.sp,
                    maxLines = 1
                )
            }
        }
    }

    Spacer(Modifier.height(10.dp))
}

@Composable
private fun CalculatorScreen(
    data: SnapshotStateList<TpaData>,
    templates: List<PartTemplate>,
    colors: AppColors,
    onData: (Int, TpaData) -> Unit,
    onSaveTemplate: (PartData) -> Unit,
    onUseTemplate: (Int, PartTemplate) -> Unit,
    onArchiveTemplate: (Long, Boolean) -> Unit,
    onDeleteTemplate: (Long) -> Unit,
    onReset: () -> Unit,
    onUndo: () -> Unit,
    canUndo: Boolean,
    compactMode: Boolean,
    onToggleCompact: () -> Unit,
    onSaveShift: () -> Unit
) {
    var selectedTpa by remember { mutableStateOf(0) }
    var showTemplates by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(if (compactMode) 6.dp else 10.dp)
    ) {
        QuickActions(
            colors = colors,
            canUndo = canUndo,
            compactMode = compactMode,
            onTemplates = { showTemplates = true },
            onUndo = onUndo,
            onToggleCompact = onToggleCompact
        )

        TpaSelector(
            selected = selectedTpa,
            data = data,
            colors = colors,
            onSelect = { selectedTpa = it }
        )

        if (selectedTpa == TpaNames.lastIndex) {
            TubeTpaCard(
                name = TpaNames[selectedTpa],
                data = data[selectedTpa],
                colors = colors,
                onData = { onData(selectedTpa, it) }
            )
        } else {
            TpaCard(
                name = TpaNames[selectedTpa],
                data = data[selectedTpa],
                colors = colors,
                onSaveTemplate = onSaveTemplate,
                onData = { onData(selectedTpa, it) }
            )
        }

        ActionButton(
            text = "💾  Сохранить смену в историю",
            colors = colors,
            onClick = onSaveShift
        )

        ActionButton(
            text = "🗑  Очистить все данные",
            colors = colors,
            danger = true,
            onClick = onReset
        )

        if (showTemplates) {
            TemplateDialog(
                templates = templates,
                colors = colors,
                onDismiss = { showTemplates = false },
                onUse = { template ->
                    onUseTemplate(selectedTpa, template)
                    showTemplates = false
                },
                onArchive = onArchiveTemplate,
                onDelete = onDeleteTemplate
            )
        }

        Spacer(Modifier.height(25.dp))
    }
}

@Composable
private fun TpaSelector(
    selected: Int,
    data: List<TpaData>,
    colors: AppColors,
    onSelect: (Int) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        TpaNames.forEachIndexed { index, name ->
            val isSelected = selected == index
            val enabled = data[index].enabled
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(13.dp))
                    .background(
                        if (isSelected) colors.primary.copy(alpha = 0.24f)
                        else colors.card
                    )
                    .border(
                        1.dp,
                        if (!enabled) colors.danger.copy(alpha = 0.7f)
                        else if (isSelected) colors.primary else colors.border,
                        RoundedCornerShape(13.dp)
                    )
                    .clickable { onSelect(index) }
                    .padding(horizontal = 11.dp, vertical = 8.dp)
            ) {
                Text(
                    text = if (enabled) name else "× $name",
                    color = when {
                        !enabled -> colors.danger
                        isSelected -> colors.primary
                        else -> colors.text
                    },
                    fontSize = 14.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                )
            }
        }
    }
}

@Composable
private fun TubeTpaCard(
    name: String,
    data: TpaData,
    colors: AppColors,
    onData: (TpaData) -> Unit
) {
    val current = data.parts.firstOrNull() ?: PartData()
    val manufactured = num(current.perBox)
    val defect = num(current.defect)
    val weight = weightGrams(current)
    val batch = num(current.batchTotal)
    val totalBatch = batch + manufactured
    val totalBatchKg = totalBatch * weight / 1000.0

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = colors.card),
        modifier = Modifier.fillMaxWidth().shadow(14.dp, RoundedCornerShape(20.dp)).border(1.dp, colors.border, RoundedCornerShape(20.dp))
    ) {
        Column(
            Modifier.fillMaxWidth().background(
                Brush.linearGradient(listOf(colors.card.copy(alpha = 0.98f), Color(0x992B6AA2)))
            ).padding(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                    .background(colors.primary.copy(alpha = 0.09f))
                    .border(1.dp, colors.primary.copy(alpha = 0.16f), RoundedCornerShape(14.dp))
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(name, color = colors.text, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
                    Text(if (data.enabled) "Участвует в отчёте" else "Исключён из отчёта",
                        color = if (data.enabled) colors.success else colors.danger, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                }
                Switch(
                    checked = data.enabled,
                    onCheckedChange = { onData(data.copy(enabled = it)) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White, checkedTrackColor = colors.success,
                        uncheckedThumbColor = Color.White, uncheckedTrackColor = Color(0xFF6B7280)
                    )
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatPill("ИЗГОТОВЛЕНО", "${fmt(manufactured)} шт", colors.success, colors)
                StatPill("БРАК", "${fmt(defect)} шт", colors.danger, colors)
                StatPill("ВЕС 1 ТРУБЫ", "${format3(weight)} г", colors.primary, colors)
                StatPill("ПАРТИЯ", "${fmt(totalBatch)} шт / ${format3(totalBatchKg)} кг", colors.warning, colors)
            }
            Spacer(Modifier.height(12.dp))
            NumberField(
                label = "▦  Количество изготовленных труб (шт)",
                value = current.perBox, modifier = Modifier.fillMaxWidth(),
                keyboardType = KeyboardType.Number, colors = colors, labelColor = colors.success
            ) { onData(data.copy(parts = listOf(current.copy(perBox = it)))) }
            NumberField(
                label = "✕  Брак (шт)",
                value = current.defect, modifier = Modifier.fillMaxWidth(),
                keyboardType = KeyboardType.Number, colors = colors, labelColor = colors.danger
            ) { onData(data.copy(parts = listOf(current.copy(defect = it)))) }
            Box {
                NumberField(
                    label = if (current.weightUnit == "кг") "Вес 1 трубы (кг)" else "Вес 1 трубы (г)",
                    value = current.weight, modifier = Modifier.fillMaxWidth(),
                    keyboardType = KeyboardType.Decimal, colors = colors
                ) { onData(data.copy(parts = listOf(current.copy(weight = it)))) }
                UnitToggle(
                    value = current.weightUnit, colors = colors,
                    modifier = Modifier.align(Alignment.TopEnd).padding(top = 1.dp, end = 7.dp)
                ) { newUnit ->
                    if (newUnit == current.weightUnit) onData(data)
                    else {
                        val value = num(current.weight)
                        val converted = if (newUnit == "кг") value / 1000.0 else value * 1000.0
                        onData(data.copy(parts = listOf(current.copy(weightUnit = newUnit, weight = formatWeightInput(converted)))))
                    }
                }
            }
            Card(
                shape = RoundedCornerShape(15.dp),
                colors = CardDefaults.cardColors(containerColor = colors.field.copy(alpha = 0.55f)),
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                    .border(1.dp, colors.warning.copy(alpha = 0.16f), RoundedCornerShape(15.dp))
            ) {
                Column(Modifier.padding(10.dp)) {
                    Text("Партия: ${fmt(totalBatch)} шт / ${format3(totalBatchKg)} кг",
                        color = colors.warning, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
                    NumberField(
                        label = "▰  Количество в указанной партии (шт)",
                        value = current.batchTotal, modifier = Modifier.fillMaxWidth(),
                        keyboardType = KeyboardType.Number, colors = colors, labelColor = colors.warning
                    ) { onData(data.copy(parts = listOf(current.copy(batchTotal = it)))) }
                }
            }
        }
    }
}

@Composable
private fun TpaCard(
    name: String,
    data: TpaData,
    colors: AppColors,
    onSaveTemplate: (PartData) -> Unit,
    onData: (TpaData) -> Unit
) {
    val totalGood = data.parts.sumOf { num(it.perBox) * num(it.boxes) }
    val totalDefect = data.parts.sumOf { num(it.defect) }
    val totalBatch = data.parts.sumOf { num(it.batchTotal) + num(it.perBox) * num(it.boxes) }
    val batchWeightKg = data.parts.sumOf { (num(it.batchTotal) + num(it.perBox) * num(it.boxes)) * weightGrams(it) } / 1000.0
    val firstPartWeight = data.parts.firstOrNull()?.let { weightGrams(it) } ?: 0.0

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = colors.card),
        modifier = Modifier
            .fillMaxWidth()
            .shadow(14.dp, RoundedCornerShape(20.dp))
            .border(1.dp, colors.border, RoundedCornerShape(20.dp))
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(
                        listOf(colors.card.copy(alpha = 0.98f), Color(0x992B6AA2))
                    )
                )
                .padding(14.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(colors.primary.copy(alpha = 0.09f))
                    .border(1.dp, colors.primary.copy(alpha = 0.16f), RoundedCornerShape(14.dp))
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(name, color = colors.text, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
                    Text(if (data.enabled) "Участвует в отчёте" else "Исключён из отчёта", color = if (data.enabled) colors.success else colors.danger, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                }
                Switch(
                    checked = data.enabled,
                    onCheckedChange = { onData(data.copy(enabled = it)) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = colors.success,
                        uncheckedThumbColor = Color.White,
                        uncheckedTrackColor = Color(0xFF6B7280)
                    )
                )
            }

            Spacer(Modifier.height(12.dp))
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StatPill("ГОТОВО", "${fmt(totalGood)} шт", colors.success, colors)
                StatPill("БРАК", "${fmt(totalDefect)} шт", colors.danger, colors)
                StatPill("ВЕС 1 ТРУБЫ", "${format3(firstPartWeight)} г", colors.primary, colors)
                StatPill("ПАРТИЯ", "${fmt(totalBatch)} шт / ${format3(batchWeightKg)} кг", colors.warning, colors)
            }
            Spacer(Modifier.height(9.dp))
            Text(
                "Детали",
                color = colors.primary,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(Modifier.height(8.dp))
            Box(
                Modifier.fillMaxWidth().height(2.dp).background(colors.primary.copy(alpha = 0.35f))
            )
            Spacer(Modifier.height(9.dp))

            data.parts.forEachIndexed { partIndex, part ->
                PartEditor(
                    partNumber = partIndex + 1,
                    part = part,
                    colors = colors,
                    canRemove = data.parts.size > 1,
                    onSaveTemplate = onSaveTemplate,
                    onChange = { changed ->
                        val updated = data.parts.toMutableList()
                        updated[partIndex] = changed
                        onData(data.copy(parts = updated))
                    },
                    onRemove = {
                        val updated = data.parts.toMutableList()
                        updated.removeAt(partIndex)
                        onData(data.copy(parts = updated.ifEmpty { listOf(PartData()) }))
                    }
                )
                if (partIndex < data.parts.lastIndex) {
                    Spacer(Modifier.height(10.dp))
                    Box(Modifier.fillMaxWidth().height(1.dp).background(colors.primary.copy(alpha = 0.25f)))
                    Spacer(Modifier.height(10.dp))
                }
            }

            Spacer(Modifier.height(4.dp))
            ActionButton(
                text = "＋  Добавить деталь / замену",
                colors = colors,
                onClick = { onData(data.copy(parts = data.parts + PartData())) }
            )
        }
    }
}

@Composable
private fun StatPill(
    label: String,
    value: String,
    accent: Color,
    colors: AppColors
) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(13.dp))
            .background(colors.field.copy(alpha = 0.72f))
            .border(1.dp, accent.copy(alpha = 0.28f), RoundedCornerShape(13.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(label, color = accent.copy(alpha = 0.9f), fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp)
        Text(value, color = colors.text, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
    }
}

@Composable
private fun PartEditor(
    partNumber: Int,
    part: PartData,
    colors: AppColors,
    canRemove: Boolean,
    onSaveTemplate: (PartData) -> Unit,
    onChange: (PartData) -> Unit,
    onRemove: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(17.dp),
        colors = CardDefaults.cardColors(containerColor = colors.field.copy(alpha = 0.28f)),
        modifier = Modifier.fillMaxWidth().border(1.dp, colors.primary.copy(alpha = 0.14f), RoundedCornerShape(17.dp))
    ) {
        Column(Modifier.padding(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Деталь $partNumber",
            color = colors.warning,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f)
        )
        if (canRemove) {
            TextButton(onClick = onRemove) {
                Text("Удалить", color = colors.danger)
            }
        }
    }

    OutlinedTextField(
        value = part.name,
        onValueChange = { onChange(part.copy(name = it)) },
        label = { Text("Название детали") },
        leadingIcon = { Text("▣", color = colors.primary, fontSize = 16.sp) },
        singleLine = true,
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = colors.text,
            unfocusedTextColor = colors.text,
            focusedContainerColor = colors.field,
            unfocusedContainerColor = colors.field,
            focusedBorderColor = colors.primary,
            unfocusedBorderColor = colors.border.copy(alpha = 0.65f),
            focusedLabelColor = colors.primary,
            unfocusedLabelColor = colors.muted,
            cursorColor = colors.primary
        ),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
    )

    CompactActionButton(
        text = "☆  Сохранить как шаблон",
        colors = colors,
        onClick = { onSaveTemplate(part) }
    )

    FieldLabel("▣  Материал", colors)
    MaterialDropdown(
        value = part.material,
        colors = colors,
        onChange = { onChange(part.copy(material = it)) }
    )

    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        NumberField(
            label = "▦  Готовые: шт. в кор.",
            value = part.perBox,
            modifier = Modifier.weight(1f).padding(top = 8.dp),
            keyboardType = KeyboardType.Number,
            colors = colors,
            labelColor = colors.success
        ) { onChange(part.copy(perBox = it)) }

        BoxCountField(
            value = part.boxes,
            colors = colors,
            modifier = Modifier.weight(1f),
            onChange = { onChange(part.copy(boxes = it)) }
        )
    }

    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        NumberField(
            label = "✕  Брак (шт)",
            value = part.defect,
            modifier = Modifier.weight(1f),
            keyboardType = KeyboardType.Number,
            colors = colors,
            labelColor = colors.danger
        ) { onChange(part.copy(defect = it)) }

        Box(Modifier.weight(1f)) {
            NumberField(
                label = if (part.weightUnit == "кг") "Вес (кг)" else "Вес (г)",
                value = part.weight,
                modifier = Modifier.fillMaxWidth(),
                keyboardType = KeyboardType.Decimal,
                colors = colors
            ) { onChange(part.copy(weight = it)) }

            UnitToggle(
                value = part.weightUnit,
                colors = colors,
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 1.dp, end = 7.dp)
            ) { newUnit ->
                if (newUnit == part.weightUnit) {
                    onChange(part)
                } else {
                    val current = num(part.weight)
                    val converted = if (newUnit == "кг") current / 1000.0 else current * 1000.0
                    onChange(part.copy(weightUnit = newUnit, weight = formatWeightInput(converted)))
                }
            }
        }
    }

    Card(
        shape = RoundedCornerShape(15.dp),
        colors = CardDefaults.cardColors(containerColor = colors.field.copy(alpha = 0.55f)),
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp).border(1.dp, colors.warning.copy(alpha = 0.16f), RoundedCornerShape(15.dp))
    ) {
        Column(Modifier.padding(10.dp)) {
            val good = num(part.perBox) * num(part.boxes)
            val batch = num(part.batchTotal) + good
            Text(
                "Партия: ${fmt(batch)} шт / ${kg(batch, weightGrams(part))} кг",
                color = colors.warning,
                fontSize = 16.sp,
                fontWeight = FontWeight.ExtraBold,
                modifier = Modifier.fillMaxWidth()
            )
            NumberField(
                label = "▰  Всего за партию (шт)",
                value = part.batchTotal,
                modifier = Modifier.fillMaxWidth(),
                keyboardType = KeyboardType.Number,
                colors = colors,
                labelColor = colors.warning
            ) { onChange(part.copy(batchTotal = it)) }
        }
            }
        }
    }
}

@Composable
private fun FieldLabel(text: String, colors: AppColors) {
    Text(
        text,
        color = colors.muted,
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(bottom = 4.dp)
    )
}

@Composable
private fun MaterialDropdown(
    value: String,
    colors: AppColors,
    onChange: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(colors.field)
                .border(1.dp, colors.border.copy(alpha = 0.7f), RoundedCornerShape(14.dp))
                .clickable { expanded = true }
                .padding(horizontal = 16.dp, vertical = 15.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                value,
                color = colors.text,
                fontSize = 16.sp,
                modifier = Modifier.weight(1f)
            )
            Text("⌄", color = colors.muted, fontSize = 20.sp)
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            Materials.forEach {
                DropdownMenuItem(
                    text = { Text(it) },
                    onClick = {
                        onChange(it)
                        expanded = false
                    }
                )
            }
        }
    }

    Spacer(Modifier.height(8.dp))
}

@Composable
private fun UnitToggle(
    value: String,
    colors: AppColors,
    modifier: Modifier = Modifier,
    onChange: (String) -> Unit
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(colors.card.copy(alpha = 0.75f))
            .border(1.dp, colors.border.copy(alpha = 0.7f), RoundedCornerShape(10.dp))
    ) {
        listOf("г", "кг").forEach { unit ->
            Box(
                modifier = Modifier
                    .clickable { onChange(unit) }
                    .background(if (value == unit) colors.primary.copy(alpha = 0.28f) else Color.Transparent)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center
            ) { Text(unit, color = if (value == unit) colors.primary else colors.muted, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable
private fun SmallStepButton(text: String, colors: AppColors, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(colors.primary.copy(alpha = 0.16f))
            .border(1.dp, colors.primary.copy(alpha = 0.45f), RoundedCornerShape(11.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Text(text, color = colors.primary, fontSize = 21.sp, fontWeight = FontWeight.Bold) }
}

@Composable
private fun BoxCountField(value: String, colors: AppColors, modifier: Modifier, onChange: (String) -> Unit) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        SmallStepButton("−", colors) {
            onChange((num(value) - 1.0).coerceAtLeast(0.0).let { if (it == 0.0) "" else it.toLong().toString() })
        }
        NumberField(
            label = "▦  Готовые: кол-во кор.",
            value = value,
            modifier = Modifier.weight(1f),
            keyboardType = KeyboardType.Number,
            colors = colors,
            labelColor = colors.success,
            onValue = { onChange(it.filter(Char::isDigit)) }
        )
        SmallStepButton("+", colors) {
            onChange((num(value) + 1.0).toLong().toString())
        }
    }
}

@Composable
private fun QuickActions(
    colors: AppColors,
    canUndo: Boolean,
    compactMode: Boolean,
    onTemplates: () -> Unit,
    onUndo: () -> Unit,
    onToggleCompact: () -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(7.dp), modifier = Modifier.fillMaxWidth()) {
        CompactActionButton("🔎 Шаблоны", colors, onTemplates, Modifier.weight(1f))
        CompactActionButton("↶ Отменить", colors, onUndo, Modifier.weight(1f), enabled = canUndo)
        CompactActionButton(if (compactMode) "▣ Обычный" else "▣ Компакт", colors, onToggleCompact, Modifier.weight(1f))
    }
}

@Composable
private fun NumberField(
    label: String,
    value: String,
    modifier: Modifier,
    keyboardType: KeyboardType,
    colors: AppColors,
    labelColor: Color = colors.muted,
    onValue: (String) -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = { new ->
            if (
                new.isEmpty() ||
                new.all { it.isDigit() || it == '.' || it == ',' }
            ) {
                onValue(new.replace(',', '.'))
            }
        },
        label = {
            Text(
                label,
                color = labelColor,
                fontSize = 13.sp
            )
        },
        singleLine = true,
        trailingIcon = if (value.isNotEmpty()) {
            {
                Box(
                    modifier = Modifier.size(36.dp).clickable { onValue("") },
                    contentAlignment = Alignment.Center
                ) { Text("×", color = labelColor, fontSize = 20.sp, fontWeight = FontWeight.Bold) }
            }
        } else null,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = colors.text,
            unfocusedTextColor = colors.text,
            focusedContainerColor = colors.field,
            unfocusedContainerColor = colors.field,
            focusedBorderColor = labelColor,
            unfocusedBorderColor = colors.border.copy(alpha = 0.65f),
            focusedLabelColor = labelColor,
            unfocusedLabelColor = labelColor.copy(alpha = 0.9f),
            cursorColor = colors.primary
        ),
        modifier = modifier.padding(vertical = 5.dp)
    )
}

@Composable
private fun ActionButton(
    text: String,
    colors: AppColors,
    danger: Boolean = false,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(50.dp)
            .shadow(8.dp, RoundedCornerShape(16.dp)),
        shape = RoundedCornerShape(16.dp),
        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
            containerColor = if (colors.primary == Color(CYAN)) Color(0xFF315FA8) else Color(0xFF0EA5E9),
            contentColor = Color.White
        )
    ) {
        Text(
            text,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}


@Composable
private fun CompactActionButton(
    text: String,
    colors: AppColors,
    onClick: () -> Unit,
    modifier: Modifier = Modifier.fillMaxWidth(),
    enabled: Boolean = true
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(44.dp),
        shape = RoundedCornerShape(14.dp),
        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
            containerColor = colors.primary.copy(alpha = 0.16f),
            contentColor = colors.text
        ),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 0.dp)
    ) {
        Text(text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ReportScreen(
    data: List<TpaData>,
    history: List<HistoryReport>,
    materialPrices: Map<String, String>,
    colors: AppColors,
    onPriceChange: (String, String) -> Unit,
    onShare: () -> Unit
) {
    val included = data.mapIndexed { i, d -> i to d }
        .filter { it.second.enabled }
        .mapNotNull { (index, tpa) ->
            val parts = tpa.parts.filter(::partHasData)
            if (parts.isNotEmpty()) index to tpa.copy(parts = parts) else null
        }


    included.forEach { (_, tpa) ->
        tpa.parts.forEach { part ->
            val specifiedBatch = num(part.batchTotal)
            val good = num(part.perBox) * num(part.boxes)
            val totalBatch = specifiedBatch + good
            val defect = num(part.defect)
            val weight = weightGrams(part)
            val goodKg = good * weight / 1000.0
            val totalBatchWeightKg = totalBatch * weight / 1000.0
            val defectKg = defect * weight / 1000.0


        }
    }


    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("ОТЧЁТ", color = colors.primary, fontSize = 25.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.4.sp)

        if (included.isEmpty()) {
            InfoCard(
                "Нет включённых ТПА. Во вкладке «Калькулятор» включите нужные станки переключателем «В отчёте».",
                colors
            )
        } else {
            // Отдельный отчёт по каждому включённому станку.
            included.forEach { (index, tpa) ->
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = colors.card),
                    modifier = Modifier.fillMaxWidth().border(1.dp, colors.border, RoundedCornerShape(20.dp))
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(TpaNames[index], color = colors.primary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))

                        tpa.parts.forEachIndexed { partIndex, part ->
                            val isTube = index == TpaNames.lastIndex
                            val specifiedBatch = num(part.batchTotal)
                            val good = if (isTube) num(part.perBox) else num(part.perBox) * num(part.boxes)
                            val totalBatch = specifiedBatch + good
                            val defect = num(part.defect)
                            val weight = weightGrams(part)
                            val specifiedBatchKg = specifiedBatch * weight / 1000.0
                            val goodKg = good * weight / 1000.0
                            val totalBatchWeightKg = totalBatch * weight / 1000.0
                            val defectKg = defect * weight / 1000.0

                            Text(
                                if (isTube) "Трубы" else if (part.name.isBlank()) "Деталь ${partIndex + 1}" else part.name,
                                color = colors.warning,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                            if (isTube) {
                                Text("Изготовлено труб: ${fmt(good)} шт / ${format3(goodKg)} кг", color = colors.success, fontSize = 15.sp)
                                Text("Брак: ${fmt(defect)} шт / ${format3(defectKg)} кг", color = colors.danger, fontSize = 15.sp)
                                Text("Вес 1 трубы: ${format3(weight)} г", color = colors.primary, fontSize = 14.sp)
                                Text("Указанная партия: ${fmt(specifiedBatch)} шт / ${format3(specifiedBatchKg)} кг", color = colors.warning, fontSize = 14.sp)
                                Text("Общая партия: ${fmt(totalBatch)} шт / ${format3(totalBatchWeightKg)} кг", color = colors.warning, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            } else {
                                Text("Материал: ${part.material}", color = colors.muted, fontSize = 13.sp)
                                Text("Указанная партия: ${fmt(specifiedBatch)} шт / ${format3(specifiedBatchKg)} кг", color = colors.warning, fontSize = 14.sp)
                                Text("Готовые за смену: ${fmt(good)} шт / ${format3(goodKg)} кг", color = colors.success, fontSize = 15.sp)
                                Text("Общая партия ТПА: ${fmt(totalBatch)} шт / ${format3(totalBatchWeightKg)} кг", color = colors.warning, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                Text("Брак: ${fmt(defect)} шт / ${format3(defectKg)} кг", color = colors.danger, fontSize = 15.sp)
                            }
                            if (partIndex < tpa.parts.lastIndex) {
                                Spacer(Modifier.height(8.dp))
                                Box(Modifier.fillMaxWidth().height(1.dp).background(colors.primary.copy(alpha = 0.25f)))
                                Spacer(Modifier.height(8.dp))
                            }
                        }
                    }
                }
            }

            ActionButton(
                text = "↗  Поделиться отчётом",
                colors = colors,
                onClick = onShare
            )
        }

        Spacer(Modifier.height(25.dp))
    }
}

@Composable
private fun TemplateDialog(
    templates: List<PartTemplate>,
    colors: AppColors,
    onDismiss: () -> Unit,
    onUse: (PartTemplate) -> Unit,
    onArchive: (Long, Boolean) -> Unit,
    onDelete: (Long) -> Unit
) {
    var search by remember { mutableStateOf("") }
    var archivedOnly by remember { mutableStateOf(false) }
    val filtered = templates.filter { template ->
        template.archived == archivedOnly &&
            (search.isBlank() || template.name.contains(search, ignoreCase = true) || template.material.contains(search, ignoreCase = true))
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = if (colors.primary == Color(CYAN)) Color(0xFF102B52) else colors.card,
        titleContentColor = colors.text,
        textContentColor = colors.muted,
        shape = RoundedCornerShape(24.dp),
        title = {
            Text(
                if (archivedOnly) "Архив деталей" else "Шаблоны деталей",
                color = colors.text,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it },
                    label = { Text("Поиск детали") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = colors.text,
                        unfocusedTextColor = colors.text,
                        focusedBorderColor = colors.primary,
                        unfocusedBorderColor = colors.border,
                        focusedLabelColor = colors.primary,
                        unfocusedLabelColor = colors.muted,
                        cursorColor = colors.primary
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { archivedOnly = false }) { Text("Шаблоны", color = if (!archivedOnly) colors.primary else colors.muted) }
                    TextButton(onClick = { archivedOnly = true }) { Text("Архив", color = if (archivedOnly) colors.primary else colors.muted) }
                }
                if (filtered.isEmpty()) {
                    Text("Ничего не найдено", color = colors.muted, modifier = Modifier.padding(vertical = 12.dp))
                } else {
                    filtered.forEach { template ->
                        Card(
                            colors = CardDefaults.cardColors(containerColor = colors.field),
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                        ) {
                            Column(Modifier.padding(10.dp)) {
                                Text(template.name, color = colors.warning, fontWeight = FontWeight.Bold)
                                Text("Материал: ${template.material}", color = colors.muted, fontSize = 12.sp)
                                Text("Короб: ${template.perBox} шт × ${template.boxes}; вес: ${template.weight} г", color = colors.text, fontSize = 12.sp)
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    TextButton(onClick = { onUse(template) }) { Text("Применить", color = colors.success) }
                                    TextButton(onClick = { onArchive(template.id, !template.archived) }) {
                                        Text(if (template.archived) "Вернуть" else "В архив", color = colors.primary)
                                    }
                                    TextButton(onClick = { onDelete(template.id) }) { Text("Удалить", color = colors.danger) }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Закрыть", color = colors.primary, fontWeight = FontWeight.Bold)
            }
        }
    )
}

@Composable
private fun HistoryScreen(
    history: List<HistoryReport>,
    colors: AppColors,
    onClear: () -> Unit
) {
    var showHistoryClear by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "ИСТОРИЯ СМЕН",
                color = colors.primary,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            if (history.isNotEmpty()) {
                TextButton(onClick = { showHistoryClear = true }) {
                    Text("Очистить", color = colors.danger, fontWeight = FontWeight.Bold)
                }
            }
        }

        if (history.isEmpty()) {
            InfoCard("Сохранённых отчётов пока нет.", colors)
        } else {
            history.asReversed().forEachIndexed { reverseIndex, report ->
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = colors.card),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, colors.border, RoundedCornerShape(20.dp))
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "СМЕНА №${history.size - reverseIndex}",
                                color = colors.primary,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f)
                            )
                            Text(report.date, color = colors.muted, fontSize = 12.sp)
                        }
                        Spacer(Modifier.height(10.dp))
                        report.entries.forEachIndexed { index, entry ->
                            Text(
                                "${entry.tpa}${if (entry.partName.isNotBlank()) " • ${entry.partName}" else ""}",
                                color = colors.warning,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text("Материал: ${entry.material}", color = colors.muted, fontSize = 13.sp)
                            Text("Готовые: ${fmt(entry.good)} шт / ${format3(entry.good * entry.weight / 1000.0)} кг", color = colors.success, fontSize = 14.sp)
                            Text("Брак: ${fmt(entry.defect)} шт / ${format3(entry.defect * entry.weight / 1000.0)} кг", color = colors.danger, fontSize = 14.sp)
                            if (index < report.entries.lastIndex) {
                                Spacer(Modifier.height(8.dp))
                                Box(Modifier.fillMaxWidth().height(1.dp).background(colors.primary.copy(alpha = 0.2f)))
                                Spacer(Modifier.height(8.dp))
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(25.dp))
    }

    if (showHistoryClear) {
        AlertDialog(
            onDismissRequest = { showHistoryClear = false },
            containerColor = if (colors.primary == Color(CYAN)) Color(0xFF102B52) else colors.card,
            titleContentColor = colors.text,
            textContentColor = colors.muted,
            shape = RoundedCornerShape(24.dp),
            title = {
                Text(
                    "Очистить историю?",
                    color = colors.text,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    "Вся сохранённая история смен будет удалена. Отменить это действие после удаления будет нельзя.",
                    color = colors.muted,
                    fontSize = 15.sp
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { onClear(); showHistoryClear = false }
                ) {
                    Text("Да, очистить", color = colors.danger, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showHistoryClear = false }) {
                    Text("Нет", color = colors.primary, fontWeight = FontWeight.Bold)
                }
            }
        )
    }
}

@Composable
private fun InfoCard(text: String, colors: AppColors) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = colors.card)
    ) {
        Text(
            text,
            color = colors.text,
            modifier = Modifier.padding(22.dp)
        )
    }
}

@Composable
private fun ShareDialog(
    text: String,
    onDismiss: () -> Unit,
    onShare: (String) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Поделиться отчётом") },
        text = {
            Text(
                text,
                fontSize = 13.sp,
                modifier = Modifier.verticalScroll(rememberScrollState())
            )
        },
        confirmButton = {
            TextButton(onClick = { onShare(text) }) {
                Text("Поделиться")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Закрыть")
            }
        }
    )
}

private fun buildReport(data: List<TpaData>): String {
    val date = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date())
    val lines = mutableListOf<String>()
    lines += "📊 TPA PREMIUM — ОТЧЁТ"
    lines += "📅 ${date}"
    lines += "━━━━━━━━━━━━━━━━━━━━"
    data.forEachIndexed { index, tpa ->
        if (!tpa.enabled) return@forEachIndexed
        val reportParts = tpa.parts.filter(::partHasData)
        if (reportParts.isEmpty()) return@forEachIndexed
        lines += ""
        lines += "🏭 ${TpaNames.getOrElse(index) { "ТПА" }}"
        lines += "━━━━━━━━━━━━━━━━━━━━"
        reportParts.forEachIndexed { partIndex, part ->
            val isTube = index == TpaNames.lastIndex
            val specifiedBatch = num(part.batchTotal)
            val good = if (isTube) num(part.perBox) else num(part.perBox) * num(part.boxes)
            val totalBatch = specifiedBatch + good
            val defect = num(part.defect)
            val weight = weightGrams(part)
            val specifiedBatchKg = specifiedBatch * weight / 1000.0
            val goodKg = good * weight / 1000.0
            val totalBatchKgPart = totalBatch * weight / 1000.0
            val defectKg = defect * weight / 1000.0
            lines += "🔹 " + if (isTube) "Трубы" else if (part.name.isBlank()) "Деталь " + (partIndex + 1) else part.name
            if (isTube) {
                lines += "   Изготовлено труб: ${fmt(good)} шт / ${format3(goodKg)} кг"
                lines += "   Брак: ${fmt(defect)} шт / ${format3(defectKg)} кг"
                lines += "   Вес 1 трубы: ${format3(weight)} г"
                lines += "   Указанная партия: ${fmt(specifiedBatch)} шт / ${format3(specifiedBatchKg)} кг"
                lines += "   Общая партия: ${fmt(totalBatch)} шт / ${format3(totalBatchKgPart)} кг"
            } else {
                lines += "   Материал: ${part.material}"
                lines += "   Указанная партия: ${fmt(specifiedBatch)} шт / ${format3(specifiedBatchKg)} кг"
                lines += "   Готовые за смену: ${fmt(good)} шт / ${format3(goodKg)} кг"
                lines += "   Общая партия ТПА: ${fmt(totalBatch)} шт / ${format3(totalBatchKgPart)} кг"
                lines += "   Брак: ${fmt(defect)} шт / ${format3(defectKg)} кг"
            }
        }
    }
    return lines.joinToString("\n")
}

private fun formatWeightInput(value: Double): String {
    if (!value.isFinite()) return ""
    val rounded = kotlin.math.round(value * 1000.0) / 1000.0
    return if (rounded == rounded.toLong().toDouble()) rounded.toLong().toString() else rounded.toString().trimEnd('0').trimEnd('.')
}

private fun weightGrams(part: PartData): Double = if (part.weightUnit == "кг") num(part.weight) * 1000.0 else num(part.weight)

private fun partHasData(part: PartData): Boolean =
    part.name.isNotBlank() ||
        num(part.perBox) > 0.0 ||
        num(part.boxes) > 0.0 ||
        num(part.defect) > 0.0 ||
        num(part.weight) > 0.0 ||
        num(part.batchTotal) > 0.0

private fun num(value: String): Double =
    value.replace(',', '.').toDoubleOrNull()?.coerceAtLeast(0.0) ?: 0.0

private fun fmt(value: Double): String =
    if (value % 1.0 == 0.0) value.toLong().toString()
    else format2(value)

private fun format2(value: Double): String =
    String.format(Locale.US, "%.2f", value)

private fun format3(value: Double): String =
    String.format(Locale.US, "%.3f", value)

private fun kg(parts: Double, weight: Double): String =
    format3(parts * weight / 1000.0)

private fun backgroundBrush(wallpaper: Int, dark: Boolean): Brush {
    return if (!dark) {
        when (wallpaper) {
            2 -> Brush.radialGradient(
                listOf(Color(0xFFE0F2FE), Color(0xFFBAE6FD))
            )
            3 -> Brush.linearGradient(
                listOf(Color(0xFFDBEAFE), Color(0xFFBAE6FD))
            )
            else -> Brush.linearGradient(
                listOf(Color(0xFF0EA5E9), Color(0xFF38BDF8))
            )
        }
    } else {
        when (wallpaper) {
            2 -> Brush.radialGradient(
                listOf(Color(0xFF1E3A8A), Color(BG_DARK_1))
            )
            3 -> Brush.linearGradient(
                listOf(Color(0xFF1B3F78), Color(0xFF0A2245), Color(BG_DARK_1), Color(0xFF0B3156))
            )
            else -> Brush.linearGradient(
                listOf(Color(0xFF0C4A6E), Color(0xFF1E3A8A))
            )
        }
    }
}
