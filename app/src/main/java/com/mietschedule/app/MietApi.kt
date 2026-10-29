package com.mietschedule.app

import android.util.Log
import android.content.Context
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Клиент miet.ru.
 * ВАЖНО: API принимает ТОЛЬКО application/x-www-form-urlencoded (с JSON уходит 404).
 * Ответы компактные, поэтому шлём 1 раз и кэшируем локально.
 */
class MietApi(private val context: Context) {

    /**
     * maxRequestsPerHost по умолчанию равен 5, а обновление запускает 6 потоков.
     * Шестой поток всё время ждал остальные, и обновление 343 групп ползло
     * 208 секунд вместо 30. Ставим 8 — с запасом на переподключения.
     */
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .dispatcher(
            okhttp3.Dispatcher().apply {
                maxRequests = 16
                maxRequestsPerHost = 8
            }
        )
        .build()

    private val prefs = context.getSharedPreferences("miet_cache", Context.MODE_PRIVATE)

    private fun post(path: String, body: FormBody?): String {
        val builder = Request.Builder()
            .url(ApiPaths.BASE + path)
            .header("User-Agent", "MIETSchedule/1.0 (Android; student schedule viewer)")
            .header("Accept", "application/json")
        if (body != null) builder.post(body) else builder.post(FormBody.Builder().build())
        client.newCall(builder.build()).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
            return text
        }
    }

    /** Список всех групп (343 шт) */
    /**
     * Список групп с сайта.
     *
     * @param force=true игнорирует кэш и всегда идёт на miet.ru. Кнопка
     *   «Обновить всё» обязана обновлять ВСЁ, а список групп меняется каждый
     *   семестр: при недельном TTL новые группы могли не появиться неделями,
     *   и кнопка обещала полное обновление, а по факту брала старый список.
     */
    fun fetchGroups(force: Boolean = false): List<String> {
        val cached = prefs.getString("groups", null)
        val fresh = prefs.getLong("groups_ts", 0L)
        val ttl = CachePolicy.GROUPS_TTL_MS
        if (!force && cached != null && System.currentTimeMillis() - fresh < ttl) {
            return cached.split("|||").filter { it.isNotBlank() }
        }
        val raw = post(ApiPaths.GROUPS, null)
        val list = runCatching { GsonHolder.gson.fromJson(raw, Array<String>::class.java).toList() }
            .getOrElse { emptyList() }
        // Пустой ответ НЕ кэшируем. Сервер при сбое может отдать [], и раньше
        // такой список записывался как обычный: на неделю (TTL) пропадали все
        // группы, до следующего принудительного обновления. Теперь кэш
        // трогается только непустым ответом, а вызывающий сам разберётся,
        // что список пуст.
        if (list.isNotEmpty()) {
            prefs.edit()
                .putString("groups", list.joinToString("|||"))
                .putLong("groups_ts", System.currentTimeMillis())
                .apply()
        } else {
            Log.w("MietApi", "Список групп пуст, кэш не трогаем")
        }
        return list
    }

    /**
     * Расписание группы.
     *
     * Запись СИНХРОННАЯ (commit), а не apply(). apply() возвращает управление
     * немедленно и пишет в фоне — при 343 группах подряд экран успевал прочитать
     * недописанный кэш: замерено, в файле оказывалось 275 ключей из 343.
     *
     * Синхронизация на самом prefs — потому что refreshCurrent() дёргает 6 потоков
     * одновременно, а SharedPreferences.apply() из шести потоков теряет записи:
     * каждый читает свой снимок и затирает чужой.
     */
    fun fetchSchedule(group: String): String =
        post(ApiPaths.DATA, FormBody.Builder().add("group", group).build())

    /**
     * Пишет расписание группы в кэш.
     *
     * Запись намеренно ОТДЕЛЕНА от сетевого вызова. Раньше каждый из 343
     * запроса сам делал prefs.commit() — синхронную запись на диск, — и обход
     * занимал 208 секунд вместо 30. Теперь сеть идёт быстро и параллельно,
     * а результаты пишутся одним пакетом через [saveSchedules].
     */
    fun cacheSchedule(group: String, raw: String) {
        val key = "sched_${group}"
        prefs.edit().putString(key, raw)
            .putLong("${key}_ts", System.currentTimeMillis())
            .commit()
    }

    // ── Срок жизни кэша расписания ─────────────────────────────────────
    //
    // Раньше срока не было вовсе: кэш группы писался один раз и жил вечно,
    // а условие загрузки `force || lessons.isEmpty()` при непустом кэше сеть
    // не дёргал. Пользователь, открывающий приложение каждый день, месяцами
    // видел расписание, изменившееся на сайте. Теперь кэш протухает, и
    // следующий запуск сам идёт за свежими данными.

    /** Кэш группы протух? Пустой кэш считаем протухшим всегда. */
    fun scheduleExpired(group: String, now: Long = System.currentTimeMillis()): Boolean {
        val raw = prefs.getString("sched_$group", null) ?: return true
        if (raw.isEmpty()) return true
        val ts = prefs.getLong("sched_${group}_ts", 0L)
        if (ts <= 0L) return true
        return now - ts >= CachePolicy.SCHEDULE_TTL_MS
    }

    /** Кэш есть и ещё свежий — можно показывать без обращения к сети. */
    fun scheduleFresh(group: String, now: Long = System.currentTimeMillis()): Boolean =
        !scheduleExpired(group, now)

    /**
     * Пакетная запись расписаний ОДНИМ редактором.
     *
     * 343 отдельных commit() — это 343 синхронных выхода на диск, и они
     * съедали всё время обновления. Один commit() на 343 ключа занимает
     * доли секунды.
     *
     * Метки времени ставятся ОДНОЙ величиной на весь пакет: так дата
     * обновления честная (время завершения записи) и не «прыгает» между
     * группами, а [lastScheduleWriteTs] видит реальную свежесть кэша.
     */
    fun saveSchedules(items: Map<String, String>) {
        if (items.isEmpty()) return
        val ts = System.currentTimeMillis()
        val ed = prefs.edit()
        for ((group, raw) in items) {
            val key = "sched_${group}"
            ed.putString(key, raw)
            ed.putLong("${key}_ts", ts)
        }
        ed.commit()
    }

    /**
     * Расписание аудитории. Параметр — `audience`, значение — ЧИСЛОВОЙ Code из
     * /audiences (например 120, не имя «1201 (м)»). Проверено: отдаёт ~850 пар.
     */
    /**
     * Ответ по аудитории. ПУСТОЙ ОТВЕТ НЕ КЭШИРУЕМ.
     *
     * Сервер отдаёт `Data: []` для аудиторий, которых у него просто нет
     * (все 17 корпуса 8, корпус 6, УВЦ, виртуальные). Такой пустой ответ,
     * попав в кэш, годами выглядит как «расписание есть, но пустое»: при
     * следующем запуске apply() подставит его, lessons окажется непустым,
     * и сборка из кэша расписаний групп не запустится.
     */
    fun fetchAudience(audienceCode: Int): String {
        val key = "aud_$audienceCode"
        val raw = post(ApiPaths.DATA, FormBody.Builder().add("audience", "$audienceCode").build())
        val empty = try {
            val data = GsonHolder.gson.fromJson(raw, ScheduleResponse::class.java)?.data
            data.isNullOrEmpty()
        } catch (e: Exception) {
            true
        }
        if (!empty) {
            prefs.edit().putString(key, raw).putLong("${key}_ts", System.currentTimeMillis()).commit()
        } else {
            prefs.edit().remove(key).remove("${key}_ts").commit()
        }
        return raw
    }

    fun cachedAudience(code: Int): String? = prefs.getString("aud_$code", null)

    /**
     * Когда последний раз РЕАЛЬНО обновили расписание с сайта.
     *
     * При каждой успешной загрузке рядом с данными уже пишется метка `<ключ>_ts`,
     * поэтому берём её, а не «сейчас»: иначе на экране всегда было бы «только что»
     * и пользователь не видел бы, что кнопка реально что-то обновила.
     *
     * Учитываем три источника — так дата отражает весь собранный кэш:
     *  - расписание выбранной группы (`sched_<группа>_ts`);
     *  - расписание аудитории (`aud_<код>_ts`);
     *  - индекс преподавателей (`teachers_*_ts`), из которого считается
     *    расписание преподавателя — там своя, более давняя метка.
     *
     * Берём МАКСИМУМ, а не минимум: «обновлено 2 минуты назад» честнее, чем
     * «5 дней назад» из-за одного старого куска кэша, который всё равно пригодится.
     */
    /**
     * Метка последней реальной записи расписания в кэш, мс.
     *
     * Нужна аудитории и преподавателю: их экран собирается из расписаний
     * ГРУПП, а groups_ts не отражает их свежесть — fetchGroups() при недельном
     * TTL возвращает кэш и метку не двигает. Смотрим на сами ключи расписаний.
     */
    fun lastScheduleWriteTs(): Long {
        // prefs.all — это копия снимка, уже запертого внутри SharedPreferences,
        // держать здесь свой монитор не нужно: он лишь замедлял записи.
        var best = 0L
        val all = prefs.all
        for ((k, v) in all) {
            if (!k.startsWith("sched_") || !k.endsWith("_ts")) continue
            val ts = (v as? Long) ?: 0L
            if (ts > best) best = ts
        }
        return best
    }

    // ── Попытки проверки сервера ───────────────────────────────────────
    //
    // lastUpdatedAt отвечает на вопрос «когда данные легли в кэш».
    // Эти два метода — на другой: «когда мы ПОПЫТАЛИСЬ уточнить их у
    // сайта». Разница видна без сети: попытка была, а данные старые.
    // Подпись в шапке обязана это различать, иначе недельный кэш
    // читается как «обновлено сегодня».

    private fun checkKey(role: Role, entity: String): String = when (role) {
        Role.STUDENT -> "last_check_student_${entity}"
        Role.TEACHER -> "last_check_teacher_${entity}"
        Role.AUDIENCE -> "last_check_aud_${entity}"
    }

    /** Когда последний раз ходили к серверу за этой сущностью. */
    fun lastCheckAt(role: Role, entity: String): Long =
        prefs.getLong(checkKey(role, entity), 0L)

    /**
     * Пометить попытку проверки. Ставится ДО сетевого запроса: метка
     * означает «проверяли», а не «получили свежее».
     */
    fun markCheck(role: Role, entity: String, now: Long = System.currentTimeMillis()) {
        prefs.edit().putLong(checkKey(role, entity), now).apply()
    }

    fun lastUpdatedAt(role: Role, entity: String): Long {
        val candidates = when (role) {
            Role.STUDENT -> listOf(prefs.getLong("sched_${entity}_ts", 0L))
            // Аудитория: ключи БЫЛИ не те, отчего подпись молчала «ещё не
            // обновлялось» даже после успешного обновления.
            //
            // Что приходит: entity = ИМЯ аудитории («8307»), teacherCode = её
            // ВНУТРЕННИЙ числовой id (234). Ключ в кэше — `aud_234_ts`, то есть
            // по имени его не достать НИКОГДА. А `sched_8307_ts` не существует
            // в принципе: расписания хранятся по ГРУППАМ, а не по аудиториям.
            //
            // Занятость аудитории строится из расписаний групп, поэтому честная
            // метка здесь — когда перезапрашивали расписания (группы_ts), плюс
            // метка самого ответа по аудитории, если он есть.
            //
            // И groups_ts тут НЕ годился: fetchGroups() отдаёт кэш, если ему
            // меньше недели, метку не трогает — и подпись застревала на часах
            // давности, хотя расписания только что перезапросили. Честная метка
            // здесь — последняя реальная запись расписания, а не список групп.
            Role.AUDIENCE -> listOfNotNull(
                entity.trim().toIntOrNull()?.let { prefs.getLong("aud_${it}_ts", 0L) },
                lastScheduleWriteTs(),
            )
            // Преподаватель: сперва ЕГО СОБСТВЕННАЯ свежесть (её держит
            // TeacherIndex.refreshTeacher в поле freshAt его записи индекса).
            // Общая метка индекса — запасной вариант на случай, когда
            // быстрого обновления ещё не было ни разу.
            //
            // Раньше тут стоял indexBuiltAt — и это была неверная величина:
            // TeacherIndex.build пишет общий KEY_TS, а последняя ПОЛНАЯ
            // пересборка могла быть месяц назад, тогда как кнопка «Обновить»
            // только что обновила его группы. Показывать «обновлено месяц
            // назад» сразу после нажатия — тоже некорректно, только в другую сторону.
            // Сюда приходит ФИО (выбранное имя), а индекс записан по
            // нормализованному ключу. Без key() поиск молча возвращал бы 0 —
            // и дата молчала бы уезжать на общую метку индекса.
            Role.TEACHER -> listOf(
                TeacherIndex.freshAtOf(this, TeacherIndex.key(entity)),
                TeacherIndex.indexBuiltAt(this),
                prefs.getLong("${KEY_AUD_IDX}_ts", 0L),
            )
        }
        return candidates.filter { it > 0L }.maxOrNull() ?: 0L
    }

    /**
     * Список аудиторий. Кэш недельный: список меняется только при ремонте.
     */
    fun fetchAudiences(): List<Audience> {
        val cached = prefs.getString(KEY_AUD, null)
        val fresh = prefs.getLong("${KEY_AUD}_ts", 0L)
        val ttl = CachePolicy.GROUPS_TTL_MS
        if (cached != null && System.currentTimeMillis() - fresh < ttl) {
            // Кэш читаем через runCatching: оборванная запись в prefs (например,
            // после падения при записи) дала бы JsonSyntaxException прямо в
            // этой строке, и экран аудиторий падал бы целиком.
            val hit = runCatching { GsonHolder.gson.fromJson(cached, Array<Audience>::class.java).toList() }.getOrNull()
            if (hit != null) return hit
            Log.w("MietApi", "Кэш аудиторий битый, читаем с сервера")
        }
        val raw = post(ApiPaths.AUDIENCES, null)
        val list = GsonHolder.gson.fromJson(raw, Array<Audience>::class.java).toList()
        if (list.isNotEmpty()) {
            prefs.edit()
                .putString(KEY_AUD, raw)
                .putLong("${KEY_AUD}_ts", System.currentTimeMillis())
                .apply()
        }
        return list
    }

    fun cachedAudiences(): List<Audience> =
        prefs.getString(KEY_AUD, null)?.let {
            runCatching { GsonHolder.gson.fromJson(it, Array<Audience>::class.java).toList() }
                .getOrNull() ?: emptyList()
        } ?: emptyList()

    // Список аудиторий из кэша расписаний. Без этого ключа поиск кода
    // аудитории при запуске видел только эндпоинт /audiences, а из него
    // 136 аудиторий из 194.
    private val KEY_AUD_LOCAL = "audiences_local"

    private val KEY_AUD_IDX = "audience_index"
    private val KEY_AUD_IDX_TS = "audience_index_ts"

    /**
     * Ключ дедупликации пары аудитории — top-level, чтобы юнит-тест не тянул
     * за собой MietApi (а он требует Context). См. доктринг у одноимённого
     * [MietApi.localLessonsOf], где ключ и применяется.
     */
