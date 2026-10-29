package com.mietschedule.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Восстанавливает напоминания после перезагрузки устройства.
 *
 * Почему нужен: `AlarmManager` удаляет ВСЕ будильники при перезагрузке —
 * это системное поведение, не обойти его нельзя. Без этого приёмника
 * утром после перезагрузки приложение молчало бы до первого открытия.
 *
 * Отдельный класс, а не обработчик в существующем ReminderReceiver:
 * у того в intent один `action`, а здесь система шлёт `BOOT_COMPLETED`
 * с другим действием и без extras.
 *
 * Работает офлайн: расписание берётся из локального кэша, сеть не нужна.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(ctx: Context, intent: Intent) {
        val action = intent.action
        // MY_PACKAGE_REPLACED — обновление приложения тоже сносит будильники.
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return

        if (!ReminderScheduler.isEnabled(ctx)) return
        if (!ReminderScheduler.notificationsAllowed(ctx)) return

        val appCtx = ctx.applicationContext
        Log.i(TAG, "Восстанавливаю напоминания после $action")

        // Пересчёт читает кэш и ставит будильники — это диск и AlarmManager,
        // поэтому из broadcast-приёмника уходим в отдельный поток. Запуск
        // здесь же заблокировал бы системный broadcast и вызвал ANR.
        val pending = goAsync()
        Thread {
            try {
                ReminderScheduler.reschedule(appCtx, MietApi(appCtx))
            } catch (e: Exception) {
                Log.w(TAG, "Напоминания не восстановлены", e)
            } finally {
                pending.finish()
            }
        }.start()
    }

    private companion object {
        const val TAG = "BootReceiver"
    }
}