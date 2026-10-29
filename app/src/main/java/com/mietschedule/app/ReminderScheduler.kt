package com.mietschedule.app

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * Напоминание за 10 минут до пары избранной группы.
 *
 * Работает без интернета: расписание берётся из локального кэша, время
 * начала пары считается на устройстве, будильник ставится системный
 * `AlarmManager`. Сеть нужна только чтобы кэш был свежим — об этом
 * заботится [RefreshWorker].
 *
 * Ограничения, которые пришлось учесть:
 *  - Android не даёт будильнику сработать в произвольное время без
 *    разрешения: на Android 12+ `setExactAndAllowWhileIdle` требует
 *    `SCHEDULE_EXACT_ALARM`, который у обычных приложений выдаётся редко.
 *    Поэтому используем `setAndAllowWhileIdle` — будильник сработает, но
 *    система может задержать его на несколько минут в режиме Doze. Для
 *    «напомнить за 10 минут» это приемлемо, а ложная обещанная точность
 *    хуже: пользователь всё равно получает напоминание, пусть позже.
 *  - Точное число будильников ограничено системой (обычно 500). Ставим
 *    напоминания только на ближайшие дни, а не на весь семестр.
 */
object ReminderScheduler {

    private const val TAG = "ReminderScheduler"

    /**
     * Пересчёт не должен накладываться сам на себя.
     *
     * Каждый вызов [reschedule] поднимал свой поток, а пересчёт состоит из
     * трёх шагов: прочитать `planned_ids`, снять будильники, поставить новые
     * и перезаписать `planned_ids`. Два потока, идущие навстречу, давали
     * ровно тот дефект, который пользователь и описывал: первый снимает
     * всё, второй ставит своё и записывает свой список, а первый
     * успевает дописать в `planned_ids` уже свои коды — либо наоборот,
     * снять будильники, которые только что поставил второй. Итог:
     * либо пуш от группы, снятой с избранного, либо молча пропавшие
     * напоминания у оставшихся групп.
     *
     * Синхронизация здесь, а не в вызывающем коде: пересчёт идёт из шести
     * разных мест, и ни одно из них не знает о соседнем.
     */
    private val rescheduleLock = Any()

    /**
     * Варианты: за сколько минут до начала пары напоминать.
     *
     * Список задан пользователем в настройках, а не разработчиком: для
     * «через минуту» и «за четверть часа» нужны разные ситуации.
     */
    val LEAD_OPTIONS = listOf(1L, 5L, 10L, 15L)

    /** За сколько минут до начала пары напоминать по умолчанию. */
    const val LEAD_DEFAULT = 10L

    private const val KEY_LEAD = "lead_minutes"

    /** Выбранный интервал. Любое значение вне списка считается дефолтом. */
    fun leadMinutes(ctx: Context): Long {
        val saved = ctx.getSharedPreferences(PREFS_REMINDERS, Context.MODE_PRIVATE)
            .getLong(KEY_LEAD, LEAD_DEFAULT)
        return if (saved in LEAD_OPTIONS) saved else LEAD_DEFAULT
    }

    /**
     * Сохранить интервал и сразу пересчитать будильники.
     *
     * Пересчёт обязателен: смена интервала меняет время САМОГО срабатывания,
     * а не только текст. Без пересчёта уведомления продолжали бы приходить
     * по старому времени.
     */
    fun setLeadMinutes(ctx: Context, minutes: Long) {
        val value = if (minutes in LEAD_OPTIONS) minutes else LEAD_DEFAULT
        ctx.getSharedPreferences(PREFS_REMINDERS, Context.MODE_PRIVATE)
            .edit().putLong(KEY_LEAD, value).apply()
        if (isEnabled(ctx) && notificationsAllowed(ctx)) {
            reschedule(ctx, MietApi(ctx))
        }
    }

