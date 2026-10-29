package com.mietschedule.app

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min

/**
 * Список преподавателей и их расписания.
 *
 * ВАЖНО: miet.ru расписание преподавателя НЕ ОТДАЁТ. В бандле сайта
 * (/schedule/assets/index-Bdwhd4-Q.js) всего три эндпоинта — groups, audiences,
 * data — и ни одного teacher; попытки передать teacher=/teacherId=/ФИО дают
 * пустой ответ или HTML-заглушку. Единственный источник — расписания групп.
 *
 * ИДЕНТИФИКАЦИЯ ПРЕПОДАВАТЕЛЯ. Нельзя брать Class.Code: это GUID предмета
 * («402a26bd-3b16-11f1-a7c9-0050569f2356»), и один преподаватель, ведущий
 * три предмета, попадает в список трижды — получалось 3974 «преподавателя»
 * вместо 681. Ключ — нормализованное Class.TeacherFull.
 *
 * Экономия памяти: индекс хранит только ФИО/код/число пар. Сами пары не
 * копируются — они уже лежат в кэше расписаний групп (те самые 343 записи
 * `sched_<группа>`), а [lessonsOf] просто перебирает этот кэш и оставляет строки
 * нужного преподавателя. Копия на 658 преподавателей весила бы десятки мегабайт.
 */
object TeacherIndex {

    /** Преподаватель: ФИО, короткая forma, код-ключ, сколько у него пар. */
    data class Teacher(
        val name: String,
        val short: String,
        val code: String = "",
        val pairCount: Int = 0,
        /**
         * Когда ЭТИМУ преподавателю в последний раз перезапрашивали с сайта
         * расписания его групп (мс). Не путать с общей меткой индекса
         * [KEY_TS]: та одна на всех 658 человек и двигается только полной
         * пересборкой. Без отдельного поля пришлось бы врать — показать
         * «только что» остальным преподавателям, чьи группы не трогали.
         */
        val freshAt: Long = 0L
    )

    private const val KEY_LIST = "teacher_index"
    private const val KEY_TS = "teacher_index_ts"
    private const val TAG = "TeacherIndex"

    /** Индекс живёт неделю: расписание меняется, но не каждый день. */
    private const val TTL = 7L * 24 * 60 * 60 * 1000

    /**
     * Ключ преподавателя — нормализованное ФИО. Пробелы вокруг и множественные
     * пробелы внутри встречаются в ответах сервера и должны давать одного и того
     * же человека, а не двух.
     */
    fun key(fullName: String): String =
        fullName.trim().replace(Regex("\\s+"), " ").lowercase()

    /**
     * Служебные записи вроде «Преподаватель УВЦ» — не человек, а канал, через
     * который одни и те же пары числятся за десятки групп: у них тысячи пар,
     * и в общем списке они только мешают. Но по прямой ссылке их открыть можно.
     */
    fun isService(name: String): Boolean =
        name.contains("УВЦ", ignoreCase = true) ||
            (name.startsWith("Преподаватель", ignoreCase = true) &&
                name.contains("практической", ignoreCase = true))

    /**
     * Собирает индекс преподавателей, перебирая расписания всех групп.
     * Расписания при этом кэшируются (их и так использует режим студента), так что
     * повторный запуск бесплатный — [lessonsOf] достанет пары из кэша.
     */
    /**
     * Пересобирает индекс преподавателей из УЖЕ ЗАПИСАННОГО кэша расписаний,
     * без единого запроса в сеть.
     *
     * Зачем: кнопка «Обновить всё» сначала обновляет 343 расписания, и индекс
     * должен отражать именно их. Прежний [build] ходил на miet.ru повторно —
     * это был второй полный проход по 343 группам после «обновления», и
     * метка обновления уезжала вперёд на минуты. Здесь индекс строится из
     * того, что только что скачано, за секунды и без сети.
     */
    suspend fun buildFromCache(api: MietApi, groups: List<String>): List<Teacher> =
        withContext(Dispatchers.IO) { buildIndex(api, groups) { g -> api.cachedSchedule(g) } }

