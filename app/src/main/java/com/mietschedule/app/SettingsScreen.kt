package com.mietschedule.app

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Экран настроек: напоминания о парах и оформление.
 *
 * Отдельный экран, а не разделы в «О программе»: настройку ищут перед
 * использованием — поставить напоминание, переключить тему, — а «О
 * программе» открывают, когда хотят почитать о приложении.
 *
 * Доступ с главного экрана: выбор роли, под ним «Настройки» и «О программе».
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onRefresh: () -> Unit = {},
    onChangeRole: () -> Unit = {},
    onThemeChange: (Int) -> Unit = {},
    /** Открыть экран «О программе». */
    onAbout: () -> Unit = {},
    /**
     * Показывать ли список избранного на главной.
     *
     * Избранное живёт отдельной вкладкой внизу, поэтому дублировать его на
     * главной нужно не всем. Значение по умолчанию — показывать: у тех, кто
     * уже пользуется приложением, главная после обновления меняться не должна.
     */
    showFavorites: Boolean = true,
    /** Изменилась настройка показа избранного на главной. */
    onShowFavoritesChange: (Boolean) -> Unit = {},
    /**
     * Идёт ли общее обновление по ⭯.
     *
     * Параметра не было, поэтому кнопка обновления в шапке «Настроек» не
     * показывала крутилку и оставалась нажимаемой все 30 секунд обхода
     * 343 групп: повторное нажатие начинало второй круг поверх первого.
     */
    refreshing: Boolean = false,
) {
    val ctx = LocalContext.current

    Scaffold(
        topBar = {
            // Общая шапка приложения, а не своя TopAppBar. Своя шапка была
            // написана до того, как «Настройки» стали отдельным экраном, и
            // поэтому не имела кнопки «Избранное», звезды, обновления и
            // «Роль». Ровно та же причина, по которой кнопка прыгала по
            // меню: набор соседей задавался здесь вручную и не совпадал.
            MietTopBar(
                title = "Настройки",
                subtitle = "Напоминания и оформление",
                onRefresh = onRefresh,
                onChangeRole = onChangeRole,
                onBack = onBack,
                refreshing = refreshing,
            )
        }
    ) { pad ->
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {

            // ── Напоминания о парах ──────────────────────────────────────
            //
            // По умолчанию выключены: уведомления без явного согласия
            // показывать нельзя. Переключатель и разрешение — разные вещи:
            // на Android 13+ включённый переключатель при отсутствии
            // POST_NOTIFICATIONS молчал бы, поэтому состояние показываем
            // по обоим условиям сразу.
            var remindersOn by remember { mutableStateOf(ReminderScheduler.remindersActive(ctx)) }
            // Интервал хранится рядом с включением: раньше он был зашит в
            // константу, и выбрать другой было нельзя.
            var leadMinutes by remember { mutableStateOf(ReminderScheduler.leadMinutes(ctx)) }
            val permissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission()
            ) { granted ->
                ReminderScheduler.setEnabled(ctx, granted)
                remindersOn = granted
            }

            SectionTitle("Напоминания о парах")
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (remindersOn) {
                            ReminderScheduler.leadLabel(leadMinutes) +
                                " до начала, для избранных групп. " +
                                "Работает без интернета."
                        } else {
                            "Выключены. Включите, чтобы не пропускать пары " +
                                "избранных групп."
                        },
                        fontSize = 12.sp,
                        color = LocalAppColors.current.muted,
                    )
                }
                Switch(
                    checked = remindersOn,
                    onCheckedChange = { want ->
                        if (!want) {
                            // Выключение всегда работает: снимаем будильники
                            // и забываем настройку.
                            ReminderScheduler.setEnabled(ctx, false)
                            remindersOn = false
                        } else if (ReminderScheduler.notificationsAllowed(ctx)) {
                            ReminderScheduler.setEnabled(ctx, true)
                            remindersOn = true
                        } else {
                            // Android 13+: сначала разрешение, потом включение.
                            permissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                        }
                    },
                )
            }

            // Интервал виден всегда: чтобы выбрать, не обязательно сначала
            // включать напоминания.
            Spacer(Modifier.height(6.dp))
            Text(
                "Напомнить за",
                fontSize = 12.sp,
                color = LocalAppColors.current.muted,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ReminderScheduler.LEAD_OPTIONS.forEach { minutes ->
                    FilterChip(
                        selected = leadMinutes == minutes,
                        onClick = {
                            leadMinutes = minutes
                            // Смена интервала меняет время САМОГО срабатывания,
                            // поэтому будильники пересчитываются сразу.
                            ReminderScheduler.setLeadMinutes(ctx, minutes)
                        },
                        label = {
                            Text(
                                ReminderScheduler.leadLabel(minutes).removePrefix("за "),
                                fontSize = 12.sp,
                            )
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            Text(
                if (remindersOn) {
                    "Напоминания включены — будильники пересчитываются после " +
                        "каждой загрузки расписания"
                } else {
                    "Напоминания выключены. Включите, чтобы не пропускать пары " +
                        "избранных групп"
                },
                fontSize = 12.sp,
                color = LocalAppColors.current.muted,
                modifier = Modifier.padding(top = 6.dp),
            )

            Spacer(Modifier.height(20.dp))

            // ── Оформление ───────────────────────────────────────────────
            //
            // Значение берём из хранилища: экран может открыться раньше, чем
            // MainActivity прочитает настройку, поэтому показываем
            // сохранённое, а не текущее состояние темы.
            var currentMode by remember { mutableStateOf(loadThemeMode(ctx)) }

            // ── Главный экран ────────────────────────────────────────────
            //
            // Избранное живёт отдельной вкладкой внизу, поэтому список на
            // главной нужен не всем: кому-то достаточно блока «Ближайшие
            // пары». Переключатель меняет вид главной сразу — состояние
            // держит AppRoot, а не этот экран.
            SectionTitle("Главный экран")
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (showFavorites) {
                            "Список избранного показан на главной. " +
                                "Он всегда доступен во вкладке «Избранное»."
                        } else {
                            "Список скрыт. Избранное всегда доступно " +
                                "во вкладке «Избранное»."
                        },
                        fontSize = 12.sp,
                        color = LocalAppColors.current.muted,
                    )
                }
                Switch(
                    checked = showFavorites,
                    onCheckedChange = onShowFavoritesChange,
                )
            }

            SectionTitle("Оформление")
            Column {
                listOf(THEME_SYSTEM, THEME_LIGHT, THEME_DARK).forEach { mode ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                currentMode = mode
                                saveThemeMode(ctx, mode)
                                onThemeChange(mode)
                            }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier.padding(end = 12.dp),
                        ) {
                            if (currentMode == mode) {
                                Text("●", color = MIET_BLUE, fontSize = 16.sp)
                            } else {
                                Text("○", color = LocalAppColors.current.muted, fontSize = 16.sp)
                            }
                        }
                        Text(
                            themeModeLabel(mode),
                            fontSize = 15.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }

            Spacer(Modifier.height(28.dp))

            // ── О программе ──────────────────────────────────────────────
            //
            // Ссылка переехала сюда с главного экрана: раньше «О программе»
            // была текстовой кнопкой внизу главной, рядом с «Мини-игой».
            // Справка о приложении — это раздел настроек, а не то, за чем
            // идут каждый день, поэтому ей место здесь, а не на главной.
            //
            // Без SectionTitle «О приложении»: кнопка «О программе» с
            // подписью говорит сама за себя, лишний заголовок над ней только
            // занимает строку.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onAbout() }
                    .padding(vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "О программе",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        "Версия и обновления",
                        fontSize = 12.sp,
                        color = LocalAppColors.current.muted,
                    )
                }
                Text("›", fontSize = 20.sp, color = MIET_BLUE, fontWeight = FontWeight.Bold)
            }

            Spacer(Modifier.height(28.dp))
        }
    }
}

/** Заголовок раздела настроек. */
@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        fontSize = 14.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(top = 16.dp, bottom = 6.dp),
    )
}