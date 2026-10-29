package com.mietschedule.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Переключатель «Показывать избранное на главной».
 *
 * Требование владельца: список избранного на главной нужен не всем (он есть
 * отдельной вкладкой внизу), поэтому его прячут переключателем в настройках.
 * При первой установке переключатель обязан быть ВКЛЮЧЁН — иначе новый
 * пользователь не увидит избранное там, где его ждёт.
 *
 * Значение по умолчанию проверяется напрямую по константе, а связывание
 * экранов — чтением исходников: у проекта нет UI-тестов, а поведение
 * «блок рисуется по настройке» проверяется на устройстве.
 */
class HomeFavoritesToggleTest {

    /** Читает исходник из пакета main — как соседние тесты. */
    private fun srcText(name: String): String {
        val candidates = listOf(
            "src/main/java/com/mietschedule/app/$name",
            "../app/src/main/java/com/mietschedule/app/$name",
        )
        for (p in candidates) {
            val f = java.io.File(p)
            if (f.exists()) return f.readText()
        }
        throw AssertionError("не найден исходник $name")
    }

    @Test
    fun `при первой установке избранное на главной показывается`() {
        assertTrue(
            "по умолчанию список избранного на главной должен показываться",
            HomePrefs.DEFAULT_SHOW_FAVORITES,
        )
    }

    @Test
    fun `значение по умолчанию используется при чтении настройки`() {
        val src = srcText("HomePrefs.kt")
        assertTrue(
            "чтение обязано опираться на константу по умолчанию, а не на литерал",
            src.contains("getBoolean(KEY_SHOW_FAVORITES, DEFAULT_SHOW_FAVORITES)"),
        )
        assertFalse(
            "значение по умолчанию не должно быть зашито в вызов чтения",
            src.contains("getBoolean(KEY_SHOW_FAVORITES, true)"),
        )
    }

    @Test
    fun `настройка сохраняется`() {
        val src = srcText("HomePrefs.kt")
        assertTrue(
            "запись настройки обязана сохранять её в хранилище",
            src.contains("putBoolean(KEY_SHOW_FAVORITES, on)"),
        )
    }

    /** Блок избранного на главной нарисован только при включённой настройке. */
    @Test
    fun `блок избранного на главной зависит от настройки`() {
        val src = srcText("HomeScreen.kt")
        val at = src.indexOf("if (showFavorites) {")
        assertTrue("на главной нет условия по настройке", at >= 0)
        // Внутри условия обязаны оказаться и список, и кнопка «Добавить»:
        // прятать список без кнопки нельзя — «Добавить» открывает выбор,
        // которым этот список и наполняется, а на вкладке «Избранное» есть
        // свой такой же вход.
        val body = src.substring(at, minOf(at + 4000, src.length))
        assertTrue("внутри условия нет списка избранного", body.contains("favs.take(3)"))
        assertTrue("внутри условия нет кнопки «Добавить»", body.contains("title = \"Добавить\""))
    }

    /** Переключатель есть в настройках и меняет состояние приложения. */
    @Test
    fun `переключатель живёт в настройках`() {
        val settings = srcText("SettingsScreen.kt")
        assertTrue(
            "нет раздела «Главный экран»",
            settings.contains("SectionTitle(\"Главный экран\")"),
        )
        assertTrue(
            "переключатель не связан с состоянием",
            settings.contains("onCheckedChange = onShowFavoritesChange"),
        )

        val main = srcText("MainActivity.kt")
        assertTrue(
            "состояние не читается из настройки при запуске",
            main.contains("HomePrefs.showFavorites(this)"),
        )
        assertTrue(
            "изменение не сохраняется",
            main.contains("HomePrefs.setShowFavorites(this@MainActivity, on)"),
        )
        assertTrue(
            "главная не получает настройку",
            main.contains("showFavorites = showFavoritesOnHome"),
        )
    }
}
