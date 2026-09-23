package com.ohmusic.app.util

import com.ohmusic.app.data.remote.NeteaseConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 模块纯 JVM 回归测试：封面尺寸参数追加 + 本地/在线 id 的负数映射约定。
 */
class NeteaseModuleTest {

    private val neteaseCover = "https://p3.music.126.net/abc==/123.jpg"

    @Test
    fun `appends size param to netease cover url`() {
        assertEquals("$neteaseCover?param=320y320", neteaseCover.withNetEaseCoverSize())
    }

    @Test
    fun `does not append param twice`() {
        val already = "$neteaseCover?param=100y100"
        assertEquals(already, already.withNetEaseCoverSize(ArtworkSize.Full))
    }

    @Test
    fun `keeps existing query when appending`() {
        val withQuery = "$neteaseCover?foo=bar"
        assertEquals("$withQuery&param=480y480", withQuery.withNetEaseCoverSize(ArtworkSize.Detail))
    }

    @Test
    fun `leaves non netease urls untouched`() {
        val foreign = "https://example.com/cover.jpg"
        assertEquals(foreign, foreign.withNetEaseCoverSize())
        val local = "content://media/external/audio/albumart/42"
        assertEquals(local, local.withNetEaseCoverSize())
    }

    @Test
    fun `nullable overload passes null through`() {
        val nullUrl: String? = null
        assertNull(nullUrl.withNetEaseCoverSizeOrNull())
        assertEquals("$neteaseCover?param=100y100", neteaseCover.withNetEaseCoverSizeOrNull(100))
    }

    @Test
    fun `negative id mapping is symmetric`() {
        val songId = 657657657L
        val negative = NeteaseConstants.negativeIdFor(songId)
        assertTrue(negative < 0)
        assertEquals(songId, NeteaseConstants.songIdFromNegativeId(negative))
        // 与 MediaStore 正数 id 空间隔离
        assertTrue(negative != 42L || songId == -42L)
    }
}
