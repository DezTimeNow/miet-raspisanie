package com.mietschedule.app

import android.content.Context
import android.util.Log
import androidx.compose.runtime.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember

/**
 * СОСТОЯНИЕ РАСПИСАНИЯ И ЕГО ЗАГРУЗКА.
 *
 * Раньше всё это лежало прямо в теле ScheduleScreen: загрузка занимала
 * 190 строк, а сама функция выходила за 620. Экран не могли править, не
 * рискуя задеть сеть, и наоборот. Здесь отделены данные от отрисовки:
 * наружу отдаются значения и команда load(), а весь разбор кэша, ролей
 * и сборка расписания аудитории живут здесь.
 *
 * Состояние — remember, а не rememberSaveable: расписание восстанавливается
 * из кэша и при повороте перезапрашивается само (LaunchedEffect в экране),
 * поэтому тащить 17 КБ JSON через Bundle незачем.
 */
@Composable
internal fun rememberScheduleData(
    ctx: Context,
    api: MietApi,
    role: Role,
    group: String,
    teacherCode: String,
    roomName: String,
    onLoaded: (Long) -> Unit,
): ScheduleData {
    var lessons by remember { mutableStateOf<List<Lesson>>(emptyList()) }
    var times by remember { mutableStateOf<List<PairTime>>(emptyList()) }
    var semestr by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var refreshing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    /**
     * Разобрать ответ сервера и показать его.
     *
     * Разбор JSON уходит на Dispatchers.IO: неделя МИЭТ — это несколько
     * сотен объектов, и на главном потоке это давало «Skipped 35 frames»
     * при каждом открытии расписания. Состояние `lessons`/`times` пишется
     * уже после разбора, из IO-потока, что Compose допускает через
     * mutableStateOf, но результат применения наружу отдаётся через
     * `onLoaded` — вызовы перерисовки остаются на главном потоке.
     */
    fun apply(raw: String) {
        scope.launch {
            val resp = withContext(Dispatchers.IO) {
                runCatching { GsonHolder.gson.fromJson(raw, ScheduleResponse::class.java) }
                    .getOrNull()
            } ?: return@launch
            lessons = resp.data ?: emptyList()
            // Таблица времени: серверная, но с подстраховкой от самих пар — у части
            // аудиторий сервер отдаёт пустой Times, и карточка осталась бы без времени.
            times = mergeTimes(resp.times, lessons)
            semestr = resp.semestr ?: ""
            // onLoaded здесь НЕ вызывается: он один на все ветки ролей,
            // в конце load(). Дублировать его значило бы обновлять подпись
            // дважды на каждую загрузку.
        }
    }

    fun load(force: Boolean) {
        scope.launch {
            refreshing = true
            error = null

            when (role) {
                Role.STUDENT -> {
                    val cached = withContext(Dispatchers.IO) { api.cachedSchedule(group) }
                    if (cached != null) runCatching { apply(cached) }
                    // Идём на сервер, если принудительно, если кэша нет или
                    // если он ПРОТУХ (6 часов). Раньше здесь стояло только
                    // `lessons.isEmpty()`, и при непустом кэше приложение
                    // месяцами показывало данные, изменившиеся на сайте.
                    // scheduleExpired читает файл кэша, поэтому тоже на IO.
                    // Раньше он звался с главного потока вместе с проверкой
                    // срока годности — это часть тех же 35 пропущенных кадров
                    // на старте, что и разбор JSON ниже.
                    val needNet = force || withContext(Dispatchers.IO) {
                        api.scheduleExpired(group)
                    }
                    if (needNet) {
                        // Отметка «проверено» ставится ИМЕННО здесь, перед
                        // запросом. Раньше она стояла в начале load(), и тогда
                        // подпись «проверено» означала даже показ свежего
                        // кэша без единого обращения к сети.
                        withContext(Dispatchers.IO) { api.markCheck(role, group) }
                        // fetchSchedule больше НЕ пишет кэш сам — иначе 343
                        // commit() посреди обновления съедали всё время. Здесь
                        // одиночный запрос, пишем кэш явно и ровно один раз.
                        runCatching {
                            withContext(Dispatchers.IO) {
                                api.fetchSchedule(group).also { api.cacheSchedule(group, it) }
                            }
                        }
                            .onSuccess { raw -> runCatching { apply(raw) } }
                            .onFailure {
                                Log.w("Schedule", "Не получили $group", it)
                                if (lessons.isEmpty()) error = "Не удалось загрузить расписание: ${it.message}"
                            }
                    }
                }

                Role.AUDIENCE -> {
                    // Расписание аудитории собирается ДВУМЯ источниками:
                    //   1) ответ сервера по `audience=<код>` — может быть ЧУЖИМ;
                    //   2) сборка из кэша расписаний всех групп по ИМЕНИ комнаты —
                    //      это наш источник, он всегда про 8307.
                    //
                    // ГЛАВНОЕ: сборка (2) выполняется ВСЕГДА, а не только когда
                    // `lessons` пуст. Раньше она жила внутри
                    // `if (force || lessons.isEmpty())`, но туда попадал
                    // кэш `aud_*`, заполненный серверным ответом. Он непустой,
                    // условие ложно, сборка не запускается — и экран показывал
                    // 12 чужих пар из «8307 к» вместо 18 своих. Именно это и было
                    // «8307 опять потеряла пары»: кнопка обновления писала в
                    // `aud_*` серверный ответ, и следующий запуск сразу упирался
                    // в непустой кэш.
                    val code = teacherCode.toIntOrNull()
                    if (code == null) {
                        error = "Не удалось определить аудиторию"
                    } else {
                        val wantRoomKey = roomName.trim().takeIf { it.isNotEmpty() }?.let { roomKey(it) }

                        // 1) Кэш ответа сервера — только как быстрый первый экран.
                        val cached = withContext(Dispatchers.IO) { api.cachedAudience(code) }
                        if (cached != null) {
                            val parsed = runCatching {
                                val r = GsonHolder.gson.fromJson(cached, ScheduleResponse::class.java)
                                !r.data.isNullOrEmpty()
                            }.getOrDefault(false)
                            if (parsed) runCatching { apply(cached) }
                        }

                        // 2) Сеть по аудитории — только при явном обновлении.
                        // Обновлять расписания групп здесь нельзя: всю сеть делает
                        // refreshCurrent() параллельно и с пакетной записью.
                        if (force) {
                            Log.i("Schedule", "Аудитория $code: расписания уже обновлены кнопкой")
                            runCatching { withContext(Dispatchers.IO) { api.fetchAudience(code) } }
                                .onFailure { Log.w("Schedule", "Сервер не отдал аудиторию $code", it) }
                        }

                        // 3) СБОРКА ИЗ РАСПИСАНИЙ ГРУПП — всегда.
                        //
                        // Серверный ответ не выигрывает у неё НИКОГДА, потому что
                        // для 8307 он отдаёт 12 строк из соседней «8307 к»
                        // («Диагностика…», «Психология…», ПСИ-21М), а в самой 8307
                        // в это время Информатика/ТЭ-26-13О. Замерено на живом
                        // ответе: настоящих пар в 8307 — 18 в неделю (72 за
                        // семестр), серверных — 12, и они не про 8307.
                        //
                        // Решение принимаем по вопросу «чья это аудитория», а не
                        // «у кого больше строк»: при 12 против 12 побеждал сервер.
                        // Если имя аудитории неизвестно (открыли из избранного,
                        // где хранится только номер), roomKey("") не равен ни
                        // одной комнате — тогда сравнивать нечем, и серверные
                        // данные просто не принимаются, потому что доказанной
                        // принадлежности у них нет.
                        val groups = withContext(Dispatchers.IO) { api.cachedGroups() }
                        if (groups.isEmpty()) {
                            withContext(Dispatchers.IO) { api.fetchGroups() }
                        }
                        val local = withContext(Dispatchers.IO) {
                            api.localLessonsOf(
                                api.cachedGroups(), code,
                                roomName.ifEmpty { group }
                            )
                        }
                        Log.i("Schedule", "Аудитория $code: локально ${local.size} пар")

                        val serverBelongs = wantRoomKey != null && lessons.isNotEmpty() &&
                            lessons.all {
                                val rn = it.room?.name?.trim().orEmpty()
                                rn.isEmpty() || roomKey(rn) == wantRoomKey
                            }
                        val takeLocal = local.isNotEmpty() && !serverBelongs

                        if (takeLocal) {
                            lessons = local
                            // Для аудиторий, которых нет на сайте (все 17 корпуса 8,
                            // корпус 6, УВЦ, виртуальные), серверной таблицы Times
                            // нет — время берём из самих пар.
                            times = mergeTimes(null, local)
                            semestr = "Осенний семестр"
                            error = null
                        } else if (lessons.isEmpty()) {
                            error = "Расписание не найдено для $code"
                        }
                    }
                }
                Role.TEACHER -> {
                    // Расписания преподавателя сервер не отдаёт: собираем из
                    // кэша расписаний групп. Если кэша нет — сначала строим
                    // индекс (это один проход по всем группам).
                    var groups = withContext(Dispatchers.IO) { api.cachedGroups() }
                    if (groups.isEmpty()) {
                        // Сетевой вызов — только на Dispatchers.IO, иначе
                        // NetworkOnMainThreadException.
                        groups = try { withContext(Dispatchers.IO) { api.fetchGroups() } }
                        catch (e: Exception) {
                            Log.w("Schedule", "Список групп не получен", e)
                            emptyList()
                        }
                    }
                    if (groups.isEmpty()) {
                        loading = false; refreshing = false
                        error = "Список групп не загрузился — проверь интернет"
                        return@launch
                    }
                    // ── Обновление по кнопке ──
                    // Раньше здесь стоял только `lessonsOf` (чистый кэш), и
                    // параметр `force` не читался ВООБЩЕ: препод нажимал
                    // «Обновить», приложение перечитывало тот же кэш и
                    // показывало «Обновлено: 3 дня назад» — то есть врало,
                    // ничего не обновив. Теперь force реально ходит в сеть.
                    if (force) {
                        // Сначала дёшево: только его группы (10–30 сек). Если
                        // их в кэше нет — падаем в полную пересборку индекса.
                        val quick = withContext(Dispatchers.IO) {
                            runCatching {
                                TeacherIndex.refreshTeacher(api, groups, teacherCode)
                            }.getOrElse {
                                Log.w("Schedule", "Быстрое обновление не вышло", it); false
                            }
                        }
                        if (!quick) {
                            Log.i("Schedule", "Быстро не вышло — полная пересборка индекса")
                            withContext(Dispatchers.IO) { TeacherIndex.build(api, groups) }
                        }
                    }
                    var found = withContext(Dispatchers.IO) {
                        TeacherIndex.lessonsOf(api, groups, teacherCode)
                    }
                    if (found.isEmpty()) {
                        // Индекс мог устареть: преподаватель взял новую группу.
                        withContext(Dispatchers.IO) { TeacherIndex.build(api, groups) }
                        found = withContext(Dispatchers.IO) {
                            TeacherIndex.lessonsOf(api, groups, teacherCode)
                        }
                    }
                    lessons = found
                    // Расписание преподавателя собирается из кэша расписаний
                    // групп, где верхней таблицы Times нет. Без этого карточка
                    // показывала номер пары, но не время начала и конца.
                    times = mergeTimes(null, found)
                    semestr = "Осенний семестр"
                    if (found.isEmpty()) error = "Пар не найдено — проверь ФИО"
                }
            }
            // Дату перечитываем ЗДЕСЬ, после всех веток ролей: метка в кэше
            // пишется в момент успешной загрузки, и только после этого есть
            // что показывать. Раньше пересчёт стоял в onClick до ответа сервера —
            // это была гонка, и дата на экране оставалась старой.
            onLoaded(withContext(Dispatchers.IO) { api.lastUpdatedAt(role, group) })
            // Напоминания строим из того, что реально лежит в кэше: будильник
            // должен работать без сети, а данные могли обновиться в фоне.
            runCatching { ReminderScheduler.reschedule(ctx, api) }
                .onFailure { Log.w("Schedule", "Напоминания не пересчитаны", it) }
            loading = false
            refreshing = false
        }
    }
    return ScheduleData(
        lessons = lessons,
        times = times,
        semestr = semestr,
        loading = loading,
        refreshing = refreshing,
        error = error,
        load = ::load,
    )
}

/** Данные экрана расписания и команда перезагрузки. */
internal class ScheduleData(
    val lessons: List<Lesson>,
    val times: List<PairTime>,
    val semestr: String,
    val loading: Boolean,
    val refreshing: Boolean,
    val error: String?,
    val load: (Boolean) -> Unit,
)
