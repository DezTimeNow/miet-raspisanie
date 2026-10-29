package com.mietschedule.app

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Фоновая актуализация расписания раз в 6 часов.
 *
 * Зачем: до этого обновление шло только при открытии приложения или по
 * кнопке «Обновить всё». Кэш группы жил бессрочно, а условие загрузки
 * `force || lessons.isEmpty()` при непустом кэше сеть не дёргал. Человек,
 * открывавший расписание каждый день, видел данные, изменившиеся на сайте,
 * только спустя недели.
 *
 * Обновляются ВСЕ избранные группы, а не только открытая. Напоминания
 * строятся по избранным, поэтому их кэши тоже должны быть свежими: иначе
 * будильник звонил бы по расписанию, которое на сайте давно поменяли.
 *
 * Требования Android, важные для выбора периода:
 *  - `PeriodicWorkRequest` с периодом меньше 15 минут не выполнится;
 *  - «ровно в 6 часов» система не гарантирует: задача запускается в удобное
 *    окно, обычно с накоплением до нескольких часов. Для свежести расписания
 *    это приемлемо.
 */
class RefreshWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val prefs = GroupPrefs(ctx)
        val api = MietApi(ctx)

        // Группы, которые нужно освежить.
        //
        // Раньше здесь стоял выход для всех, кроме студента: мол, расписание
        // преподавателя собирается из кэша 343 групп и такой обход дорог.
        // Но обновляет-то эти кэши ТОТ ЖЕ работник, что и студенческие, —
        // то есть у преподавателя и у аудитории фонового обновления не
        // было вовсе, и их расписание жило до ручного нажатия. Требование
        // владельца: актуальные данные без ручного обновления.
        //
        // Лишних запросов это не добавляет: список групп у роли один и тот
        // же, обходятся ровно те же кэши, что и раньше. Для преподавателя
        // добавляется его избранное — оно идёт в тех же целях, что и
        // у студента, по нему строится индекс.
        val targets = targetsFor(prefs)

        if (targets.isEmpty()) {
            Log.i(TAG, "Нет ни избранных, ни выбранной группы — сеть не трогаю")
            return Result.success()
        }

        val role = prefs.role()
        val fresh = mutableListOf<String>()
        val stale = targets.filter { group ->
            api.scheduleFresh(group).also { ok -> if (ok) fresh += group }
        }

        if (stale.isEmpty()) {
            Log.i(TAG, "Все кэши свежие (${fresh.size}), сеть не трогаю")
            return Result.success()
        }

        var failures = 0
        var updated = 0
        for (group in stale) {
            api.markCheck(role, group)
            try {
                val raw = api.fetchSchedule(group)
                if (raw.isBlank()) {
                    Log.w(TAG, "Пустой ответ по $group, кэш не трогаю")
                    failures++
                } else {
                    api.cacheSchedule(group, raw)
                    updated++
                }
            } catch (e: Exception) {
                failures++
                Log.w(TAG, "Не обновил $group", e)
            }
        }

        if (updated > 0) {
            Log.i(TAG, "Обновлено групп: $updated из ${stale.size}")
            // Напоминания строим из кэша, поэтому пересчёт после обновления
            // обязателен: иначе будильники остались бы на старых временах.
            runCatching { ReminderScheduler.reschedule(ctx, api) }
                .onFailure { Log.w(TAG, "Напоминания не перепланированы", it) }
            // Индекс преподавателей пересобираем ИЗ КЭША: сети нет, только
            // парсинг уже скачанных расписаний. Полная пересборка с сети
            // (build) ходит по всем 343 группам и стоит минут — ей место под
            // кнопкой «Обновить всё». Здесь же TTL 7 дней означал, что новый
            // преподаватель в расписании не появлялся в списке до недели.
            // Пересборка из свежих кэшей закрывает эту дыру каждые 6 часов.
            runCatching {
                val groups = api.cachedGroups()
                if (groups.isNotEmpty()) {
                    TeacherIndex.buildFromCache(api, groups)
                    Log.i(TAG, "Индекс преподавателей пересобран из кэша")
                }
            }.onFailure { Log.w(TAG, "Индекс преподавателей не пересобран", it) }
        }

        // Если хотя бы часть не удалась — повторим позже. Полный отказ
        // от всех групп означает проблему с сетью, а не с расписанием.
        return if (updated > 0 || failures == 0) Result.success() else Result.retry()
    }

    companion object {
        private const val TAG = "RefreshWorker"
        private const val UNIQUE = "schedule_refresh"

        /**
         * Какие группы освежать в фоне.
         *
         * Для студента это его избранное и текущая группа — по ним строятся
         * напоминания, поэтому их кэши обязаны быть свежими.
         *
         * Для преподавателя это избранные ФИО и текущий: его расписание
         * собирается обходом расписаний всех групп, а значит оно тем более
         * должно опираться на свежие кэши. Раньше фонового обновления у него
         * не было вовсе, и данные жили до ручного нажатия.
         *
         * Для аудитории то же самое: её занятость склеивается из расписаний
         * групп по имени помещения, поэтому освежаем именно их.
         *
         * Вынесено отдельно, потому что правило «избранное плюс текущее»
         * одинаково для всех ролей, а различается только то, чьи избранные
         * берутся.
         */
        internal fun targetsFor(prefs: GroupPrefs): List<String> {
            val favs = prefs.favGroups(prefs.role()).filter { it.isNotBlank() }.distinct()
            val current = prefs.load()?.trim()
            return (if (current.isNullOrBlank()) favs else favs + current).distinct()
        }

        /**
         * Зарегистрировать периодическую задачу. Повторный вызов безопасен.
         *
         * Вызывается НЕ из главного потока: первая инициализация WorkManager
         * читает базу и регистрирует компоненты, что давало «Skipped 485
         * frames» и ANR на старте приложения.
         */
        fun schedule(ctx: Context) {
            val request = PeriodicWorkRequestBuilder<RefreshWorker>(
                CachePolicy.REFRESH_PERIOD_MS, TimeUnit.MILLISECONDS,
            )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
                UNIQUE,
                // KEEP: перезапуск процесса и повторный вызов не должны
                // сбрасывать таймер задачи на ноль.
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}