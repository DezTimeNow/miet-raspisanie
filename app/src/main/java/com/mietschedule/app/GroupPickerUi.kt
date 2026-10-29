package com.mietschedule.app

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.List
import androidx.compose.material3.Icon
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf

/**
 * Экран выбора группы: направление → группа.
 *
 * Вынесено из MainActivity: файл вырос до 1334 строк и держал в себе
 * навигацию, три экрана и загрузку данных. Экраны разнесены по файлам,
 * поведение и внешний вид не менялись.
 */
// ───────────────────── выбор группы: направление → группа ─────────────────────


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupPickerScreen(
    api: MietApi,
    prefs: GroupPrefs,
    currentGroup: String?,
    onChosen: (String) -> Unit,
    /**
     * Стрелка «‹»: возврат туда, откуда вошли.
     *
     * Отдельно от домика намеренно. Раньше оба действия были одним
     * параметром onSwitchRole, и домик в шапке вёл на выбор роли вместо
     * главной: на главной он должен возвращать домой, а стрелка — на
     * предыдущий экран, и это разные адреса.
     */
    onBack: () -> Unit = {},
    /** Домик: всегда на главную, из любого места приложения. */
    onHome: () -> Unit = {},
    onRefresh: () -> Unit = {},
    refreshing: Boolean = false,
    refreshNote: String = "",
) {
    var groups by remember { mutableStateOf<List<String>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var openCode by remember { mutableStateOf<String?>(null) }
    var favs by remember { mutableStateOf(setOf<String>()) }

    // Контекст для пересчёта напоминаний. LocalContext — @Composable-функция,
    // поэтому вызывается здесь, а не внутри обработчика нажатия.
    val appCtx = LocalContext.current

    // Избранное перечитываем при каждом входе на экран
    LaunchedEffect(Unit) {
        favs = groups.filter { prefs.isFav(it) }.toSet()
    }
    // Если список групп ещё не подгружен, а избранное уже есть — дочитываем
    LaunchedEffect(groups) {
        if (groups.isNotEmpty()) favs = groups.filter { prefs.isFav(it) }.toSet()
    }

    LaunchedEffect(Unit) {
        val cached = withContext(Dispatchers.IO) { api.cachedGroups() }
        if (cached.isNotEmpty()) { groups = cached; loading = false }
        runCatching { withContext(Dispatchers.IO) { api.fetchGroups() } }
            .onSuccess { groups = it; loading = false }
            .onFailure {
                Log.w("MietPicker", "Не удалось загрузить группы", it)
                if (groups.isEmpty()) {
                    error = "Не удалось загрузить список групп: ${it.message}"
                    loading = false
                }
            }
    }

    val byCode = remember(groups, query) {
        val filtered = if (query.isBlank()) groups
        else groups.filter { it.contains(query.trim(), ignoreCase = true) }
        Faculties.groupByCode(filtered)
    }
    val total = byCode.values.sumOf { it.size }
    val favList = remember(favs, groups) { favs.sorted() }

    Scaffold(
        topBar = {
            // Раньше здесь стоял текст «Сменить роль» СЛЕВА от кнопки обновления.
            // Он шире, чем стрелка «‹» у преподавателя и аудитории, и из-за
            // этого обновление на экране студента уезжало вправо относительно
            // остальных ролей. Теперь шапка общая — MietTopBar, стрелка «‹»
            // ведёт к выбору роли.
            MietTopBar(
                title = "Расписание МИЭТ",
                subtitle = "Выбери свою группу",
                onRefresh = onRefresh,
                onChangeRole = onHome,
                refreshing = refreshing,
                onBack = onBack
            )
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {

            OutlinedTextField(
                value = query,
                onValueChange = { query = it; openCode = null },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                placeholder = { Text("Поиск: ИВТ, ЭН, ИС, Колледж…", fontSize = 14.sp) },
                singleLine = true,
                shape = RoundedCornerShape(12.dp)
            )

            when {
                loading && groups.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    CircularProgressIndicator()
                }
                error != null && groups.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(error!!, color = LocalAppColors.current.error, fontSize = 14.sp, modifier = Modifier.padding(24.dp))
                        Button(onClick = { error = null; loading = true }) { Text("Повторить") }
                    }
                }
                else -> {
                    Text(
                        if (query.isBlank()) "$total ${plural(total, "группа", "группы", "групп")} в ${byCode.size} ${plural(byCode.size, "маркировке", "маркировках", "маркировках")}"
                        else "Найдено: $total",
                        fontSize = 12.sp, color = LocalAppColors.current.muted,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
                    )

                    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 8.dp)) {

                        // ── Избранное ──
                        if (favList.isNotEmpty() && query.isBlank()) {
                            item(key = "favhdr") {
                                FavHeader(count = favList.size)
                            }
                            item(key = "favlist") {
                                Card(
                                    Modifier.fillMaxWidth().padding(vertical = 3.dp),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                                    elevation = CardDefaults.cardElevation(1.dp)
                                ) {
                                    Column(Modifier.padding(10.dp)) {
                                        favList.chunked(2).forEach { pair ->
                                            Row(Modifier.fillMaxWidth()) {
                                                pair.forEach { g ->
                                                    FavChip(g, Modifier.weight(1f)) { prefs.save(g); onChosen(g) }
                                                }
                                                if (pair.size == 1) Spacer(Modifier.weight(1f))
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        byCode.forEach { (code, list) ->
                            item(key = "code_$code") {
                                Card(
                                    Modifier.fillMaxWidth().padding(vertical = 3.dp),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                    elevation = CardDefaults.cardElevation(1.dp)
                                ) {
                                    Column(Modifier.padding(vertical = 4.dp)) {
                                        Row(
                                            Modifier.fillMaxWidth()
                                                .clickable {
                                                    openCode = if (openCode == code) null else code
                                                }
                                                .padding(horizontal = 14.dp, vertical = 10.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(Modifier.weight(1f)) {
                                                Text(code, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                                                Text("${list.size} ${plural(list.size, "группа", "группы", "групп")}", fontSize = 12.sp, color = LocalAppColors.current.muted)
                                            }
                                            Text(
                                                if (openCode == code) "\u2212" else "+",
                                                fontSize = 22.sp, color = MIET_BLUE, fontWeight = FontWeight.Bold
                                            )
                                        }

                                        AnimatedVisibility(visible = openCode == code) {
                                            Column(Modifier.padding(bottom = 6.dp)) {
                                                list.forEach { g ->
                                                    GroupRow(
                                                        group = g,
                                                        isFav = g in favs,
                                                        isCurrent = g == currentGroup,
                                                        onClick = { prefs.save(g); onChosen(g) },
                                                        onFav = {
                favs = favs.toggle(g, prefs)
                // Отметка избранного меняет список групп для напоминаний —
                // пересчитываем будильники сразу, а не по фоновой задаче.
                // Контекст берём ДО лямбды: LocalContext — @Composable-функция,
                // внутри обработчика вызывать её нельзя.
                runCatching { ReminderScheduler.reschedule(appCtx, MietApi(appCtx)) }
            },
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        item { Spacer(Modifier.height(24.dp)) }
                    }
                }
            }
        }
    }
}


/**
 * Русское склонение по числу: 1 группа / 2 группы / 5 групп.
 * Для существительных [one] — форма для 1, [few] — для 2-4, [many] — для остальных.
 * Правило: последние две цифры 11-14 (кроме 11-13 в 11-14) всегда считаются как "много".
 */
internal fun plural(n: Int, one: String, few: String, many: String): String {
    val abs = Math.abs(n) % 100
    return when {
        abs in 11..14 -> many
        abs % 10 == 1 -> one
        abs % 10 in 2..4 -> few
        else -> many
    }
}

private fun Set<String>.toggle(g: String, prefs: GroupPrefs): Set<String> {
    val next = toMutableSet()
    if (prefs.toggleFav(g)) next.add(g) else next.remove(g)
    return next
}


@Composable
private fun FavHeader(count: Int) {
    Row(
        Modifier.fillMaxWidth().padding(start = 14.dp, top = 8.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("★ Избранное", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = LocalAppColors.current.favStar)
        Spacer(Modifier.width(6.dp))
        Text("$count", fontSize = 12.sp, color = LocalAppColors.current.muted)
    }
}

@Composable
private fun FavChip(group: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Card(
        modifier.padding(3.dp).clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = LocalAppColors.current.fav)
    ) {
        Text(
            group, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
        )
    }
}

@Composable
private fun GroupRow(
    group: String,
    isFav: Boolean,
    isCurrent: Boolean,
    onClick: () -> Unit,
    onFav: () -> Unit
) {
    Card(
        Modifier.fillMaxWidth().padding(vertical = 2.dp).clickable { onClick() },
        colors = CardDefaults.cardColors(
            containerColor = if (isCurrent) LocalAppColors.current.currentGroup else LocalAppColors.current.rowGroup
        )
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                group, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f)
            )
            Box(Modifier.clickable { onFav() }) {
                Text(
                    if (isFav) "★" else "☆",
                    fontSize = 17.sp, color = if (isFav) LocalAppColors.current.favStar else LocalAppColors.current.starInactive
                )
            }
        }
    }
}
