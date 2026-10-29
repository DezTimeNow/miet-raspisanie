package com.mietschedule.app

import android.util.Log
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import java.util.Calendar
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.foundation.layout.PaddingValues
import kotlinx.coroutines.delay

/** Выбор преподавателя: инициал → ФИО, с поиском и звездами. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TeacherPickerScreen(
    api: MietApi,
    prefs: GroupPrefs,
    onChosen: (name: String, code: String) -> Unit,
    onBack: () -> Unit,
    onRefresh: () -> Unit = {},
    onChangeRole: () -> Unit = {},
    onAbout: () -> Unit = {},
    refreshing: Boolean = false,
    // Прогресс обновления под шапкой: обновляются все 343 группы, это
    // ~35 секунд, и без текста ожидание выглядит как зависшее приложение.
    refreshNote: String = "",
) {
    var teachers by remember { mutableStateOf<List<TeacherIndex.Teacher>>(emptyList()) }
    var building by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var done by remember { mutableStateOf(0) }
    var total by remember { mutableStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var openLetter by remember { mutableStateOf<String?>(null) }
    var favs by remember { mutableStateOf(prefs.favGroups(Role.TEACHER).toSet()) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        val cached = withContext(Dispatchers.IO) { TeacherIndex.cached(api) }
        if (cached != null) {
            teachers = cached
        } else {
            // Список групп нужен и для индекса. Если его нет (первый запуск,
            // или пользователь сразу выбрал роль «преподаватель»), подгружаем
            // здесь — заставлять его сначала зайти в режим студента нечестно.
            var groups = withContext(Dispatchers.IO) { api.cachedGroups() }
            if (groups.isEmpty()) {
                // ВАЖНО: сетевой вызов обязан идти на Dispatchers.IO. Вызов прямо
                // из тела LaunchedEffect идёт на главном потоке и падает с
                // NetworkOnMainThreadException.
                groups = try { withContext(Dispatchers.IO) { api.fetchGroups() } }
                catch (e: Exception) {
                    Log.w("TeacherPicker", "Список групп не получен", e)
                    emptyList()
                }
            }
            if (groups.isEmpty()) {
                error = "Нужен список групп, а он не загрузился: ${error ?: "нет сети"}"
                return@LaunchedEffect
            }
            building = true
            total = groups.size
            runCatching {
                TeacherIndex.build(api, groups) { d, t ->
                    done = d; progress = if (t > 0) d.toFloat() / t else 0f
                }
            }.onSuccess { teachers = it }
                .onFailure {
                    Log.w("TeacherPicker", "Индекс не собрался", it)
                    error = "Не получилось собрать список: ${it.message}"
                }
            building = false
        }
    }

    // Служебные каналы прячем из общего списка: у них тысячи пар, это не человек.
    val visible = remember(teachers, query) {
        val q = query.trim()
        teachers.filter { !TeacherIndex.isService(it.name) }
            .filter {
                q.isEmpty() ||
                    it.name.contains(q, ignoreCase = true) ||
                    it.short.contains(q, ignoreCase = true)
            }
    }
    val byLetter = remember(visible) {
        visible.groupBy { it.name.take(1).uppercase() }.toSortedMap()
    }

    Scaffold(
        topBar = {
            // Общая шапка MietTopBar. Размер иконки обновления здесь раньше был
            // 24.dp, на остальных экранах 22.dp — при одинаковой кнопке это
            // давало разную ширину и, следовательно, разный слот.
            MietTopBar(
                title = "Преподаватель",
                subtitle = if (teachers.isEmpty()) "Загрузка…"
                else "${byLetter.values.sumOf { it.size }} преподавателей",
                onRefresh = onRefresh,
                onChangeRole = onChangeRole,
                refreshing = refreshing,
                onBack = onBack
            )
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            // Прогресс обновления: обновляются все 343 группы — это ~35 секунд.
            // Без этой строки ожидание выглядит как зависшее приложение.
            if (refreshing && refreshNote.isNotEmpty()) {
                Text(
                    refreshNote,
                    fontSize = 12.sp,
                    color = MIET_BLUE,
                    modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 2.dp)
                )
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it; openLetter = null },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                placeholder = { Text("Поиск: Лупин, Лупин С.С.…", fontSize = 14.sp) },
                singleLine = true,
                shape = RoundedCornerShape(12.dp)
            )

            when {
                error != null -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Text(error!!, color = LocalAppColors.current.error, fontSize = 14.sp, modifier = Modifier.padding(24.dp))
                }
                building -> Column(
                    Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        "Собираю расписания преподавателей",
                        fontSize = 15.sp, fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Собираю из расписаний всех групп. Один раз, потом из кэша.",
                        fontSize = 12.sp, color = LocalAppColors.current.muted
                    )
                    Spacer(Modifier.height(16.dp))
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("Групп обработано: $done из $total", fontSize = 12.sp, color = LocalAppColors.current.muted)
                }
                teachers.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    CircularProgressIndicator()
                }
                else -> {
                    if (favs.isNotEmpty() && query.isBlank()) {
                        FavStrip("★ Избранные преподаватели", favs.toList()) { name ->
                            val t = teachers.firstOrNull { it.name == name }
                            if (t != null) onChosen(t.name, t.code)
                        }
                    }
                    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
                        byLetter.forEach { (letter, list) ->
                            item(key = "l_$letter") {
                                Card(
                                    Modifier.fillMaxWidth().padding(vertical = 3.dp),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                    elevation = CardDefaults.cardElevation(1.dp)
                                ) {
                                    Row(
                                        Modifier.fillMaxWidth()
                                            .clickable {
                                                openLetter = if (openLetter == letter) null else letter
                                            }
                                            .padding(horizontal = 14.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            letter, fontWeight = FontWeight.Bold, fontSize = 15.sp,
                                            color = MIET_BLUE, modifier = Modifier.width(26.dp)
                                        )
                                        Text(
                                            "${list.size}", fontSize = 13.sp, color = LocalAppColors.current.muted,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Text(
                                            if (openLetter == letter) "\u2212" else "+",
                                            fontSize = 20.sp, color = MIET_BLUE, fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                            if (openLetter == letter) {
                                items(list, key = { it.code }) { t ->
                                    Row(
                                        Modifier.fillMaxWidth()
                                            .clip(RoundedCornerShape(8.dp))
                                            .clickable { onChosen(t.name, t.code) }
                                            .padding(start = 40.dp, end = 12.dp, top = 7.dp, bottom = 7.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(Modifier.weight(1f)) {
                                            Text(t.name, fontSize = 13.sp)
                                            Text(
                                                "${t.short} • ${t.pairCount} пар",
                                                fontSize = 12.sp, color = LocalAppColors.current.muted
                                            )
                                        }
                                        IconButton(onClick = {
                                            favs = if (prefs.toggleFavFor(Role.TEACHER, t.name)) {
                                                favs + t.name
                                            } else {
                                                favs - t.name
                                            }
                                        }) {
                                            Icon(
                                                if (t.name in favs) Icons.Filled.Star else Icons.Outlined.StarBorder,
                                                contentDescription = "В избранное",
                                                tint = if (t.name in favs) LocalAppColors.current.favStar else LocalAppColors.current.starInactive,
                                                modifier = Modifier.size(19.dp)
                                            )
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

/** Выбор аудитории: корпус → аудитория. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudiencePickerScreen(
    api: MietApi,
    prefs: GroupPrefs,
    onChosen: (code: Int, name: String) -> Unit,
    onBack: () -> Unit,
    onRefresh: () -> Unit = {},
    onChangeRole: () -> Unit = {},
    onAbout: () -> Unit = {},
    refreshing: Boolean = false,
    // Прогресс обновления под шапкой: обновляются все 343 группы, это
    // ~35 секунд, и без текста ожидание выглядит как зависшее приложение.
    refreshNote: String = "",
) {
    var list by remember { mutableStateOf<List<Audience>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var openBldg by remember { mutableStateOf<String?>(null) }
    var favs by remember { mutableStateOf(prefs.favGroups(Role.AUDIENCE).toSet()) }
    // Прогресс догрузки аудиторий из расписаний: (готово, всего).
    // 0/0 — ещё не стартовало. Без него экран 3-4 секунды (343 запроса
    // параллельно) висит с пустым телом, и это выглядит как зависание.
    var loadProgress by remember { mutableStateOf(0 to 0) }
    // Фильтр свободных. showFree — включён ли, pairNo — на какой паре смотрим.
    var showFree by rememberSaveable { mutableStateOf(false) }
    var pairNo by rememberSaveable { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        // Сначала то, что сервер знает, — это быстро.
        // Кэш пуст после первой установки — тогда идём сразу в сеть.
        // Первый шаг: список с сервера — быстро, но неполный. НЕ показываем
        // его сразу: раньше loading снимался здесь, и экран на 12 секунд
        // висел с неполным списком (136 из 194), а потом список «прыгал»
        // — владелец описал это как микролаг. Теперь неполный список
        // остаётся в памяти, но не рисуется, пока не закончится догрузка.
        val fromServer = withContext(Dispatchers.IO) {
            val c = api.cachedAudiences()
            if (c.isNotEmpty()) c else runCatching { api.fetchAudiences() }.getOrDefault(emptyList())
        }
        if (fromServer.isNotEmpty()) {
            list = fromServer
        }
        // Потом дополняем аудиториями из расписаний: сайт отдаёт 136, а в паре
        // встречается 194 — без этого корпус 8 (8102, 8103, …) вообще не виден.
        //
        // Считать есть только что из кэша. Но если пользователь ни разу не открывал
        // расписание группы, кэша расписаний ещё нет — тогда сначала забираем
        // список групп: их 343, один запрос, зато аудитории появятся сразу.
        // ОДИН проход по кэшу расписаний: функция отдаёт и список аудиторий, и
        // сами пары. Раньше занятость считалась вторым разбором того же кэша,
        // и открытие экрана занимало полминуты вместо нескольких секунд.
        //
        // ПЕРЕСБОРКА ИЗ КЭША ВСЕГДА: deriveAudiencesFromGroups читает кэши
        // расписаний, а когда их нет — качает и САМ кладёт в кэш. Поэтому
        // список аудиторий всегда строится из свежих данных (кэши обновляются
        // фоново каждые 6 часов), без сети, когда кэш тёплый. TTL 7 дней у
        // /audiences больше не при чём: ремонт аудитории отражается в
        // пересборе из кэша, а не в недельном списке с сервера.
        val (extra, lessons) = withContext(Dispatchers.IO) {
            if (api.cachedSchedule(api.cachedGroups().firstOrNull() ?: "") == null) {
                api.deriveAudiencesFromGroups(fetchGroupsIfNeeded = true) { done, total ->
                    loadProgress = done to total
                }
            } else {
                api.deriveAudiencesFromGroups()
            }
        }
        // Индекс занятости — компактная строка на пару, а не 28 МБ расписаний.
        if (lessons.isNotEmpty()) api.saveBusyIndex(lessons)
        // Сохраняем локальный список. Без этого код аудитории искался только
        // в /audiences, где 136 аудиторий из 194 нет: аудитории корпуса 8
        // при запуске приложения и при открытии из избранного давали
        // «Не удалось определить аудиторию». Список уже посчитан — сохраняем
        // его, чтобы поиск кода работал на всех экранах.
        if (extra.isNotEmpty()) api.saveLocalAudiences(extra)

        if (extra.isNotEmpty()) {
            // Склейка по ключу ПОМЕЩЕНИЯ (номер + суффикс), а не по коду.
            // Коды у двух источников не совпадают: в эндпоинте 1202 — это код 121,
            // в расписаниях тот же номер аудитории — код 1202. По коду они разные,
            // и в списке появлялись дубли «8307», «8308», «8309», «4109»,
            // «3304», «3305». По ключу это одно и то же помещение.
            val byKey = LinkedHashMap<String, Audience>()
            for (a in list) {
                if (a.name.isNullOrBlank()) continue
                byKey[roomKey(a.name)] = a
            }
            for (a in extra) {
                if (a.name.isNullOrBlank() || a.code == null) continue
                val k = roomKey(a.name)
                val existing = byKey[k]
                // Если такое помещение уже есть, оставляем то, у которого больше
                // пар в расписаниях: у серверного кода имена короче («8309»),
                // у расписного — точнее («8309 к»), и расписание по нему богаче.
                if (existing == null) {
                    byKey[k] = a
                }
            }
            list = byKey.values.sortedWith(
                compareBy(
                    { roomSortKey(it.name).first },
                    { roomSortKey(it.name).second },
                    { roomSortKey(it.name).third }
                )
            )
        }
        if (list.isEmpty() && error == null) {
            error = "Не удалось загрузить аудитории: проверь интернет"
        }
        // Флаг снимаем в конце в любом случае. Раньше он сбрасывался только
        // внутри onSuccess, и если список приходил из локального индекса, экран
        // навсегда оставался с надписью «Загрузка…» при полном списке из 194.
        loading = false
    }

// Таблица времени пар нужна фильтру «свободные на данную минуту»:
    // чтобы понять, какая пара идёт прямо сейчас, нужны часы каждой пары.
    // На экране аудиторий её нет — расписание не открыто, поэтому берём из
    // кэша. НО ГЛАЗАМИ ПОЛЬЗОВАТЕЛЯ: сетка звонков у групп разная (3-я пара
    // 12:00 у одних, 12:30 у колледжных), и первая попавшаяся таблица давала
    // на чипах чужое время. pairTimesForUser берёт сетку группы пользователя.
    // Одна сборка на весь экран: собирать её в каждом чипе нельзя, это разбор
    // кэша на каждое нажатие.
    //
    // Состояние, а не suspend прямо в remember: чтение идёт из кэша и является
    // suspend-функцией, а remember-блок не suspend. Раньше здесь стоял
    // withContext внутри remember, и файл не компилировался.
    var pairTimes by remember { mutableStateOf<List<PairTime>>(emptyList()) }
    LaunchedEffect(Unit) {
        val fromCache = withContext(Dispatchers.IO) {
            runCatching { api.pairTimesForUser(prefs) }.getOrDefault(emptyList())
        }
        pairTimes = fromCache
    }
    val nowTable = remember(pairTimes) { NowLogic.windowTable(pairTimes) }

    // Тик раз в минуту: «осталось 12 мин» устаревает быстро, а перерисовывать
    // весь список из-за секунд незачем.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            now = System.currentTimeMillis()
        }
    }

    // Номер пары, идущей сейчас, и слова для подписи.
    //
    // Кнопка «Показать свободные» обещает показать аудитории свободные на
    // ДАННУЮ МИНУТУ, то есть в паре, которая идёт прямо сейчас. Раньше фильтр
    // считал занятость по всей учебной неделе и по умолчанию брал все пары:
    // список отсекал комнаты, занятые в паре, которой сегодня нет, и
    // «свободных» на деле не было.
    val (nowPairCode, nowPairGoing) = remember(nowTable, now) {
        NowLogic.pairLabelState(nowTable, now)
    }
    val minutesLeft = remember(nowTable, now) { NowLogic.minutesLeft(nowTable, now) }

    // Занятые комнаты считаются один раз, а не на каждый элемент списка: иначе
    // каждая строка спрашивала бы расписание заново при прокрутке.
    //
    // Фильтр по DayNumber обязателен. Без него комната считалась занятой из-за
    // пары, которой сегодня нет: DayNumber 0..3 — это конкретная УЧЕБНАЯ неделя,
    // и у одной группы в числителе пара в 205, а в знаменателе она же в 310.
    val todayDay = remember { dayIndexFromCalendar(Calendar.getInstance().get(Calendar.DAY_OF_WEEK)) }
    val weekRow = remember { runCatching { WeekType.currentRowIndex() }.getOrDefault(0) }
    // Флаг готовности ИЗ КЭША УДАЛЁН СОЗНАТЕЛЬНО.
    //
    // Раньше он ставился в LaunchedEffect после долгого разбора кэша. Эффект
    // успевал отмениться (Compose пересоздавал composable на list = fromServer),
    // и флаг навсегда оставался false: индекс лежал на диске целым, а кнопка
    // «Показать свободные» показывала «Сегодня пар нет» в обычный учебный день.
    //
    // Теперь вопрос «есть ли индекс» задаётся напрямую из prefs — он дешёвый
    // и всегда отвечает на текуное состояние диска, без гонки с загрузкой.
    //
    // Какую пару смотрим: выбранную вручную, иначе ту, что идёт сейчас.
    // Вручную приоритетнее — человек выбрал конкретную пару, чтобы заранее
    // посмотреть, и перебивать его текущим временем нельзя.
    val activePair = pairNo.toIntOrNull() ?: if (showFree) nowPairCode else null
    val busyKeys = remember(activePair, showFree, todayDay, weekRow) {
        if (!showFree) emptySet()
        else api.busyRoomsCached(todayDay, weekRow, activePair) ?: emptySet()
    }

    // Фильтр «свободные» без выбранной пары и без идущей пары даёт ВСЕ
    // аудитории. Формально верно, но человек читает «221 аудитория свободно»
    // как «все комнаты пусты». Различаем два случая: пар сегодня вообще нет
    // (выходной) и пары есть, но сейчас не время пары.
    val noLessonsToday = remember(todayDay, weekRow) {
        api.busyRoomsCached(todayDay, weekRow, null).isNullOrEmpty()
    }

    val byBldg = remember(list, query, busyKeys, showFree) {
        val q = query.trim()
        val filtered = list.filter { q.isEmpty() || it.name?.contains(q, ignoreCase = true) == true }
            .filter { !showFree || roomKey(it.name) !in busyKeys }
        filtered.groupBy { buildingOf(it.name) }
            // Корпуса — по номеру (1, 3, 4, … 8), служебные («ДК МИЭТ», «УВЦ»,
            // «Виртуальные аудитории», «Аудитории практики», «Прочее») — после них.
            // Раньше toSortedMap() ставил «Аудитории практики» перед «Корпус 1».
            .toList()
            .sortedWith(
                compareBy({ it.first.startsWith("Корпус ") },
                          { it.first.removePrefix("Корпус ").toIntOrNull() ?: Int.MAX_VALUE },
                          { it.first })
            )
            .associate { (bldg, items) -> bldg to items.sortedWith(
                compareBy(
                    { roomSortKey(it.name).first },
                    { roomSortKey(it.name).second },
                    { roomSortKey(it.name).third }
                )
            ) }
    }

    Scaffold(
        topBar = {
            // Та же общая шапка, что и у преподавателя и студента: одинаковый
            // размер иконки обновления (22.dp, не 24.dp) и одинаковый набор
            // соседей, поэтому кнопка занимает один и тот же слот.
            MietTopBar(
                title = "Аудитория",
                subtitle = when {
                    loading -> "Загрузка…"
                    // Фильтр включён, но пар сегодня нет: писать «свободно» было
                    // бы ложью — свободны все, потому что не занята ни одна.
                    showFree && noLessonsToday -> "Сегодня пар нет"
                    showFree -> {
                        val free = list.count { roomKey(it.name) !in busyKeys }
                        val pair = activePair
                        if (pair == null) "$free свободно сейчас"
                        else "$free свободно на паре $pair"
                    }
                    else -> "${list.size} " + plural(list.size, "аудитория", "аудитории", "аудиторий")
                },
                onRefresh = onRefresh,
                onChangeRole = onChangeRole,
                refreshing = refreshing,
                onBack = onBack
            )
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            // Прогресс обновления: обновляются все 343 группы — это ~35 секунд.
            // Без этой строки ожидание выглядит как зависшее приложение.
            if (refreshing && refreshNote.isNotEmpty()) {
                Text(
                    refreshNote,
                    fontSize = 12.sp,
                    color = MIET_BLUE,
                    modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 2.dp)
                )
            }
            // Поиск и кнопка «Показать свободные» — в одной строке.
            //
            // Кнопка переключатель: нажатие включает фильтр занятости по
            // выбранной паре, повторное — снимает. Подпись меняется, чтобы было
            // видно, в каком состоянии список.
            Row(
                Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it; openBldg = null },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Поиск: 8109, 1201 м…", fontSize = 14.sp) },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp)
                )
                Spacer(Modifier.width(8.dp))
                AssistChip(
                    onClick = { showFree = !showFree },
                    label = {
                        Text(
                            if (showFree) "Показать все" else "Показать свободные",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    },
                    leadingIcon = {
                        Icon(
                            if (showFree) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            contentDescription = null,
                            Modifier.size(16.dp),
                        )
                    },
                )
            }

            // Полоса прогресса догрузки: аудитории корпуса 8 (58 из 221)
            // собираются из расписаний всех групп — 343 запроса. Без неё
            // экран несколько секунд висит с пустым телом, и человек читает
            // это как зависание. Полоса снимается вместе с loading.
            if (loading && loadProgress.second > 0) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                    val frac = if (loadProgress.second > 0)
                        loadProgress.first.toFloat() / loadProgress.second else 0f
                    LinearProgressIndicator(
                        progress = { frac },
                        modifier = Modifier.fillMaxWidth().height(4.dp),
                        color = MIET_BLUE,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    )
                    Text(
                        "Загружаю… ${loadProgress.first} из ${loadProgress.second}",
                        fontSize = 12.sp,
                        color = LocalAppColors.current.muted,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                }
            }

            // Фильтр по паре — виден только когда включены свободные.
            //
            // Занятость считается по номеру пары, поэтому его надо уметь задать:
            // в 12:00 и в 15:00 список свободных разный. Пусто — «вся пара недели».
            //
            // ЧИПЫ В ПРОКРУЧИВАЕМОЙ СТРОКЕ, а не в обычной.
            //
            // Жалоба владельца: в вертикальном положении телефона пар было не
            // видно дальше четвёртой. Причина не в данных, а в вёрстке: чипы
            // лежали в Row, который шире экрана не бывает, поэтому всё, что не
            // поместилось, просто обрезалось краем — и выбрать пару 5 или 6
            // было физически нечем. LazyRow прокручивается, и все пары под
            // рукой независимо от ширины телефона.
            if (showFree) {
                val codes = remember(todayDay, weekRow) {
                    // Номера пар берём из индекса занятости, а не из кэша
                    // расписаний: индекс уже на диске и читается мгновенно.
                    // Code 0 — не настоящая пара (у «Разговоров о важном» слота
                    // нет, сервер отдаёт нули), в фильтре он только мешал.
                    api.pairCodesCached(todayDay, weekRow) ?: emptyList()
                }
                // Фильтр работает по конкретной паре, а не по всей неделе.
                // Чипы подписаны временем: «09:40» полезнее, чем «2» — по
                // номеру человек угадывает, а по времени видит сразу.
                Column(Modifier.fillMaxWidth().padding(top = 2.dp)) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            pairChipsTitle(
                                selectedPair = pairNo.toIntOrNull(),
                                nowPair = nowPairCode,
                                nowPairGoing = nowPairGoing,
                                minutesLeft = minutesLeft,
                            ),
                            fontSize = 12.sp,
                            color = LocalAppColors.current.muted,
                            modifier = Modifier.weight(1f),
                        )
                        // Сброс на «вся неделя» стоит здесь же: без него
                        // выбранную пару нельзя отменить, кроме как повторным
                        // нажатием на тот же чип, о котором человек мог и не
                        // помнить.
                        if (pairNo.isNotEmpty()) {
                            TextButton(
                                onClick = { pairNo = "" },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                            ) {
                                Text("Вся неделя", fontSize = 12.sp, color = MIET_BLUE)
                            }
                        }
                    }
                    if (codes.isNotEmpty()) {
                        LazyRow(
                            Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            items(codes, key = { "pair$it" }) { code ->
                                FilterChip(
                                    selected = pairNo == code.toString(),
                                    onClick = {
                                        pairNo = if (pairNo == code.toString()) "" else code.toString()
                                    },
                                    label = {
                                        // Номер и время на одной подписи: номер
                                        // нужен, чтобы считать пары, время —
                                        // чтобы знать, когда.
                                        //
                                        // ОДНА Text с двумя строками, а не Column
                                        // из двух Text: Column внутри label
                                        // FilterChip рисовался, но вторую строку
                                        // не отдавал — на устройстве была видна
                                        // только цифра номера. С переносом строки
                                        // в одном узле обе строки гарантированно
                                        // попадают на экран.
                                        val hm = pairChipLabel(code, pairTimes)
                                        if (hm == code.toString()) {
                                            // Часов нет — остаётся номер. Не
                                            // придумываем время: «6» честнее
                                            // выдуманного «10:00».
                                            Text("$code", fontSize = 12.sp, maxLines = 1)
                                        } else {
                                            Text(
                                                text = buildAnnotatedString {
                                                    append("$code\n")
                                                    withStyle(SpanStyle(fontSize = 9.sp)) {
                                                        append(hm)
                                                    }
                                                },
                                                fontSize = 12.sp,
                                                maxLines = 2,
                                                lineHeight = 13.sp,
                                                textAlign = TextAlign.Center,
                                            )
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }

            when {
                error != null -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Text(error!!, color = LocalAppColors.current.error, fontSize = 14.sp, modifier = Modifier.padding(24.dp))
                }
                loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
                else -> {
                    if (favs.isNotEmpty() && query.isBlank()) {
                        FavStrip("★ Избранные аудитории", favs.toList()) { name ->
                            val a = list.firstOrNull { it.name == name }
                            if (a?.code != null) onChosen(a.code, name)
                        }
                    }
                    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
                        byBldg.forEach { (bldg, items_) ->
                            item(key = "b_$bldg") {
                                Card(
                                    Modifier.fillMaxWidth().padding(vertical = 3.dp),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                    elevation = CardDefaults.cardElevation(1.dp)
                                ) {
                                    Row(
                                        Modifier.fillMaxWidth()
                                            .clickable {
                                                openBldg = if (openBldg == bldg) null else bldg
                                            }
                                            .padding(horizontal = 14.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(Modifier.weight(1f)) {
                                            Text(bldg, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                                            Text("${items_.size} " + plural(items_.size, "аудитория", "аудитории", "аудиторий"),
                     fontSize = 12.sp, color = LocalAppColors.current.muted)
                                        }
                                        Text(
                                            if (openBldg == bldg) "\u2212" else "+",
                                            fontSize = 22.sp, color = MIET_BLUE, fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                            if (openBldg == bldg) {
                                // Ключ — не it.code: код из /audiences оказался внутренним id, и
                                // разные помещения его делят («8307» и «8307 к» — оба
                                // id 234). LazyColumn падал на «Key was already used».
                                // Имя уникально после склейки, поэтому ключ строим от него.
                                items(items_, key = { "a_${roomKey(it.name)}_${it.code}" }) { a ->
                                    Row(
                                        Modifier.fillMaxWidth()
                                            .clip(RoundedCornerShape(8.dp))
                                            .clickable { if (a.code != null) onChosen(a.code, a.name.orEmpty()) }
                                            .padding(start = 14.dp, end = 12.dp, top = 7.dp, bottom = 7.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(a.name.orEmpty(), fontSize = 13.sp, modifier = Modifier.weight(1f))
                                        IconButton(onClick = {
                                            val n = a.name.orEmpty()
                                            favs = if (prefs.toggleFavFor(Role.AUDIENCE, n)) favs + n else favs - n
                                        }) {
                                            Icon(
                                                if (a.name.orEmpty() in favs) Icons.Filled.Star else Icons.Outlined.StarBorder,
                                                contentDescription = "В избранное",
                                                tint = if (a.name.orEmpty() in favs) LocalAppColors.current.favStar else LocalAppColors.current.starInactive,
                                                modifier = Modifier.size(19.dp)
                                            )
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
 * Подпись строки фильтра пар: что именно покажет список.
 *
 * Человек должен понимать смысл нажатой кнопки, не читая код. Различаем
 * четыре случая, иначе подпись врёт:
 *  - пара выбрана вручную: «свободно на выбранной паре»;
 *  - идёт пара сейчас: «свободно сейчас» и сколько до конца;
 *  - перерыв: «сейчас перерыв, свободно на следующей паре»;
 *  - ни то ни другое: не время пар, все комнаты свободны формально.
 */