    /** Подпись варианта для переключателя: «за $lead минут». */
    fun leadLabel(minutes: Long): String = when (minutes) {
        1L -> "за 1 минуту"
        5L -> "за 5 минут"
        15L -> "за 15 минут"
        else -> "за $minutes минут"
    }

    /** На сколько дней вперёд планируем. Дальше система всё равно не даст. */
    private const val HORIZON_DAYS = 7

    /** Идентификаторы для отмены. */
    private const val PREFIX = "miet_reminder_"

    /**
     * Ключ настройки в хранилище: включены ли напоминания.
     * По умолчанию выключено: уведомления без явного согласия пользователя
     * показывать нельзя.
     */
    private const val PREFS_REMINDERS = "reminder_prefs"
    private const val KEY_ENABLED = "enabled"

    const val CHANNEL_ID = "lesson_reminders"

    // ── Настройка ───────────────────────────────────────────────────────

    fun isEnabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS_REMINDERS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false)

    fun setEnabled(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(PREFS_REMINDERS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, on).apply()
        if (on) {
            reschedule(ctx, MietApi(ctx))
        } else {
            // Снятие будильников тоже трогает AlarmManager, но он быстрый
            // и список кодов маленький — здесь можно остаться синхронным.
            //
            // Блокировка та же, что и у пересчёта: выключение приходит из
            // настроек, а параллельно может идти пересчёт после загрузки
            // расписания. Без блокировки выключение снимало будильники, а
            // пересчёт, успевший прочитать флаг ДО этого, ставил их заново —
            // и напоминания приходили при выключенном переключателе.
            synchronized(rescheduleLock) { cancelAll(ctx) }
        }
    }

    /**
     * Разрешены ли уведомления.
     *
     * На Android 13+ (API 33) без POST_NOTIFICATIONS уведомление не покажут
     * вообще, даже если канал создан. Проверяем явно, иначе переключатель
     * выглядел бы включённым, а напоминания молча не приходили бы.
     */
    fun notificationsAllowed(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    /** Разрешение есть И переключатель включён — можно планировать. */
    fun remindersActive(ctx: Context): Boolean =
        isEnabled(ctx) && notificationsAllowed(ctx)

    // ── Планирование ────────────────────────────────────────────────────

    /**
     * Пересчитать напоминания по свежему кэшу.
     *
     * Вызывается после успешной загрузки расписания и после фоновой
     * задачи [RefreshWorker].
     */
    fun reschedule(ctx: Context, api: MietApi) {
        // Пересчёт читает кэш с диска и разбирает JSON расписания. Вызывается
        // из обработчиков нажатий и из результата загрузки, то есть с главного
        // потока, а это сотни миллисекунд на каждую избранную группу.
        // Поэтому весь расчёт уходит в IO.
        val appCtx = ctx.applicationContext
        Thread {
            runCatching {
                synchronized(rescheduleLock) { rescheduleBlocking(appCtx, api) }
            }.onFailure { Log.w(TAG, "Пересчёт напоминаний не удался", it) }
        }.start()
    }

    /** Собственно пересчёт. Вызывать только из фонового потока. */
    private fun rescheduleBlocking(ctx: Context, api: MietApi) {
        // Проверяем разрешение, а не только переключатель: без него на
        // Android 13+ будильники звонили бы в пустоту.
        if (!isEnabled(ctx) || !notificationsAllowed(ctx)) {
            cancelAll(ctx)
            return
        }
        val prefs = GroupPrefs(ctx)

        // Избранные группы студента — ровно те, по которым нужны напоминания.
        // РОЛЬ, выбранная для ПРОСМОТРА, здесь ни при чём.
        //
        // Раньше при роли, отличной от «студент», пересчёт снимал ВСЕ
        // будильники и выходил. Достаточно было один раз заглянуть в экран
        // аудиторий или преподавателей, чтобы остаться без напоминаний: а
        // пересчёт идёт на каждом открытии приложения, на каждой смене
        // избранного и в фоновой задаче — то есть первое же действие в чужой
        // роли гасило пуши до возврата в роль «студент». Избранное хранится
        // отдельно для каждой роли, поэтому чужая вкладка не означает отказа
        // от своих напоминаний.
        val favs = prefs.favGroups(Role.STUDENT)
        if (favs.isEmpty()) {
            cancelAll(ctx)
            return
        }

        val am = ctx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        ensureChannel(ctx)

        // Прочистка перед постановкой — обязательна и безусловна.
        //
        // Раньше отмена стояла только в ранних выходах выше: выключены
        // напоминания, выбрана не роль «студент», избранных не осталось
        // совсем. Сценарий «было две группы в избранном, снял звезду с
        // одной» под эти условия не подпадал, и будильники отписанной
        // группы оставались в AlarmManager — напоминание приходило, хотя
        // человек от группы отписался.
        //
        // Снять их потом было нечем: cancelAll берёт коды из planned_ids,
        // а rememberPlanned ниже перезаписывает этот список новым, и коды
        // отписанной группы из него исчезают. Поэтому порядок жёсткий:
        // сначала снять всё по сохранённому списку, потом ставить заново.
        cancelAll(ctx)

        val todayDow = (Calendar.getInstance().get(Calendar.DAY_OF_WEEK) + 5) % 7
        // Интервал берём из настроек пользователя, а не из константы:
        // вариантов несколько, и значение меняется без переустановки.
        val lead = leadMinutes(ctx)

        val plannedIds = mutableListOf<Pair<Int, String>>()
        // Минимальное время срабатывания: по нему решаем, какой будильник
        // станет точным. Long.MAX_VALUE, если ничего не запланировано.
        var nearestFireAt = Long.MAX_VALUE

        // Часы, которые покажет пользователь в статус-баре, пока точный
        // будильник активен. Без этого Intent система не знает, что показать.
        val showPi = showIntent(ctx)

        for (group in favs) {
            val raw = api.cachedSchedule(group)
            if (raw.isNullOrBlank()) {
                Log.i(TAG, "Нет кэша для избранной $group — напоминаний не будет")
                continue
            }
            val lessons = runCatching {
                GsonHolder.gson.fromJson(raw, ScheduleResponse::class.java)?.data ?: emptyList()
            }.getOrElse {
                Log.w(TAG, "Кэш $group не разобран", it)
                emptyList()
            }
            if (lessons.isEmpty()) continue

            for (dayOffset in 0 until HORIZON_DAYS) {
                // Сегодняшний индекс + смещение дней: так получаем календарный
                // день недели для каждого дня горизонта.
                val dow = (todayDow + dayOffset) % 7
                // ДАТУ БЕРЁМ ОТ СЕГОДНЯ, А НЕ ИЗ ТЕКУЩЕЙ НЕДЕЛИ.
                //
                // Раньше стоял вызов dateOfWeekDay с нулевым сдвигом —
                // «понедельник текущей недели + dow». Для dayOffset
                // 0..(6 - todayDow) это верно, но дальше dow переполняется в
                // начало недели, а дата откатывается в ПРОШЛОЕ: сегодня
                // пятница, dayOffset 3 — это понедельник СЛЕДУЮЩЕЙ недели, а
                // код давал понедельник текущей, то есть позавчера. Будильник
                // уходил в прошлое, отсекался по «startAt <= now» — и с
                // пятницы до воскресенья напоминаний не было ВООБЩЕ. Выглядело
                // как «через раз»: пн-чт работали, пт-вс молчали.
                //
                // today + dayOffset — ровно тот календарный день, который
                // имеется в виду, независимо от границ недели.
                val date = Calendar.getInstance().apply {
                    timeInMillis = System.currentTimeMillis()
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                    add(Calendar.DAY_OF_MONTH, dayOffset)
                }

                for (l in lessons) {
                    // Day в ответе 1..7, где 1 = понедельник, наш индекс 0..6.
                    val lessonDow = (l.day ?: -1) - 1
                    if (lessonDow != dow) continue
                    // DayNumber = конкретная учебная неделя (0..3). Тип недели
                    // считаем ДЛЯ ДАТЫ ЭТОГО ДНЯ, а не из dayOffset.
                    //
                    // Раньше стоял floorMod от суммы текущего типа и числа
                    // полных недель в горизонте. При dayOffset=3 в пятницу это
                    // даёт +0, хотя день уже в СЛЕДУЮЩЕЙ календарной (и
                    // учебной) неделе — тип другой. Настоящая пара отсеивалась,
                    // и «через раз» ловили не только по дате, но и по типу
                    // недели.
                    //
                    // typeFor считает от даты до старта семестра — ровно то,
                    // как экран расписания показывает «числитель/знаменатель».
                    val lessonRow = l.dayNumber ?: 0
                    val dayWeekType = WeekType.typeFor(date, WeekType.parseIso(WeekType.SEMESTR_START_ISO) ?: continue)
                    if (lessonRow != dayWeekType) continue

                    val startAt = dateAt(date, l.time?.timeFrom) ?: continue
                    val now = System.currentTimeMillis()
                    if (startAt <= now) continue

                    val notifyAt = startAt - TimeUnit.MINUTES.toMillis(lead)
                    // Пара начинается раньше, чем через LEAD_MINUTES: напоминать
                    // «за $lead минут» уже поздно, но саму пару показать полезно —
                    // ставим будильник на минуту до начала.
                    val fireAt = if (notifyAt <= now) startAt - TimeUnit.MINUTES.toMillis(1) else notifyAt
                    if (fireAt <= now) continue

                    val id = requestCodeFor(group, dayOffset, l.time?.code ?: 0, l.roomNameSafe())
                    val pi = pendingIntent(ctx, group, l, id)

                    // Ближайшее напоминание ставим ТОЧНЫМ будильником.
                    //
                    // setAndAllowWhileIdle Android в режиме Doze сдвигает на
                    // минуты, а иногда на час: в dumpsys окна доходили до
                    // «+1h», то есть «за $lead минут» превращалось в «за час».
                    // setAlarmClock — единственный локальный способ, который
                    // работает офлайн и не требует SCHEDULE_EXACT_ALARM.
                    // Плата: система показывает значок будильника в строке
                    // состояния, пока такие напоминания активны.
                    //
                    // Точным делаем только ближайшее: держать 40 точных
                    // будильников смысла нет — пользователю важен сегодняшний.
                    val isNearest = fireAt < nearestFireAt
                    if (isNearest) nearestFireAt = fireAt

                    // Ставим будильник. Если точный вариант не разрешён или
                    // система его отвергла — ОБЯЗАТЕЛЬНО ставим мягкий, иначе
                    // напоминание пропадает целиком. Раньше здесь был один
                    // runCatching: сбой точного варианта молча оставлял пару
                    // без напоминания, и это выглядело как «напоминаний нет».
                    val exactOk = isNearest && canUseExact(ctx)
                    val exactResult = if (exactOk) {
                        runCatching {
                            am.setAlarmClock(
                                AlarmManager.AlarmClockInfo(fireAt, showPi),
                                pi,
                            )
                        }
                    } else {
                        Result.failure(SecurityException("Точные будильники не разрешены"))
                    }

                    if (exactResult.isFailure) {
                        runCatching {
                            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fireAt, pi)
                        }.onFailure {
                            Log.w(TAG, "Будильник не поставился: $group $dayOffset", it)
                        }
                    }
                    plannedIds += id to group
                    // Запись списка кодов — после КАЖДОЙ постановки, а не
                    // одним вызовом в конце.
                    //
                    // Список нужен, чтобы снимать будильники. Если процесс
                    // завершится между постановкой и записью (или запись не
                    // успеет лечь на диск), поставленный будильник становится
                    // «безымянным»: снять его больше нечем, и он срабатывает
                    // даже после того, как группа убрана из избранного.
                    // Ровно так приходило напоминание о паре группы, которой
                    // у владельца в избранном уже не было.
                    rememberPlanned(ctx, plannedIds)
                }
            }
        }

        // Список нужен для отмены: AlarmManager не умеет снимать «всё по тегу»,
        // а пересчёт вызывается на каждой загрузке. Без сохранённых кодов старые
        // будильники остались бы звонить после смены избранного.
        rememberPlanned(ctx, plannedIds)
        Log.i(TAG, "Запланировано напоминаний: ${plannedIds.size} для ${favs.size} групп")
    }

    /**
     * Можно ли ставить точный будильник.
     *
     * `setAlarmClock` разрешён всегда, в отличие от `setExactAndAllowWhileIdle`,
     * который требует SCHEDULE_EXACT_ALARM. Но на части прошивок и при
     * жёсткой экономии батареи вызов бросает SecurityException — поэтому
     * проверяем и пробуем в try/catch на месте постановки.
     */
    fun canUseExact(ctx: Context): Boolean {
        // Проверено на Android 14: setAlarmClock БЕЗ SCHEDULE_EXACT_ALARM или
        // USE_EXACT_ALARM бросает SecurityException. То есть «точный будильник
        // всегда разрешён» — неверно, и такой вызов просто терял напоминание.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return false
        return am.canScheduleExactAlarms()
    }

    /**
     * Intent для значка будильника в статус-баре.
     *
     * Android показывает там время следующего срабатывания; без явного
     * Intent он ставит пустое место. Открываем приложение — так нажатие
     * на значок ведёт в расписание.
     */
    private fun showIntent(ctx: Context): PendingIntent {
        val intent = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags = flags or PendingIntent.FLAG_IMMUTABLE
        }
        return PendingIntent.getActivity(ctx, 0, intent, flags)
    }

    /** Момент начала пары по строке `0001-01-01T09:00:00`. */
    private fun dateAt(date: Calendar, timeFrom: String?): Long? {
        val hhmm = runCatching {
            val t = timeFrom?.trim().orEmpty()
            val timePart = t.substringAfter('T').take(5)
            if (timePart.length < 5) return null
            timePart.substringBefore(':').toInt() to timePart.substringAfter(':').toInt()
        }.getOrNull() ?: return null

        val cal = date.clone() as Calendar
        cal.set(Calendar.HOUR_OF_DAY, hhmm.first)
        cal.set(Calendar.MINUTE, hhmm.second)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    private fun requestCodeFor(
        group: String,
        dayOffset: Int,
        pairCode: Int,
        room: String?,
    ): Int = (PREFIX + group + dayOffset + pairCode + (room ?: "")).hashCode()

    private fun pendingIntent(ctx: Context, group: String, l: Lesson, id: Int): PendingIntent {
        val intent = Intent(ctx, ReminderReceiver::class.java).apply {
            action = ACTION_REMIND
            // Ключевая деталь: PendingIntent система сравнивает по action и
            // data, а НЕ по extras. Без уникального Uri каждый пересчёт
            // создавал НОВЫЙ PendingIntent, и отмена по старому списку кодов
            // не находила их: будильники от прошлых пересчётов оставались
            // висеть (проверено: 15 штук вместо 5).
            data = android.net.Uri.parse("miet://reminder/$id")
            // Код будильника нужен и получателю: по нему строится номер
            // уведомления, иначе две пары одного слота затирают друг друга.
            putExtra(EXTRA_REMIND_ID, id)
            putExtra(EXTRA_GROUP, group)
            putExtra(EXTRA_TIME, l.time?.timeFrom)
            putExtra(EXTRA_PAIR, l.time?.code ?: 0)
            putExtra(EXTRA_SUBJECT, l.classInfo?.name)
            putExtra(EXTRA_TEACHER, l.classInfo?.teacherFull ?: l.classInfo?.teacher)
            putExtra(EXTRA_ROOM, l.roomNameSafe())
        }
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags = flags or PendingIntent.FLAG_IMMUTABLE
        }
        return PendingIntent.getBroadcast(ctx, id, intent, flags)
    }

    /**
     * Убрать все напоминания: смена группы, роли или выключение.
     *
     * AlarmManager не умеет «снять всё по тегу», поэтому снимаем по кодам,
     * сохранённым при постановке. Ключ — тот же `data` в Intent: без него
     * PendingIntent не совпадает, `cancel()` ничего не снимает, а будильники
     * накапливались от пересчёта к пересчёту (проверено: 15 вместо 5).
     */
    fun cancelAll(ctx: Context) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val ids = plannedIds(ctx).map { it.first }
        for (id in ids) {
            runCatching { am.cancel(cancelIntent(ctx, id, "")) }
        }
        ctx.getSharedPreferences(PREFS_REMINDERS, Context.MODE_PRIVATE)
            .edit().remove(KEY_PLANNED).apply()
        Log.i(TAG, "Напоминания сняты: ${ids.size}")
    }

    private fun cancelIntent(ctx: Context, id: Int, group: String): PendingIntent {
        val intent = Intent(ctx, ReminderReceiver::class.java).apply {
            action = ACTION_REMIND
            // Тот же Uri, что и при постановке: без него PendingIntent не
            // совпадёт и AlarmManager.cancel() ничего не снимет.
            data = android.net.Uri.parse("miet://reminder/$id")
            putExtra(EXTRA_GROUP, group)
        }
        var flags = PendingIntent.FLAG_NO_CREATE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags = flags or PendingIntent.FLAG_IMMUTABLE
        }
        return PendingIntent.getBroadcast(ctx, id, intent, flags)
    }

    private const val KEY_PLANNED = "planned_ids"

    /** «id:группа» для всех запланированных будильников. */
    private fun plannedIds(ctx: Context): List<Pair<Int, String>> {
        val raw = ctx.getSharedPreferences(PREFS_REMINDERS, Context.MODE_PRIVATE)
            .getString(KEY_PLANNED, null) ?: return emptyList()
        return raw.split(';').mapNotNull { line ->
            val parts = line.split(':', limit = 2)
            if (parts.size != 2) return@mapNotNull null
            parts[0].toIntOrNull()?.let { it to parts[1] }
        }
    }

    private fun rememberPlanned(ctx: Context, ids: List<Pair<Int, String>>) {
        ctx.getSharedPreferences(PREFS_REMINDERS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PLANNED, ids.joinToString(";") { "${it.first}:${it.second}" })
            .apply()
    }

    // ── Канал уведомлений ───────────────────────────────────────────────

    fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val ch = NotificationChannel(
            CHANNEL_ID,
            "Напоминания о парах",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Напоминание за 10 минут до начала пары"
            enableVibration(true)
        }
        nm.createNotificationChannel(ch)
    }

    const val ACTION_REMIND = "com.mietschedule.app.REMIND"
    const val EXTRA_GROUP = "group"
    const val EXTRA_TIME = "time"
    const val EXTRA_PAIR = "pair"
    const val EXTRA_SUBJECT = "subject"
    const val EXTRA_TEACHER = "teacher"
    const val EXTRA_ROOM = "room"
    /** Код будильника: различает пары одного слота (они в разных аудиториях). */
    const val EXTRA_REMIND_ID = "remind_id"

    /** Имя аудитории; у [RoomInfo] поле может быть числом. */
    private fun Lesson.roomNameSafe(): String? = room?.name?.trim()?.takeIf { it.isNotEmpty() }
}

