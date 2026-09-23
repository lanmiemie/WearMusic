package com.shijiu.wearmusic.ui.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shijiu.wearmusic.util.YrcLine
import com.shijiu.wearmusic.util.YrcParser
import kotlin.math.roundToInt

/**
 * 卡拉OK逐字歌词行：排版一次、每帧只重绘。
 *
 * - 文本用 [rememberTextMeasurer] 排版一次得到 [androidx.compose.ui.text.TextLayoutResult]；
 * - 进度 [positionState] 只在 Canvas 绘制阶段读取，每帧变化仅触发重绘，
 *   不引起组合层重组，也就不会反复创建 AnnotatedString；
 * - 绘制时先整行铺「未唱色」，再按字符边界 + 字内小数进度裁剪出
 *   「已唱色」区域：整字跳变之间用字内平滑填充衔接，观感不生硬。
 */
@Composable
fun KaraokeLineText(
    line: YrcLine,
    positionState: State<Long>,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 14.sp,
    sungColor: Color = Color(0xFFE8383F),
    unsungColor: Color = Color(0xFF8E8E95),
    maxLines: Int = 3
) {
    val textMeasurer = rememberTextMeasurer()
    val style = TextStyle(
        fontSize = fontSize,
        fontWeight = FontWeight.Bold,
        lineHeight = fontSize * 1.3f,
        textAlign = TextAlign.Center,
        color = unsungColor
    )

    // 文本块按排版后实际宽度绘制，这里显式居中，与上下行普通 Text 对齐
    BoxWithConstraints(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        val density = LocalDensity.current
        val maxWidthPx = with(density) { maxWidth.toPx().roundToInt() }
        val layout = remember(line.text, style, maxWidthPx, maxLines) {
            textMeasurer.measure(
                text = AnnotatedString(line.text),
                style = style,
                maxLines = maxLines,
                overflow = TextOverflow.Clip,
                constraints = Constraints(maxWidth = maxWidthPx.coerceAtLeast(1))
            )
        }
        val widthDp = with(density) { layout.size.width.toDp() }
        val heightDp = with(density) { layout.size.height.toDp() }

        Canvas(modifier = Modifier.width(widthDp).height(heightDp)) {
            // 未唱底色：整行一次画完
            drawText(textLayoutResult = layout, color = unsungColor)

            val totalChars = line.text.length
            val charPos = YrcParser.charProgressAt(line, positionState.value)
            val sungFull = charPos.toInt().coerceIn(0, totalChars)
            if (sungFull <= 0) return@Canvas
            val fraction = charPos - sungFull
            val boundaryChar = if (fraction > 0f) sungFull else sungFull - 1
            if (boundaryChar < 0) return@Canvas

            // 已唱覆盖层：按行裁剪；个别异常布局（截断行等）直接整行点亮兜底
            runCatching {
                val lastLine = layout.getLineForOffset(boundaryChar)
                for (lineIndex in 0..lastLine) {
                    val right = if (lineIndex < lastLine) {
                        layout.getLineRight(lineIndex)
                    } else if (fraction > 0f && sungFull < totalChars) {
                        val box = layout.getBoundingBox(sungFull)
                        box.left + box.width * fraction
                    } else {
                        layout.getBoundingBox(boundaryChar).right
                    }
                    clipRect(
                        left = 0f,
                        top = layout.getLineTop(lineIndex),
                        right = right,
                        bottom = layout.getLineBottom(lineIndex),
                        clipOp = ClipOp.Intersect
                    ) {
                        drawText(textLayoutResult = layout, color = sungColor)
                    }
                }
            }.onFailure {
                drawText(textLayoutResult = layout, color = sungColor)
            }
        }
    }
}