internal fun pairChipsTitle(
    selectedPair: Int?,
    nowPair: Int,
    nowPairGoing: Boolean,
    minutesLeft: Int?,
): String = when {
    selectedPair != null -> "Занято на паре $selectedPair"
    nowPairGoing && minutesLeft != null -> "Сейчас идёт пара $nowPair, осталось $minutesLeft мин"
    nowPairGoing -> "Сейчас идёт пара $nowPair"
    nowPair > 0 -> "Перерыв — свободно на паре $nowPair"
    else -> "Занято на паре"
}

/**
 * Подпись чипа пары: время, а не номер.
 *
 * «2» мало о чём говорит, «09:40» отвечает сразу — человек, который ищет
 * комнату, смотрит на часы, а не считает пары. Если времени у номера нет,
 * остаётся номер: подпись хуже, но информация не потеряна.
 *
 * Возврат номера вместо часов — это не украшение, а признак битых данных:
 * если хоть часть чипов подписана временем, а часть номером, значит таблица
 * времени собрана не из того источника. Сервер отдаёт полную сетку в поле
 * Times верхнего уровня ответа, и читать надо его, а не пары (см.
 * MietApi.pairTimesFromCache).
 */
internal fun pairChipLabel(code: Int, times: List<PairTime>): String {
    val hm = times.firstOrNull { it.code == code }?.timeFrom
    val parsed = NextLessonLogic.parseHhMm(hm)
    return if (parsed != null) {
        String.format(java.util.Locale.US, "%02d:%02d", parsed[0], parsed[1])
    } else "$code"
}