    suspend fun build(
        api: MietApi,
        groups: List<String>,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }
    ): List<Teacher> = withContext(Dispatchers.IO) {
        // СЕТЬ + запись в кэш: build() сам качает группы, значит обязан и
        // сохранить их. fetchSchedule больше не пишет кэш сам (343 commit()
        // тормозили обновление), поэтому пишем здесь — по одному, этот путь
        // единичный, а не массовый.
        buildIndex(api, groups, onProgress) { g ->
            api.fetchSchedule(g).also { api.cacheSchedule(g, it) }
        }
    }

    /**
     * Общее тело: [loadRaw] отдаёт сырой JSON расписания группы. Сеть там или
     * кэш — решает вызывающий, поэтому [build] и [buildFromCache] отличаются
     * только источником данных и не дублируют 70 строк разбора.
     */
    private suspend fun buildIndex(
        api: MietApi,
        groups: List<String>,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
        loadRaw: (String) -> String?
    ): List<Teacher> {
        val names = ConcurrentHashMap<String, String>()   // key -> ФИО
        val shorts = ConcurrentHashMap<String, String>()  // key -> ФИО краткое
        val slots = ConcurrentHashMap<String, MutableSet<String>>() // key -> слоты

        // 343 группы последовательно — это минуты. Шесть параллельных потоков
        // дают те же ~12 секунд, но сервер не должен от этого захлебнуться,
        // поэтому потоков мало и между ними ничего не спит.
        val progress = AtomicInteger(0)
        val pool = Executors.newFixedThreadPool(6)
        try {
            val futures = groups.map { g ->
                pool.submit {
                    var raw: String? = null
                    try { raw = loadRaw(g) } catch (e: Exception) {
                        Log.w(TAG, "Не получили $g: ${e.message}")
                    }
                    val data: List<Lesson> = if (raw != null) {
                        try { GsonHolder.gson.fromJson(raw, ScheduleResponse::class.java)?.data ?: emptyList() }
                        catch (e: Exception) { emptyList() }
                    } else emptyList()

                    for (l in data) {
                        val c = l.classInfo ?: continue
                        val tName = c.teacherFull?.trim().orEmpty()
                        if (tName.isEmpty()) continue
                        val k = key(tName)
                        names[k] = tName
                        shorts.merge(k, c.teacher?.trim().orEmpty()) { a, b ->
                            if (a.length >= b.length) a else b
                        }
                        // Слот, а не строка: одна пара у преподавателя с 5 группами
                        // числится в расписании каждой из них, но это одна пара.
                        // Аудитория в слоте: пара, разбитая на две подгруппы по
                        // разным аудиториям, — это две пары, иначе pairCount
                        // у препода занижается на число таких разбиений.
                        // Слот считает ПАРЫ ПРЕПОДАВАТЕЛЯ. Два разных занятия в одном
                        // слоте (Овчинников + Власов в 8307) и пара, разбитая на две
                        // подгруппы по разным аудиториям, — это разные пары, поэтому
                        // и Class.Code, и аудитория в слоте обязательны. Иначе
                        // pairCount у препода занижается, а список показывает неверное число пар.
                        // Пара преподавателя считается по Class.Code + аудитории:
                        // два разных занятия в слоте (Овчинников и Власов в 8307)
                        // и пара, разбитая на две подгруппы, — это разные пары.
                        val slot = teacherSlotKey(l)
                        slots.computeIfAbsent(k) { ConcurrentHashMap.newKeySet() }.add(slot)
                    }
                    onProgress(progress.incrementAndGet(), groups.size)
                }
            }
            futures.forEach { it.get() }
        } finally {
            pool.shutdown()
        }

        val result = slots.keys.map { k ->
            Teacher(
                name = names[k] ?: k,
                short = shorts[k] ?: "",
                code = k,
                pairCount = slots[k]?.size ?: 0
            )
        }.sortedBy { it.name }

        api.saveTeacherIndex(KEY_LIST, GsonHolder.gson.toJson(result))
        api.saveTeacherIndexTs(KEY_TS, System.currentTimeMillis())
        Log.i(TAG, "Индекс преподавателей: ${result.size}")
        return result
    }

    /**
     * Ключ метки времени индекса. Сделан публичным, потому что экран «О программе»
     * показывает дату последнего обновления и не должен знать про `private const`.
     */
    const val TS_KEY = KEY_TS

    /** Когда индекс преподавателей последний раз собирали с сайта, мс. */
    fun indexBuiltAt(api: MietApi): Long = api.loadTeacherIndexTs(TS_KEY)

    /** Кэшированный индекс или null, если его нет либо он протух. */
    fun cached(api: MietApi): List<Teacher>? {
        val raw = api.loadTeacherIndex(KEY_LIST) ?: return null
        val ts = api.loadTeacherIndexTs(KEY_TS)
        if (ts <= 0L || System.currentTimeMillis() - ts > TTL) return null
        return try {
            GsonHolder.gson.fromJson(raw, Array<Teacher>::class.java).toList().takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            Log.w(TAG, "Не разобрали индекс: ${e.message}")
            null
        }
    }

    /**
     * БЫСТРОЕ обновление одного преподавателя: ходит в сеть только по его группам.
     *
     * Зачем это отдельной функцией, а не [build]: [build] перезапрашивает все
     * 343 группы ради построения индекса — это минуты. Преподавателю в среднем
     * 1–8 групп, то есть 10–30 секунд вместо минут, и кнопка «Обновить» становится
     * пригодной для нажатия, а не для того, чтобы уйти на кофе.
     *
     * Если групп преподавателя в кэше нет (первый запуск), индекс не на что
     * искать — тогда отдаём false, и вызывающий код падает в полную пересборку.
     */
    suspend fun refreshTeacher(
        api: MietApi,
        groups: List<String>,
        teacherCode: String
    ): Boolean = withContext(Dispatchers.IO) {
        val mine = groupsOf(api, groups, teacherCode)
        if (mine.isEmpty()) return@withContext false

        // Шесть потоков по его собственным группам: их мало, сервер не заметит.
        val pool = Executors.newFixedThreadPool(min(6, mine.size))
        val fetched = java.util.concurrent.ConcurrentHashMap<String, String>()
        try {
            pool.invokeAll(mine.map { g ->
                Callable {
                    runCatching { api.fetchSchedule(g) }
                        .onSuccess { raw ->
                            fetched[g] = raw
                        }
                        .onFailure { Log.w(TAG, "Не обновили $g: ${it.message}") }
                }
            })
        } finally {
            pool.shutdown()
        }
        if (fetched.isEmpty()) return@withContext false

        // Скачанное ОБЯЗАНО лечь в кэш: lessonsOf() ниже читает расписания
        // групп из кэша, а markFresh() пишет метку «обновлено только что».
        // Без записи экран показывал бы старые пары преподавателя под свежей
        // подписью — обновление выглядело успешным, а данные не менялись.
        api.saveSchedules(fetched)

        // Метка свежести — только ему, в его запись. Общая KEY_TS не трогаем:
        // она принадлежит полной пересборке [build] и всем 658 преподавателям.
        val fresh = System.currentTimeMillis()
        val lessons = lessonsOf(api, groups, teacherCode)
        markFresh(api, teacherCode, fresh, lessons.size)
        Log.i(TAG, "Быстрое обновление: ${mine.size} групп, ${fetched.size} скачано, ${lessons.size} пар")
        true
    }

    /**
     * Группы, в которых ведёт этот преподаватель — читаются из кэша, без сети.
     *
     * Нужны для быстрого обновления: перезапрашивать все 343 группы ради одного
     * человека — это минуты, а у препода обычно 1–8 групп.
     */
    suspend fun groupsOf(api: MietApi, groups: List<String>, teacherCode: String): List<String> =
        withContext(Dispatchers.IO) {
            val gson = GsonHolder.gson
            val out = LinkedHashSet<String>()
            for (g in groups) {
                val raw = api.cachedSchedule(g) ?: continue
                val data: List<Lesson> = try {
                    gson.fromJson(raw, ScheduleResponse::class.java)?.data ?: emptyList()
                } catch (e: Exception) {
                    continue
                }
                if (data.any { key(it.classInfo?.teacherFull.orEmpty()) == teacherCode }) out.add(g)
            }
            out.toList()
        }

    /**
     * Обновляет метку свежести ОДНОГО преподавателя, не пересобирая индекс.
     *
     * Пересборка [build] ходит в сеть по всем 343 группам — это минуты. Здесь
     * же мы уже перезапросили его группы, поэтому честнее дописать свежесть
     * прямо в его запись. Общая метка [KEY_TS] при этом НЕ трогается: она
     * принадлежит полной пересборке и всем остальным преподавателям сразу.
     */
    fun markFresh(api: MietApi, teacherCode: String, ts: Long, pairCount: Int) {
        val raw = api.loadTeacherIndex(KEY_LIST) ?: return
        // Именно toMutableList(): toList() даёт неизменяемый список, и запись
        // list[i] = … не компилируется («No 'set' operator method providing
        // array access») — ошибка называет индекс, а виноват тип контейнера.
        val list = try {
            GsonHolder.gson.fromJson(raw, Array<Teacher>::class.java).toMutableList()
        } catch (e: Exception) {
            Log.w(TAG, "Индекс не разобрали для метке: ${e.message}")
            return
        }
        val i = list.indexOfFirst { it.code == teacherCode }
        if (i < 0) return
        val t = list[i]
        list[i] = t.copy(
            freshAt = maxOf(t.freshAt, ts),
            pairCount = if (pairCount > 0) pairCount else t.pairCount
        )
        api.saveTeacherIndex(KEY_LIST, GsonHolder.gson.toJson(list))
    }

    /** Свежесть своих пар у конкретного преподавателя, мс. 0 — неизвестно. */
    fun freshAtOf(api: MietApi, teacherCode: String): Long {
        val raw = api.loadTeacherIndex(KEY_LIST) ?: return 0L
        return try {
            GsonHolder.gson.fromJson(raw, Array<Teacher>::class.java)
                .firstOrNull { it.code == teacherCode }?.freshAt ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    /**
     * Пары преподавателя, собранные из кэша расписаний групп.
     *
     * Одна и та же пара встречается в расписании каждой группы, которую ведёт
     * преподаватель, — поэтому дедуплицируем по (день, неделя, номер пары, группа).
     * Иначе у преподавателя с 5 группами каждая пара показалась бы 5 раз.
     */
    suspend fun lessonsOf(api: MietApi, groups: List<String>, teacherCode: String): List<Lesson> =
        withContext(Dispatchers.IO) {
            val gson = GsonHolder.gson
            val out = LinkedHashMap<String, Lesson>()
            for (g in groups) {
                val raw = api.cachedSchedule(g) ?: continue
                val data: List<Lesson> = try {
                    gson.fromJson(raw, ScheduleResponse::class.java)?.data ?: emptyList()
                } catch (e: Exception) {
                    continue
                }
                for (l in data) {
                    val tf = l.classInfo?.teacherFull ?: continue
                    if (key(tf) != teacherCode) continue
                    // Аудитория в ключе — по той же причине, что в localLessonsOf:
                    // пара, бьтая на две подгруппы по разным аудиториям (ТЭ-26-13О
                    // в четверг 2-й парой: 8307 и 8306), — это ДВЕ разные строки
                    // для преподавателя, а не одна. Без аудитории в ключе вторая
                    // затирала первую, и у препода терялась пара.
                    // Слот + Class.Code + аудитория — по тем же причинам, что в
                    // MietApi.localLessonsOf. Для преподавателя важно особенно:
                    // Овчинников и Власов в одной паре различает ТОЛЬКО Class.Code
                    // (преподаватель у строк разный, но предмет, аудитория и группа
                    // совпадают), а подгруппы по разным аудиториям — только Room.
                    // Без обоих полей у препода пропадали пары.
                    // Общая функция ключа, а не своя копия: у препода были те же
                    // две беды (два Class.Code в одной паре и подгруппы по разным
                    // аудиториям), и когда их чинили в двух местах отдельно,
                    // исправления разъезжаются. Одна функция — один ключ.
                    out[teacherLessonKey(l)] = l
                }
            }
            out.values.sortedWith(
                compareBy({ it.day ?: 1 }, { it.dayNumber ?: 0 }, { it.time?.code ?: 0 })
            )
        }
}

/**
 * Ключ строки расписания преподавателя. Общая часть с [roomLessonKey] — слот
 * и Class.Code, но Group.Name заменён на ФИО-ключ: у препода в строке важен
 * человек, а не группа, в которую он зашёл в этом семестре.
 *
 * [TeacherIndex.key], а не просто key(): функция объявлена ВНЕ объекта
 * TeacherIndex, и unqualified-вызов не резолвится — компилятор пишет
 * «Unresolved reference 'key'» и указывает на строку с вызовом, а не на то,
 * что одноимённый метод есть, но в другом контейнере.
 */
internal fun teacherLessonKey(l: Lesson): String =
    "${l.day}|${l.dayNumber ?: 0}|${l.time?.code}|" +
        "${l.classInfo?.code}|${TeacherIndex.key(l.classInfo?.teacherFull.orEmpty())}|" +
        roomKey(l.room?.name)

/** Слот для подсчёта числа пар преподавателя: без Group, аудитория значима. */
internal fun teacherSlotKey(l: Lesson): String =
    "${l.day}|${l.dayNumber ?: 0}|${l.time?.code}|" +
        "${l.classInfo?.code}|${roomKey(l.room?.name)}"
