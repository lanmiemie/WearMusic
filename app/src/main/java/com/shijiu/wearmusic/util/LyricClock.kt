package com.shijiu.wearmusic.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import com.shijiu.wearmusic.playback.PlaybackManager
import kotlin.math.abs

/**
 * 歌词时钟：在两次上游轮询（约 500ms 一次）之间以帧信号平滑插值播放位置。
 *
 * - 播放中：每帧按真实帧间隔在本地累加，逐字高亮 60fps 丝滑推进；
 * - 上游位置到达时：偏差在 [SOFT_TOLERANCE_MS] 内视为正常采样误差忽略
 *   （本地插值更细），超过则判定为 seek / 换歌 / 时钟漂移，直接跳到上游值；
 * - 暂停：停止累加并把位置对齐上游。
 *
 * 返回的 [State] 只应在绘制阶段（Canvas lambda）读取，
 * 这样位置每帧变化只触发重绘、不触发重组。
 */
@Composable
fun rememberLyricPositionMs(playback: PlaybackManager): State<Long> {
    val position = remember { mutableLongStateOf(playback.currentPosition()) }
    val isPlaying by playback.isPlaying.collectAsState()

    // 帧驱动：只在播放中累加；暂停/恢复时本效果重启，帧基准自动复位。
    LaunchedEffect(isPlaying) {
        if (!isPlaying) {
            position.longValue = playback.currentPosition()
            return@LaunchedEffect
        }
        var lastFrameNanos = 0L
        while (true) {
            withFrameNanos { now ->
                if (lastFrameNanos != 0L) {
                    position.longValue += (now - lastFrameNanos) / 1_000_000L
                }
                lastFrameNanos = now
            }
        }
    }

    // 上游校正：小偏差忽略（本地插值更平滑），大偏差视为 seek 直接跳变。
    LaunchedEffect(Unit) {
        playback.positionMs.collect { upstream ->
            if (abs(upstream - position.longValue) > SOFT_TOLERANCE_MS) {
                position.longValue = upstream
            }
        }
    }

    return position
}

/** 小于该偏差（毫秒）的采样误差直接忽略，避免高频抖动。 */
private const val SOFT_TOLERANCE_MS = 250L