/**
 * Ключ помещения для склейки двух списков аудиторий.
 *
 * Приложение берёт аудитории из двух источников: эндпоинт /audiences (136 штук)
 * и расписания групп (194 пары «код, имя»). Одни и те же помещения названы
 * по-разному и имеют РАЗНЫЕ коды, поэтому склеивать по коду нельзя:
 *
 *   код 121 «1202 (м)» в эндпоинте  ↔  код 1202 «1202» в расписаниях
 *   код 97  «8309»     в эндпоинте  ↔  код 8309 «8309» в расписаниях
 *   код 144 «3304»     в эндпоинте  ↔  код 3304 «3304» в расписаниях
 *
 * При склейке по коду такие пары не вылавливались, и в списке корпуса 8
 * «8307», «8308» и «8309» показывались по два раза.
 *
 * Ключ — номер аудитории плюс существенный суффикс. Проверено на данных:
 *   - «8307» (код 8307) и «8307 к» (код 234) — РАЗНЫЕ аудитории, «к» важен;
 *   - «3303 а» (код 143) и «3303 м» (код 135) — РАЗНЫЕ, буква значима;
 *   - буквы «м» и «л» — просто уточнение, помещение не различают;
 *   - скобки ВСЕГДА игнорируются: код 98 — это «3105 (к)» в эндпоинте и «3105»
 *     в расписаниях, то есть одна и та же аудитория;
 *   - «3350амЛПО» и «3350бм ЛПО» — РАЗНЫЕ, суффикс берём целиком.
 *
 * Помещения без номера (УВЦ 1, ДК МИЭТ, Виртуальная аудитория 9,
 * Аудитория практической подготовки 7) ключуются по имени.
 */
