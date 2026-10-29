package com.mietschedule.app

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/**
 * Экран «О программе»: версия сборки и как приложение вообще работает.
 *
 * Текст написан нейтрально: он описывает, ЧТО приложение делает, и не
 * объясняет недостатки сайта. Прежние формулировки вроде «сайт не отдаёт»
 * и «136 аудиторий из 221» звучали как обвинение в адрес МИЭТ и вдобавок
 * светили внутренние числа, которые пользователю ничего не дают.
 * Правило: говорим о своём поведении, а не о чужих ошибках.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    versionName: String,
    versionCode: Int,
    onBack: () -> Unit,
    onUpdateFound: (UpdateInfo) -> Unit = {},
    onRefresh: () -> Unit = {},
    onChangeRole: () -> Unit = {},
    refreshing: Boolean = false,
    refreshNote: String = "",
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var checkMessage by remember { mutableStateOf<String?>(null) }
    // Пересчитываем при возврате на экран: метка «проверено N мин назад» не
    // должна застывать, пока пользователь читает про обновления.
    val lastChecked = UpdateChecker.lastCheckedAt(ctx)
    Scaffold(
        topBar = {
            // Общая шапка: иконка обновления была тут 24.dp, на остальных
            // экранах 22.dp — при разном размере соседа кнопка занимала
            // разный слот. Теперь размер задан один раз, в MietTopBar.
            MietTopBar(
                title = "О программе",
                subtitle = "Расписание МИЭТ",
                onRefresh = onRefresh,
                onChangeRole = onChangeRole,
                refreshing = refreshing,
                onBack = onBack
            )
        }
    ) { pad ->
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = LocalAppColors.current.currentGroup),
                elevation = CardDefaults.cardElevation(2.dp)
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.Info, contentDescription = null, tint = MIET_BLUE, modifier = Modifier.size(34.dp))
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text("Расписание МИЭТ", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                        Text(
                            "Версия $versionName (сборка $versionCode)",
                            fontSize = 13.sp, color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // ───────── кнопка «Проверить обновления» ─────────
            // Стоит ПЕРВОЙ, сразу под карточкой с версией: это то, за чем
            // сюда заходят чаще всего. Проверка спрашивает GitHub напрямую,
            // минуя 6-часовой кэш, поэтому кнопка ⭯ в шапке (перезагрузка
            // расписания) тут не годится — это другое.
            Button(
                onClick = {
                    if (checking) return@Button
                    checking = true
                    checkMessage = null
                    scope.launch {
                        val result = UpdateChecker.checkNow(ctx, versionCode)
                        checking = false
                        when (result) {
                            is UpdateCheckResult.Available -> {
                                checkMessage = "Доступна ${result.info.versionLabel}"
                                onUpdateFound(result.info)
                            }
                            is UpdateCheckResult.UpToDate -> checkMessage =
                                "Установлена свежая версия (на GitHub ${result.tagName})"
                            is UpdateCheckResult.Failed -> checkMessage = result.reason
                        }
                    }
                },
                enabled = !checking,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MIET_BLUE,
                    disabledContainerColor = LocalAppColors.current.currentGroup,
                ),
                shape = RoundedCornerShape(12.dp),
            ) {
                if (checking) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(17.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.surface,
                    )
                } else {
                    Icon(
                        Icons.Filled.SystemUpdate,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    if (checking) "Проверяю…" else "Проверить обновления",
                    color = MaterialTheme.colorScheme.surface,
                    fontSize = 14.sp,
                )
            }

            checkMessage?.let { msg ->
                Spacer(Modifier.height(6.dp))
                Text(
                    msg,
                    fontSize = 12.sp,
                    color = LocalAppColors.current.muted,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }

            Spacer(Modifier.height(12.dp))

            Text(
                lastCheckLabel(lastChecked),
                fontSize = 12.sp,
                color = LocalAppColors.current.muted,
                modifier = Modifier.padding(horizontal = 4.dp),
            )

            Spacer(Modifier.height(4.dp))

            // Напоминания и выбор темы живут в «Настройках»: их ищут перед
            // использованием, а «О программе» открывают, когда хотят почитать
            // о приложении. Доступ — с главного экрана.
            AboutBlock(
                "Пары на сегодня",
                "На главной — весь день по порядку: предмет, аудитория, " +
                "преподаватель и время. Прошедшие пары остаются видимыми, " +
                "но приглушёнными; идущая подсвечена и показывает, сколько " +
                "осталось; у будущих — сколько ждать до начала. Когда день " +
                "закончится, появится ближайший день с занятиями."
            )

            AboutBlock(
                "Избранное",
                "Группа, преподаватель или аудитория отмечаются звездой и " +
                "появляются на главной списком. Нажатие открывает расписание " +
                "сразу. Добавить — кнопкой «Добавить»."
            )

            AboutBlock(
                "Работает без интернета",
                "Расписание загружается с сайта МИЭТ и хранится на телефоне. " +
                "Без сети показывается то, что уже сохранено."
            )

            AboutBlock(
                "Напоминания",
                "За 1, 5, 10 или 15 минут до начала пары. Включаются в " +
                "«Настройках» и работают без интернета."
            )

            AboutBlock(
                "Обновления",
                "Приложение само проверяет на GitHub, не вышел ли новый " +
                "релиз. Скачанный файл сверяется по контрольной сумме — " +
                "битый или подменённый не установится."
            )

            // Приложение ничего никуда не отправляет: ни аналитики, ни сбора
            // сведений об устройстве. Об этом честно сказано в интерфейсе,
            // потому что проверить это пользователь иначе не может.
            AboutBlock(
                "Данные",
                "Приложение ничего никуда не отправляет: ни статистики, ни " +
                "сведений об устройстве. Расписание загружается напрямую с " +
                "сайта МИЭТ, избранное и настройки хранятся только на телефоне."
            )

            // ───────── права на материалы ─────────
            //
            // Юридически обозначено, что права на название, эмблему и
            // расписание принадлежат МИЭТ, а приложение неофициальное.
            // Формулировка осторожная: «права остаются у правообладателя,
            // приложение использует открытое расписание для удобства и на
            // права не претендует».
            AboutBlock(
                "Права на материалы",
                "Приложение неофициальное: оно не является продуктом " +
                "Национального исследовательского университета МИЭТ и не " +
                "связано с ним. Права на название «МИЭТ», эмблему и данные о " +
                "расписании занятий принадлежат правообладателю — " +
                "Национальному исследовательскому университету МИЭТ. " +
                "Расписание приложение берёт с открытой страницы расписания " +
                "университета и использует только для удобства студентов и " +
                "преподавателей, не претендуя на права на эти материалы."
            )

            Spacer(Modifier.height(24.dp))
        }
    }
}

/** «Проверено: сегодня в 14:32» / «Обновления ещё не проверялись». */
internal fun lastCheckLabel(atMillis: Long): String {
    if (atMillis <= 0L) return "Обновления ещё не проверялись"
    val diff = System.currentTimeMillis() - atMillis
    val mins = diff / 60_000
    val hours = mins / 60
    return when {
        mins < 1 -> "Обновления проверены только что"
        mins < 60 -> "Обновления проверены $mins мин назад"
        hours < 24 -> "Обновления проверены $hours ч назад"
        else -> "Обновления проверены ${hours / 24} дн назад"
    }
}

@Composable
private fun AboutBlock(title: String, body: String) {
    Card(
        Modifier.fillMaxWidth().padding(bottom = 12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(1.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = MIET_BLUE)
            Spacer(Modifier.height(6.dp))
            Text(body, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}
