package com.mietschedule.app

import android.content.Context

/**
 * Настройки главного экрана.
 *
 * Пока одна: показывать ли список избранного на главной. Избранное живёт
 * отдельной вкладкой в нижнем меню, и части пользователей дублировать его на
 * главной не нужно — им достаточно блока «Пары на сегодня».
 *
 * Значение по умолчанию — ПОКАЗЫВАТЬ. У тех, кто уже пользуется приложением,
 * главная после обновления обязана выглядеть как раньше: молча убрать
 * привычный экран хуже, чем оставить лишний блок.
 */
object HomePrefs {

    private const val PREFS = "home_prefs"
    private const val KEY_SHOW_FAVORITES = "show_favorites"

    /**
     * Значение по умолчанию — показывать.
     *
     * Вынесено в константу, чтобы тест проверял это значение напрямую, а не
     * чтением исходника: требование владельца — при первой установке
     * переключатель включён.
     */
    const val DEFAULT_SHOW_FAVORITES = true

    /** Показывать список избранного на главной. По умолчанию — да. */
    fun showFavorites(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_SHOW_FAVORITES, DEFAULT_SHOW_FAVORITES)

    fun setShowFavorites(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_SHOW_FAVORITES, on)
            .apply()
    }
}