internal fun roomKey(name: String?): String {
    val s = (name ?: "").trim()
    val digits = Regex("^(\\d{3,4})").find(s) ?: return "name:" + s.lowercase()
    val num = digits.groupValues[1].toInt()
    val rest = s.substring(digits.value.length).trim()
    val outsideParens = Regex("^\\s*[а-яА-Яа-я]+").find(rest)?.value?.trim()?.lowercase() ?: ""
    val essential = if (outsideParens == "м" || outsideParens == "л") "" else outsideParens
    return "num:$num:$essential"
}

/**
 * Порядок аудиторий внутри корпуса: по номеру, а не по строке.
 *
 * Сортировка строками даёт неудобный порядок: «8107» встал бы после «81010»,
 * а «3205 МПСУ» смешалось бы с «3207 м» по буквам. Разбираем номер числом,
 * потом суффикс, потом уже текстовую часть (для УВЦ и прочих без номера).
 */
internal fun roomSortKey(name: String?): Triple<Int, String, String> {
    val s = (name ?: "").trim()
    val digits = Regex("^(\\d{3,4})").find(s)
        ?: return Triple(Int.MAX_VALUE, "", s.lowercase())
    val num = digits.groupValues[1].toInt()
    val rest = s.substring(digits.value.length).trim()
    // Тот же суффикс, что и в roomKey: буква сразу после номера без скобок.
    val outsideParens = Regex("^\\s*[а-яА-Яа-я]+").find(rest)?.value?.trim()?.lowercase() ?: ""
    val essential = if (outsideParens == "м" || outsideParens == "л") "" else outsideParens
    // Третья компонента — чтобы «8307» шёл раньше «8307 к», а при равном
    // суффиксе порядок всё равно был стабильным.
    return Triple(num, essential, s.lowercase())
}

