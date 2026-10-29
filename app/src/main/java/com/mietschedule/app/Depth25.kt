package com.mietschedule.app

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * 2.5D-токены оформления: градиенты, тени и анимация нажатия, которых нет
 * в плоском Material3.
 *
 * «2.5D» здесь — не псевдообъём с нарисованными бликами, а мягкая глубина
 * из приёмов:
 *
 *  1. **Градиент вместо плоского цвета.** Шапка и фон идут не сплошным
 *     цветом, а вертикальным переливом: сверху светлее, снизу темнее (или
 *     наоборот). Глаз читает это как изгиб поверхности.
 *  2. **Цветная тень под приподнятыми элементами.** Карточки получают
 *     собственную тень [cardShadow], окрашенную в основной цвет, а не в
 *     серый по умолчанию: цветная тень под синей карточкой убедительнее
 *     чёрной.
 *  3. **Анимация нажатия.** Кнопка при нажатии чуть уменьшается и
 *     приподнимается тень — как будто палец продавил поверхность.
 *     [pressScale] даёт коэффициент масштаба от состояния нажатия.
 *
 * Все значения вынесены сюда, чтобы правка оформления была в одном месте.
 */
internal object Depth25 {

    /**
     * Градиент шапки: сверху светлый оттенок основного цвета, снизу —
     * насыщенный. Имитирует свет, падающий на приподнятую панель.
     */
    val topBar: Brush
        get() = Brush.verticalGradient(
            0.0f to Color(0xFF1E6BD6),
            0.55f to MIET_BLUE,
            1.0f to Color(0xFF00439B),
        )

    /**
     * Тень карточки, окрашенная в основной цвет.
     *
     * Цветная тень вместо чёрной: под синей карточкой серая тень выглядит
     * грязным пятном, а насыщенная синяя — как настоящая тень от источника
     * света того же цвета. Прозрачность 0.18 — заметно, но не тяжело.
     */
    val cardShadow: Color = Color(0x2E0057B8)

    /**
     * Мягкая тень для кнопок нижней навигации и плавающих элементов.
     * Слабее карточной: кнопка меньше, тень тоньше.
     */
    val softShadow: Color = Color(0x1A0057B8)

    /**
     * Коэффициент масштаба при нажатии: 1f в покое, [PRESSED_SCALE] при
     * нажатии. Читается состояние нажатия из [MutableInteractionSource] —
     * тот же источник, что и ripple, поэтому анимация идёт ровно тогда,
     * когда палец на поверхности.
     *
     * Применяется через `Modifier.graphicsLayer` (масштаб не влияет на
     * измерение), иначе нажатие дёргало бы вёрстку.
     */
    @Composable
    fun pressScale(interactionSource: MutableInteractionSource): Float {
        val pressed by interactionSource.collectIsPressedAsState()
        val scale by animateFloatAsState(
            targetValue = if (pressed) PRESSED_SCALE else 1f,
            animationSpec = tween(durationMillis = 90),
            label = "pressScale",
        )
        return scale
    }

    private const val PRESSED_SCALE = 0.96f
}