internal fun roomLessonKey(l: Lesson): String =
    "${l.day}|${l.dayNumber ?: 0}|${l.time?.code}|" +
        "${l.classInfo?.code}|${l.classInfo?.name}|${l.group?.name}|" +
        roomKey(l.room?.name)

    /**
     * Аудитории из кэша расписаний — и заодно сами пары, одним проходом.
     *
     * Считаются из кэша расписаний групп — если кэша нет, возвращает пустой список
     * и выбор аудитории просто покажет серверные 136.
     *
     * Второй элемент пары — ВСЕ пары из этого же прохода. Раньше занятость для
     * кнопки «Показать свободные» считалась ОТДЕЛЬНЫМ разбором кэша: тот же
     * файл читался дважды подряд, и открытие экрана аудитории занимало
     * полминуты вместо нескольких секунд. Теперь данные берутся один раз, а на
     * диск кладётся только индекс занятости.
     */
    suspend fun deriveAudiencesFromGroups(
        fetchGroupsIfNeeded: Boolean = false,
        // Прогресс: (готово, всего). Вызывается по мере завершения групп —
        // экран аудиторий показывает «Загружаю… N из 343» вместо статичной
        // надписи, иначе 15 секунд пустого экрана выглядят как зависание.
        progress: ((Int, Int) -> Unit)? = null,
    ): Pair<List<Audience>, List<Lesson>> {
        val gson = GsonHolder.gson
        val out = LinkedHashMap<Int, Audience>()
        val allLessons = java.util.Collections.synchronizedList(ArrayList<Lesson>())

        var cached = cachedGroups()
        if (cached.isEmpty() && fetchGroupsIfNeeded) {
            cached = try { fetchGroups() } catch (e: Exception) {
                Log.w("MietApi", "Список групп не получен: ${e.message}")
                emptyList()
            }
        }
        if (cached.isEmpty()) return emptyList<Audience>() to emptyList()

        // Расписания групп кэшируются только когда пользователь реально открыл
        // расписание. Аудитории нужны уже на экране выбора, поэтому если кэша
        // нет — забираем расписания напрямую (343 запроса; заодно прогревается
        // кэш для режима преподавателя).
        //
        // ПАРАЛЛЕЛЬНО, а не последовательно: ранний вариант шёл по циклу
        // for (g in cached) и тратил на обход 12+ секунд, из-за чего экран
        // аудиторий выглядел зависшим. Восемь запросов одновременно при
        // maxRequestsPerHost = 8 в OkHttp — обход укладывается в ~3-4 с.
        // Прогресс отдаётся по каждому завершённому запросу, чтобы экран
        // показывал движение, а не пустоту.
        val needFetch = cachedSchedule(cached.first()) == null
        val total = cached.size
        val done = java.util.concurrent.atomic.AtomicInteger(0)

        val raws: Map<String, String?> = if (!needFetch) {
            cached.associateWith { cachedSchedule(it) }
        } else {
            val semaphore = kotlinx.coroutines.sync.Semaphore(8)
            kotlinx.coroutines.coroutineScope {
                cached.map { g ->
                    async {
                        semaphore.withPermit {
                            val raw = try { fetchSchedule(g) } catch (e: Exception) { null }
                            val n = done.incrementAndGet()
                            progress?.invoke(n, total)
                            g to raw
                        }
                    }
                }.awaitAll().toMap()
            }
        }

        for (g in cached) {
            val raw = raws[g] ?: continue
            val data: List<Lesson> = try {
                gson.fromJson(raw, ScheduleResponse::class.java)?.data ?: emptyList()
            } catch (e: Exception) {
                continue
            }
            allLessons.addAll(data)
            for (l in data) {
                val room: RoomInfo = l.room ?: continue
                val code: Int = room.roomCode() ?: continue
                val name: String = room.name?.trim().orEmpty()
                if (name.isEmpty()) continue
                if (code !in out) out[code] = Audience(code = code, name = name)
            }
        }
        // Только что скачанные расписания — в кэш, одним пакетом.
        //
        // Раньше эта функция была ЕДИНСТВЕННЫМ местом, которое качало 343
        // расписания и не сохраняло их. Экран аудиторий с чистым кэшем
        // повторял обход при каждом заходе: 343 запроса и полный парсинг
        // заново. Теперь после первой загрузки второй заход читает кэш —
        // ноль сети, мгновенно. Заодно прогревается кэш для режима
        // преподавателя, которому он тоже нужен.
        if (needFetch) {
            val fetched = raws.mapNotNull { (g, raw) -> raw?.let { g to it } }.toMap()
            if (fetched.isNotEmpty()) saveSchedules(fetched)
        }
        return out.values.toList() to allLessons
    }

    /**
     * Код аудитории по её названию из кэша списка аудиторий.
     *
     * Из избранного приходит только название («8307»), а расписание
     * аудитории запрашивается по коду из /audiences — внутреннему id, у
     * «8307» это 234, а не 8307. Без этого поиска открытие аудитории из
     * избранного заканчивалось ошибкой «Не удалось определить аудиторию».
     *
     * Сравнение по roomKey, а не по точному тексту: в списке и в кэше имя
     * может отличаться пробелами, а roomKey приводит обе формы к одному
     * виду. null, если аудитории нет в кэше — тогда её надо сначала найти
     * в списке на экране выбора.
     */
    fun audienceCodeByName(name: String): Int? {
        val want = roomKey(name)
        if (want.isEmpty()) return null
        return cachedAudiences().firstOrNull { roomKey(it.name) == want }?.code
            ?: localAudiences().firstOrNull { roomKey(it.name) == want }?.code
    }

    /**
     * Аудитории, найденные в кэше расписаний групп.
     *
     * Отдельный кэш, а не пустая функция: список собирался только на экране
     * выбора аудитории и больше нигде не сохранялся. Из-за этого поиск кода
     * при запуске приложения и при открытии из избранного смотрел лишь в
     * /audiences, где 136 аудиторий из 194 нет. Аудитории корпуса 8
     * (8102, 8109, 8307…) в эндпоинте не значатся вовсе, и для них поиск
     * заканчивался «Не удалось определить аудиторию».
     *
     * Правка найдена через отчёт об ошибке: приложение отправило его само,
     * с этим самым текстом ошибки.
     *
     * Здесь нужен кэш, а не [deriveAudiencesFromGroups]: та функция ходит в
     * сеть (343 запроса) и объявлена suspend, а ключ аудитории ищется
     * синхронно — при старте приложения и при открытии из избранного.
     */
    fun localAudiences(): List<Audience> =
        prefs.getString(KEY_AUD_LOCAL, null)?.let {
            runCatching { GsonHolder.gson.fromJson(it, Array<Audience>::class.java).toList() }
                .getOrNull() ?: emptyList()
        } ?: emptyList()

    /** Сохранить локальный список, чтобы поиск кода работал на всех экранах. */
    fun saveLocalAudiences(list: List<Audience>) {
        if (list.isEmpty()) return
        prefs.edit()
            .putString(KEY_AUD_LOCAL, GsonHolder.gson.toJson(list.toTypedArray()))
            .apply()
    }

    /**
     * Пары одной избранной сущности ИЗ КЭША — без сети.
     *
     * Нужна блоку «сейчас и дальше» в главном меню: он стоит на первом экране и
     * обязан появиться мгновенно, а сеть на первом экране запускать нельзя.
     *
     * Роль решает источник, и разница существенная:
     *  - студент — его расписание лежит в кэше по имени группы;
     *  - преподаватель — расписания на сайте нет, пары собираются из кэша всех
     *    групп (TeacherIndex), это медленно, поэтому берём только избранных;
     *  - аудитория — то же самое: пары комнаты разбросаны по расписаниям групп.
     *
     * Пустой результат означает «в кэше нет», а не «пар нет»: вызывающий код
     * показывает блок только при непустом списке, иначе человек увидел бы
     * «пар нет» там, где на самом деле просто ещё не открывал расписание.
     */
    suspend fun cachedLessonsOf(role: Role, value: String): List<Lesson> = when (role) {
        Role.STUDENT -> {
            val raw = cachedSchedule(value)
            if (raw.isNullOrBlank()) emptyList()
            else runCatching {
                GsonHolder.gson.fromJson(raw, ScheduleResponse::class.java)?.data
            }.getOrNull() ?: emptyList()
        }
        Role.TEACHER -> runCatching {
            TeacherIndex.lessonsOf(this, cachedGroups(), TeacherIndex.key(value))
        }.getOrDefault(emptyList())
        Role.AUDIENCE -> {
            val code = audienceCodeByName(value)
            if (code == null) emptyList()
            else runCatching { localLessonsOf(cachedGroups(), code, value) }.getOrDefault(emptyList())
        }
    }

    /**
     * Все пары из кэша расписаний — ОДИН проход по диску.
     *
     * Отдельная функция, потому что список аудиторий и занятость нужны из
     * одних и тех же данных. Два прохода по 28 МБ означали двойное время
     * открытия экрана и вдвое больше работы на телефоне.
     *
     * Только чтение диска: сети здесь нет намеренно — кнопка «Показать
     * свободные» должна работать и без интернета.
     */
    suspend fun cachedLessons(): List<Lesson> {
        val gson = GsonHolder.gson
        val acc = ArrayList<Lesson>()
        for (g in cachedGroups()) {
            val raw = cachedSchedule(g) ?: continue
            val data = runCatching {
                gson.fromJson(raw, ScheduleResponse::class.java)?.data
            }.getOrNull() ?: continue
            acc.addAll(data)
        }
        return acc
    }

    // ───────────────────── Индекс занятости аудиторий ─────────────────────
    //
    // Занятость нужна кнопке «Показать свободные» на экране аудитории, а
    // считать её из кэша расписаний на КАЖДЫЙ вход нельзя: кэш это 28 МБ и
    // 343 расписания, и разбор занимал 30–50 секунд — список открывался
    // полминуты.
    //
    // Поэтому в кэш кладётся не всё расписание, а только занятость: строки
    // «день|учебнаяНеделя|номерПары|комната». На 343 расписания это десятки
    // килобайт вместо 28 МБ, и чтение мгновенное.
    private val KEY_BUSY = "room_busy_index"

    /**
     * Есть ли на диске индекс занятости.
     *
     * Отдельная функция, потому что индекс живёт дольше одного экрана: он был
     * собран при первом открытии расписания, а экран аудиторий может открыться
     * позже, когда кэш расписаний уже пуст. Проверять «есть ли у меня сейчас
     * пары» в этом случае бессмысленно — индекс на диске и есть источник правды.
     */
    fun busyIndexExists(): Boolean =
        !prefs.getString(KEY_BUSY, null).isNullOrBlank()

    /** Сохранить занятость. Записи: `день;неделя;код;ключКомнаты`. */
    fun saveBusyIndex(lessons: List<Lesson>) {
        if (lessons.isEmpty()) return
        val lines = lessons.asSequence()
            .filter { (it.time?.code ?: 0) > 0 }
            .mapNotNull { l ->
                val room = l.room?.name?.trim().orEmpty()
                if (room.isEmpty()) return@mapNotNull null
                val k = roomKey(room)
                if (k.isEmpty()) null else "${(l.day ?: 1) - 1};${l.dayNumber ?: 0};${l.time?.code};$k"
            }
            .distinct()
            .toList()
        prefs.edit().putString(KEY_BUSY, lines.joinToString("\n")).apply()
    }

    /**
     * Занятые комнаты по ключу `день;неделя;код` — из кэша, без разбора расписаний.
     *
     * @param day индекс дня недели 0..6 (понедельник = 0)
     * @param weekRow номер учебной недели DayNumber 0..3
     * @param pairCode номер пары или null — любая пара
     */
    fun busyRoomsCached(day: Int, weekRow: Int, pairCode: Int?): Set<String>? {
        val raw = prefs.getString(KEY_BUSY, null) ?: return null
        val out = HashSet<String>()
        for (line in raw.split("\n")) {
            val p = line.split(';')
            if (p.size != 4) continue
            if (p[0] != day.toString() || p[1] != weekRow.toString()) continue
            if (pairCode != null && p[2] != pairCode.toString()) continue
            out.add(p[3])
        }
        return out
    }

    /** Номера пар, встречающиеся в этот день на этой учебной неделе. */
    fun pairCodesCached(day: Int, weekRow: Int): List<Int>? {
        val raw = prefs.getString(KEY_BUSY, null) ?: return null
        val out = sortedSetOf<Int>()
        for (line in raw.split("\n")) {
            val p = line.split(';')
            if (p.size != 4) continue
            if (p[0] != day.toString() || p[1] != weekRow.toString()) continue
            p[2].toIntOrNull()?.let { out.add(it) }
        }
        return out.toList()
    }

    /**
     * Таблица времени пар из кэша расписаний.
     *
     * Нужна экрану выбора аудитории: фильтр «Показать свободные» обещает
     * показать комнаты свободные на ДАННУЮ МИНУТУ, а для этого надо знать,
     * какая пара идёт прямо сейчас. На этом экране расписание не открыто,
     * поэтому таблица времени нигде в поле зрения — берём из кэша.
     *
     * Собирается из первых попавшихся расписаний: время пары у всех групп
     * одинаковое (это сетка университета), поэтому одного расписания
     * достаточно. Полный обход всех 343 групп не нужен и стоил бы секунд
     * тридцати.
     */
    suspend fun pairTimesFromCache(maxGroups: Int = 12): List<PairTime> {
        val gson = GsonHolder.gson
        var fromLessons: List<PairTime>? = null
        for (g in cachedGroups().take(maxGroups)) {
            val raw = cachedSchedule(g) ?: continue
            val resp = runCatching {
                gson.fromJson(raw, ScheduleResponse::class.java)
            }.getOrNull() ?: continue

            // СНАЧАЛА таблица времени из корня ответа. Она полная — 8 пар,
            // даже если в расписании группы встречаются только пять.
            //
            // Именно из-за этого чипы фильтра подписывались смешанно: часть
            // пар приходила с часами, часть — только номером. Причина в том,
            // что раньше бралась ТОЛЬКО collectTimes(data) — таблица, собранная
            // из самих пар, а в парах кодов 6, 7 и 8 у этой группы просто нет.
            // Сервер отдаёт полную сетку в Times на верхнем уровне, и она
            // лежит рядом, но не читалась.
            val rootTimes = resp?.times
            if (!rootTimes.isNullOrEmpty()) return mergeTimes(rootTimes, emptyList())

            // Запасной путь: таблица из самих пар. Номера там есть, но часов
            // может не быть — тогда подпись чипа остаётся номером.
            val inLessons = collectTimes(resp?.data.orEmpty())
            if (inLessons.isNotEmpty() && fromLessons == null) fromLessons = inLessons
            if (fromLessons != null) return fromLessons
        }
        return fromLessons.orEmpty()
    }

    /**
     * Сетка звонков глазами пользователя.
     *
     * Время пары зависит от группы: 3-я пара у одних начинается в 12:00, у
     * колледжных — в 12:30 (проверено на живом ответе miet.ru). Поэтому
     * «первая попавшаяся» таблица для подписей чипов и для расчёта идущей
     * пары не годится: на экране аудиторий чипы показывали чужое время.
     *
     * Источник — расписание ГРУППЫ, потому что сетка задаётся именно группой.
     * Порядок: своя группа пользователя, затем любая избранная, и только если
     * групп нет вовсе — прежний путь (таблица из кэша). У преподавателя и
     * аудитории своей группы может не быть: тогда поведение остаётся прежним.
     *
     * Собирать таблицу из пар одного расписания дешевле, чем разбирать
     * двенадцать чужих, поэтому сначала идёт этот путь.
     */
    suspend fun pairTimesForUser(prefs: GroupPrefs): List<PairTime> {
        val ownGroup = prefs.loadFor(Role.STUDENT)
        if (!ownGroup.isNullOrBlank()) {
            val own = collectTimes(cachedLessonsOf(Role.STUDENT, ownGroup))
            if (own.isNotEmpty()) return own
        }
        for (group in prefs.favGroups(Role.STUDENT)) {
            val own = collectTimes(cachedLessonsOf(Role.STUDENT, group))
            if (own.isNotEmpty()) return own
        }
        return pairTimesFromCache()
    }

    fun loadAudienceIndex(): String? = prefs.getString(KEY_AUD_IDX, null)
    fun loadAudienceIndexTs(): Long = prefs.getLong(KEY_AUD_IDX_TS, 0L)

    /**
     * Расписание аудитории из кэша расписаний групп.
     *
     * ФИЛЬТР ИМЕНОМ, А НЕ КОДОМ. Код из /audiences — внутренний id, а не номер
     * аудитории: у «8307» он 234, у «1201 (м)» — 120, у «3205» — 9. Считать
     * 136 аудиторий, у которых id != номер (все 136 из 136). Фильтр по коду
     * отдавал у 8307 не 72 пары, а 12 (остальное — от соседнего «8307 к»), а
     * у 42 аудиторий из 136 показывал чужие пары.
     */
    /**
     * КЛЮЧ ДЕДУПЛИКАЦИИ пары аудитории. Вынесен в отдельную функцию, чтобы его
     * можно было проверить тестом: когда он был инлайном, ошибка в нём
     * воспроизводилась и в тесте, и в приложении одновременно.
     *
     * Ключ = слот (день+неделя+пара) + Class.Code + аудитория.
     *
     * Зачем Class.Code. Группа ТЭ-26-13О во вторник 2-й парой в аудитории 8307
     * стоит ДВА разных занятия «Информатика» — Овчинников и Власов. Преподаватель
     * в ключ не входит (у аудитории его роль другая), предмет один, аудитория
     * одна, группа одна — различаются ТОЛЬКО по Class.Code. Ключ без него
     * склеивал две пары в одну, и приложение теряло 4 пары из 12 (замерено на
     * живом ответе miet.ru).
     *
     * Зачем аудитория. В четверг 2-й парой та же группа стоит в 8307 И в 8306
     * одновременно — пара разделена на две подгруппы по разным аудиториям.
     * Слот и Class.Code у этих строк совпадают, поэтому без roomKey одна
     * затирала другую.
     */
    suspend fun localLessonsOf(groups: List<String>, roomCode: Int, roomName: String? = null): List<Lesson> {
        val gson = GsonHolder.gson
        val out = LinkedHashMap<String, Lesson>()
        // Ключ от имени; без имени откатываемся на код (аудитории из кэша).
        val want = roomName?.trim()?.takeIf { it.isNotEmpty() }?.let { roomKey(it) }
        for (g in groups) {
            val raw = cachedSchedule(g) ?: continue
            val data: List<Lesson> = try {
                gson.fromJson(raw, ScheduleResponse::class.java)?.data ?: emptyList()
            } catch (e: Exception) {
                continue
            }
            for (l in data) {
                val lname = l.room?.name?.trim().orEmpty()
                val hit = if (want != null) {
                    lname.isNotEmpty() && roomKey(lname) == want
                } else {
                    l.room?.roomCode() == roomCode
                }
                if (!hit) continue
                // КЛЮЧ ОБЯЗАН ВКЛЮЧАТЬ АУДИТОРИЮ, иначе подгруппы схлопываются.
                //
                // Группа ТЭ-26-13О в четверг 2-й парой делится надвое: чётная
                // неделя в 8307, нечётная — в 8306 (и наоборот). Преподаватель
                // один, предмет один, группа одна — старый ключ был для обеих
                // строк ОДИНАКОВЫМ, и LinkedHashMap оставлял последнюю. Замерено
                // на живом ответе: у 8307 сервер отдаёт 12 строк, в приложение
                // попадало 8 — четыре пары исчезли, потому что «победила» 8306.
                //
                // Подгруппа в данных не выделена: Group.Name у обеих строк
                // одинаковый (ТЭ-26-13О), различается только аудитория. Значит
                // различает их именно Room.
                // КЛЮЧ = слот + Class.Code + аудитория. Оба последних обязательны,
                // и это два РАЗНЫХ случая, а не один:
                //
                // 1) Class.Code — идентичность самого занятия. Группа ТЭ-26-13О
                //    во вторник 2-й парой в 8307 стоит ДВА разных «Информатика»:
                //    Овчинников (Code 9DBB…) и Власов (Code 6B8D…). Преподаватель,
                //    предмет, аудитория, группа — всё одинаковое, различается
                //    ТОЛЬКО Code. Ключ без него ронял 4 пары из 12 (замерено).
                //
                // 2) roomKey — на случай разбиения на подгруппы по разным
                //    аудиториям: в четверг 2-й парой та же группа стоит в 8307
                //    И в 8306 одновременно. Без аудитории одна затирала другую.
                //
                // Проверено на живом ответе ТЭ-26-13О: 8307 — 12 строк, все 12
                // доходят до карточек; 8306 — 4 из 4.
                out[roomLessonKey(l)] = l
            }
        }
        return out.values.sortedWith(
            compareBy({ it.day ?: 1 }, { it.dayNumber ?: 0 }, { it.time?.code ?: 0 })
        )
    }

    /** Последний закэшированный ответ без сети (для офлайн-старта) */
    fun cachedSchedule(group: String): String? = prefs.getString("sched_$group", null)

    /** Закэшированный список групп без обращения к сети (для мгновенного показа). */
    fun cachedGroups(): List<String> =
        prefs.getString("groups", null)?.split("|||")?.filter { it.isNotBlank() } ?: emptyList()

    /**
     * Дата начала семестра, нужная для расчёта текущей недели (числитель/знаменатель).
     * Сайт публикует её в HTML страницы расписания как `var semestr_start = "2026-08-04"`.
     * Кэшируем на сутки; при неудаче остаётся зашитая в WeekType.SEMESTR_START_ISO.
     */
    fun semestrStart(): String {
        val cached = prefs.getString(KEY_SEMESTR, null)
        val ts = prefs.getLong("${KEY_SEMESTR}_ts", 0L)
        if (cached != null && System.currentTimeMillis() - ts < 24 * 60 * 60 * 1000L) return cached
        return runCatching {
            val req = Request.Builder()
                .url("https://miet.ru/schedule/")
                .header("User-Agent", "MIETSchedule/1.0 (Android; student schedule viewer)")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
                val html = resp.body?.string().orEmpty()
                val m = Regex("""semestr_start\s*=\s*["']([0-9]{4}-[0-9]{2}-[0-9]{2})["']""").find(html)
                    ?: throw IllegalStateException("semestr_start не найден")
                m.groupValues[1].also { iso ->
                    prefs.edit().putString(KEY_SEMESTR, iso)
                        .putLong("${KEY_SEMESTR}_ts", System.currentTimeMillis()).apply()
                }
            }
        }.getOrElse { cached ?: WeekType.SEMESTR_START_ISO }
    }

    fun cacheAgeMs(group: String): Long {
        val ts = prefs.getLong("sched_${group}_ts", 0L)
        return if (ts == 0L) -1L else System.currentTimeMillis() - ts
    }

    // ───────────────────── индекс преподавателей ─────────────────────
    // Лежит в том же файле кэша, что и расписания групп: индекс строится
    // ровно из них, и отдельный файл только размножал бы данные.

    fun saveTeacherIndex(key: String, json: String) =
        prefs.edit().putString(key, json).apply()

    fun loadTeacherIndex(key: String): String? = prefs.getString(key, null)

    fun saveTeacherIndexTs(key: String, ts: Long) =
        prefs.edit().putLong(key, ts).apply()

    fun loadTeacherIndexTs(key: String): Long = prefs.getLong(key, 0L)

    private companion object {
        const val KEY_SEMESTR = "semestr_start"
        const val KEY_AUD = "audiences"
    }
}

/**
 * Ключ дедупликации пары аудитории — top-level, чтобы юнит-тест не тянул
 * за собой MietApi (а он требует Context). См. доктринг у одноимённого
 * [MietApi.localLessonsOf], где ключ и применяется.
 */
internal fun roomLessonKey(l: Lesson): String =
    "${l.day}|${l.dayNumber ?: 0}|${l.time?.code}|" +
        "${l.classInfo?.code}|${l.classInfo?.name}|${l.group?.name}|" +
        roomKey(l.room?.name)