/**
 * Получатель будильника: показывает уведомление.
 *
 * Отдельный класс нужен, потому что будильник — это Intent, который система
 * доставляет компоненту приложения, даже если само приложение не запущено.
 */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != ReminderScheduler.ACTION_REMIND) return

        val group = intent.getStringExtra(ReminderScheduler.EXTRA_GROUP) ?: return
        val pair = intent.getIntExtra(ReminderScheduler.EXTRA_PAIR, 0)
        val subject = intent.getStringExtra(ReminderScheduler.EXTRA_SUBJECT)
        val teacher = intent.getStringExtra(ReminderScheduler.EXTRA_TEACHER)
        val room = intent.getStringExtra(ReminderScheduler.EXTRA_ROOM)
        val timeFrom = intent.getStringExtra(ReminderScheduler.EXTRA_TIME)

        // Группа обязана быть в избранном ПРЯМО СЕЙЧАС.
        //
        // Будильник живёт в системе и переживает удаление группы из
        // избранного. Снятие будильников при пересчёте — оптимизация, а не
        // гарантия: процесс мог завершиться между снятием старых и записью
        // новых кодов, и тогда «безымянный» будильник снять уже нечем. Он
        // срабатывал и показывал напоминание о паре группы, которой в
        // избранном нет. Проверка на месте закрывает это при любом раскладе.
        if (group !in GroupPrefs(ctx).favGroups(Role.STUDENT)) {
            Log.i("ReminderReceiver", "Пропущено: группа $group не в избранном")
            return
        }

        ReminderScheduler.ensureChannel(ctx)

        val timeText = formatTime(timeFrom)
        val title = buildString {
            append(group)
            if (pair > 0) append(", ").append(pair).append(" пара")
        }
        val body = buildString {
            if (!timeText.isNullOrBlank()) append(timeText).append(" — ")
            append(subject ?: "пара")
            val who = teacher?.takeIf { it.isNotBlank() }
            val where = room?.takeIf { it.isNotBlank() }
            if (who != null || where != null) {
                append("\n")
                append(listOfNotNull(who, where).joinToString(" • "))
            }
        }

        val open = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_GROUP, group)
            // Роль обязательна: расписание выбирает данные ПО РОЛИ, а не по
            // названию в уведомлении. Без неё тап по напоминанию о группе при
            // сохранённой роли «преподаватель» открывал расписание
            // преподавателя. Напоминания сейчас строятся только по группам
            // избранного (см. rescheduleBlocking), поэтому роль всегда
            // студенческая; когда появятся напоминания преподавателей и
            // аудиторий, здесь обязана стоять их роль.
            putExtra(EXTRA_GROUP_ROLE, Role.STUDENT.key)
        }
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags = flags or PendingIntent.FLAG_IMMUTABLE
        }
        val contentPi = PendingIntent.getActivity(ctx, group.hashCode(), open, flags)

        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        // Номер уведомления — по КОДУ БУДИЛЬНИКА, а не только по группе.
        //
        // У группы бывает ДВЕ пары в одном слоте: у ТЭС-26-11О в среду 3-я
        // пара — «Информатика» в 8307 и «Информатика» в 8306 (разные
        // преподаватели). С номером по группе второе уведомление затирало
        // первое, и напоминание приходило одно вместо двух — выглядело как
        // «приходят не все».
        val id = 1000 + intent.getIntExtra(ReminderScheduler.EXTRA_REMIND_ID, group.hashCode())
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(ctx, ReminderScheduler.CHANNEL_ID)
        } else {
            Notification.Builder(ctx)
        }
        val notification = builder
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(contentPi)
            .build()

        // Проверено на Android 14: без разрешения POST_NOTIFICATIONS
        // notify() молча ничего не делает, поэтому проверяем заранее и
        // пишем в лог — иначе «напоминания включены», а их нет.
        if (!ReminderScheduler.notificationsAllowed(ctx)) {
            Log.w("ReminderReceiver", "Уведомление пропущено: разрешения нет")
            return
        }
        runCatching { nm.notify(id, notification) }
            .onFailure { Log.w("ReminderReceiver", "Уведомление не показано", it) }
    }

    private fun formatTime(timeFrom: String?): String? = runCatching {
        val t = timeFrom?.trim().orEmpty()
        val part = t.substringAfter('T').take(5)
        if (part.length < 5) return null
        part
    }.getOrNull()
}