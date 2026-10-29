package com.mietschedule.app

import android.content.Context

/**
 * Хранилище выбранной роли, текущего выбора и избранного.
 *
 * Избранное разделено по ролям: у студента это группы, у преподавателя — ФИО,
 * у аудитории — номера. Смешивать их в одном списке бессмысленно: ФИО
 * «Лупин Сергей Сергеевич» и группа «ПИН-11» сравнимы только по сортировке,
 * но не по смыслу, а список из 658 фамилий рядом с 343 группами нечитаем.
 */
class GroupPrefs(context: Context) {

    private val prefs = context.getSharedPreferences("miet_prefs", Context.MODE_PRIVATE)

    // ───────────────────────── роль и текущий выбор ─────────────────────────

    /** Роль, выбранная пользователем. По умолчанию — студент. */
    fun role(): Role = Role.fromKey(prefs.getString(KEY_ROLE, null))

    fun saveRole(role: Role) {
        prefs.edit().putString(KEY_ROLE, role.key).apply()
    }

    /** Сохранённый выбор для текущей роли или null. */
    fun load(): String? = prefs.getString(KEY_SELECT + role().key, null)

    fun save(value: String) {
        prefs.edit().putString(KEY_SELECT + role().key, value).apply()
    }

    /**
     * Сохранить выбор ДЛЯ КОНКРЕТНОЙ роли.
     *
     * Нужно при открытии из избранного. `save()` читает роль из хранилища, а
     * не ту, по которой открывают: пользователь в роли «студент» открывает
     * вкладку «Преподаватели», жмёт на преподавателя — и без этого метода
     * имя преподавателя записывалось в слот студента. Роль в приложении уже
     * была преподавательской, а в хранилище лежала студенческая, поэтому
     * при следующем запуске открывался не тот выбор.
     */
    fun saveFor(role: Role, value: String) {
        prefs.edit().putString(KEY_SELECT + role.key, value).apply()
    }

    /** Выбор для конкретной роли — нужно вкладкам избранного. */
    fun loadFor(role: Role): String? = prefs.getString(KEY_SELECT + role.key, null)

    // ───────────────────────── избранное по ролям ─────────────────────────

    /** Отметка «избранное» у значения внутри текущей роли. */
    fun isFav(value: String): Boolean = favSet(role()).contains(value)

    fun isFavFor(role: Role, value: String): Boolean = favSet(role).contains(value)

    /** Добавить/убрать из избранного. Возвращает новое состояние. */
    fun toggleFav(value: String): Boolean {
        val role = role()
        val set = favSet(role).toMutableSet()
        val now = if (set.contains(value)) { set.remove(value); false } else { set.add(value); true }
        writeFavs(role, set)
        return now
    }

    fun toggleFavFor(role: Role, value: String): Boolean {
        val set = favSet(role).toMutableSet()
        val now = if (set.contains(value)) { set.remove(value); false } else { set.add(value); true }
        writeFavs(role, set)
        return now
    }

    /** Избранное конкретной роли — для вкладок. */
    fun favGroups(role: Role = this.role()): List<String> = favSet(role).sorted()

    /** Все избранное текущей роли: используется фоновой проверкой расписания. */
    fun favSetFor(role: Role): Set<String> = favSet(role)

    fun clear() {
        prefs.edit().remove(KEY_SELECT + role().key).apply()
    }

    private fun writeFavs(role: Role, set: Set<String>) {
        prefs.edit().putStringSet(KEY_FAVS + role.key, set).apply()
    }

    /**
     * Избранное роли. Читается «мягко»: если в prefs попало что-то не то (строка
     * вместо множества после ручной правки файла или миграции), это не должно
     * ронять приложение при запуске — просто возвращаем пустое множество.
     */
    private fun favSet(role: Role): Set<String> = runCatching {
        prefs.getStringSet(KEY_FAVS + role.key, emptySet())?.toSet() ?: emptySet()
    }.getOrElse { emptySet() }

    private companion object {
        const val KEY_ROLE = "role"
        const val KEY_SELECT = "selected_"
        const val KEY_FAVS = "favorites_"
    }
}