fun buildingOf(name: String?): String {
    val n = name?.trim().orEmpty()
    if (n.isEmpty()) return "Прочее"
    val first = n.firstOrNull()
    if (first != null && first.isDigit()) return "Корпус $first"
    // Без цифры: группируем по первому слову, но «ДК МИЭТ» — это одно название,
    // резать его на «ДК» бессмысленно.
    return when {
        n.startsWith("ДК") -> "ДК МИЭТ"
        n.startsWith("УВЦ") -> "УВЦ"
        n.startsWith("Виртуальная") -> "Виртуальные аудитории"
        n.startsWith("Аудитория") -> "Аудитории практики"
        else -> n.substringBefore(' ').ifEmpty { "Прочее" }
    }
}



/** Плашка избранного в одну строку, общая для трёх экранов выбора. */
@Composable
fun FavStrip(title: String, items: List<String>, onPick: (String) -> Unit) {
    Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
        Text(title, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = LocalAppColors.current.favStar)
        Spacer(Modifier.height(4.dp))
        androidx.compose.foundation.lazy.LazyRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(items, key = { it }) { g ->
                Card(
                    Modifier.clickable { onPick(g) },
                    colors = CardDefaults.cardColors(containerColor = LocalAppColors.current.fav)
                ) {
                    Text(
                        g, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)
                    )
                }
            }
        }
    }
}