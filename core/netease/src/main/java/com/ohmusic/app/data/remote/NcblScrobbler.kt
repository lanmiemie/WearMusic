package com.ohmusic.app.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlin.random.Random

/**
 * 网易云"听歌打卡"上报器（NCBL v3 客户端日志协议）。
 *
 * ### 为什么不用 /scrobble（weapi/eapi weblog）
 * 2024 年起网易云对第三方 weblog 上报做了静默风控：无论 weapi、eapi，
 * 换任何上报域名（music.163.com / interface / clientlog）都返回
 * `{"code":200,"data":"success"}`，但服务端并不记账（听歌排行/听歌数量均无变化）。
 * 实测 16 小时无任何落库。
 *
 * ### 本实现的思路（对齐 SPlayer-Next 的 scrobble_v1）
 * 仿网易云桌面客户端的播放日志上报：把一次播放拆成
 * `_plv`（播放开始）与 `_pld`（播放结束）两条日志记录，用桌面客户端同款
 * NCBL v3 二进制协议（ChaCha20 + RSA 会话密钥 + zstd）加密后上传到
 * `clientlog3.music.163.com`。服务端以 `successfiles` 回执确认真实接收，
 * 听歌排行数分钟内即可落库。
 *
 * ### 零依赖说明
 * - ChaCha20：本文件内置实现（约 60 行）；
 * - RSA：java.math.BigInteger.modPow；
 * - zstd：日志体仅 1~2KB，压缩无收益，这里构造**全 Raw Block 的合法 zstd 帧**
 *   （服务端照常解压），免去引入数 MB 的 zstd native 库，不增加 APK 体积。
 */
object NcblScrobbler {

    private const val ENDPOINT = "https://clientlog3.music.163.com/api/clientlog/encrypt/upload?multiupload=true"
    private const val APP_VERSION = "3.1.37"
    private const val APP_VERSION_CODE = "205354"
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Safari/537.36 Chrome/91.0.4472.164 NeteaseMusicDesktop/$APP_VERSION"

    /** NCBL v3 会话密钥 RSA 公钥 N（客户端同款，e=65537）。 */
    private val RSA_N = java.math.BigInteger(
        "fd90bd466ff9bc8a3fec2fbcf263b90d5c564879fa5d7aab89b31c1d5cb4139d", 16
    )
    private val RSA_E = java.math.BigInteger.valueOf(65537L)

    private const val FIELD_SEP = '\u0001'

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private val octetStream = "application/octet-stream".toMediaType()

    /** 打卡结果：`accepted` 为服务端 successfiles 回执确认；失败时 [error] 给出原因。 */
    data class Result(val accepted: Boolean, val error: String? = null)

    /**
     * 上报一次播放（PLV + PLD 双日志）。
     *
     * @param cookie 当前登录 cookie 原始串（必须含 MUSIC_U）
     * @param songId 网易云歌曲 id
     * @param playedSec 实际播放秒数
     * @param totalSec 歌曲总时长秒数
     * @param title 歌名（桌面客户端日志会带，未知可传空串）
     * @param artist 歌手名（同上）
     * @param sourceId 播放来源 id（歌单/FM 等；缺省用歌曲自身 id，服务端接受）
     * @return 服务端是否确认接收
     * @throws Exception 网络错误 / 参数缺失 / 服务端拒收
     */
    fun scrobble(
        cookie: String,
        songId: Long,
        playedSec: Int,
        totalSec: Int,
        title: String = "",
        artist: String = "",
        sourceId: Long? = null
    ): Result = uploadBoth(cookie, songId, playedSec, totalSec, title, artist, sourceId)

    suspend fun scrobbleSuspend(
        cookie: String,
        songId: Long,
        playedSec: Int,
        totalSec: Int,
        title: String = "",
        artist: String = "",
        sourceId: Long? = null
    ): Result = withContext(Dispatchers.IO) {
        uploadBoth(cookie, songId, playedSec, totalSec, title, artist, sourceId)
    }

    private fun uploadBoth(
        cookie: String,
        songId: Long,
        playedSec: Int,
        totalSec: Int,
        title: String,
        artist: String,
        sourceId: Long?
    ): Result {
        val parsed = parseCookie(cookie)
        val token = parsed["MUSIC_U"] ?: return Result(false, "缺少 MUSIC_U 登录凭证")
        val sid = (sourceId ?: songId).toString()
        val res = mapOf(
            "id" to songId.toString(), "type" to "song", "name" to title,
            "artist" to artist, "bitrate" to 320, "level" to "exhigh",
            "fee" to 0, "time" to totalSec.coerceAtLeast(playedSec)
        )
        val src = mapOf("id" to sid, "type" to "track", "name" to "track")

        // PLV（播放开始）
        val plv = linkedMapOf(
            "mode" to "circulation", "download" to 0, "alg" to "", "status" to "front",
            "id" to res["id"], "bitrate" to res["bitrate"], "type" to "song",
            "is_listentogether" to 0, "source" to src["name"], "is_heart" to 0,
            "resource_ratio" to "", "resource_time" to res["time"], "musiceffect_id" to "",
            "app_mode" to 2, "bitrate_level" to res["level"], "vipType" to (parsed["vipType"] ?: ""),
            "fee" to res["fee"], "file" to 4, "rightSource" to 0,
            "sourceId" to src["id"], "sourcetype" to src["type"], "libra_abt" to "",
            "channel" to (parsed["channel"] ?: "netease"), "curStartChannel" to ""
        )
        // PLD（播放结束）：复制 PLV 后覆盖播放相关字段；end 字段对齐桌面客户端日志
        val pld = LinkedHashMap<String, Any?>(plv)
        pld.putAll(
            mapOf(
                "time" to playedSec.coerceAtLeast(1), "realtime" to playedSec.coerceAtLeast(1),
                "musiceffect_id" to "1001", "app_mode" to 1, "lyriceffect" to "default",
                "displayMode" to "classic", "end" to "interrupt"
            )
        )

        val ts = System.currentTimeMillis() / 1000
        val plvBody = "$ts${FIELD_SEP}_plv$FIELD_SEP" + toJson(plv)
        val pldBody = "$ts${FIELD_SEP}_pld$FIELD_SEP" + toJson(pld)

        val meta = buildMetaJson(parsed)
        val cookieStr = buildCookieStr(parsed)

        val plvOk = uploadWithRetry(meta, cookieStr, plvBody)
        if (!plvOk.first) return Result(false, "PLV 日志上报失败：${plvOk.second}")

        val pldOk = uploadWithRetry(meta, cookieStr, pldBody)
        if (!pldOk.first) return Result(false, "PLD 日志上报失败：${pldOk.second}")

        return Result(true)
    }

    /**
     * 最多重试 3 次（对齐 SPlayer-Next），每次间隔 200ms×attempt。
     * 永不抛异常：[Pair.first] 为服务端 successfiles 回执确认，
     * [Pair.second] 为失败原因（网络异常或拒收详情）。
     */
    private fun uploadWithRetry(meta: String, cookieStr: String, body: String): Pair<Boolean, String> {
        var lastErr = ""
        for (attempt in 1..3) {
            try {
                return uploadOnce(meta, cookieStr, body)
            } catch (e: Exception) {
                lastErr = e.message ?: e.javaClass.simpleName
                if (attempt < 3) {
                    try {
                        Thread.sleep(attempt * 200L)
                    } catch (_: InterruptedException) {
                    }
                }
            }
        }
        return Pair(false, lastErr)
    }

    private fun uploadOnce(meta: String, cookieStr: String, body: String): Pair<Boolean, String> {
        val payload = encryptNcbl(meta.toByteArray(Charsets.UTF_8), body.toByteArray(Charsets.UTF_8))
        val boundary = uuidHex()
        val fileName = "op_${Random.nextInt(10000, 99999)}_0_${Random.nextLong(1, 4294967295L)}"
        val multipart = buildString {
            append("--").append(boundary).append("\r\n")
            append("Content-Disposition: form-data; name=\"file\"; filename=\"").append(fileName).append("\"\r\n")
            append("Content-Type: multipart/form-data\r\n\r\n")
        }.toByteArray(Charsets.UTF_8) + payload +
            "\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8)

        val request = Request.Builder()
            .url(ENDPOINT)
            .header("Referer", "https://music.163.com/di")
            .header("User-Agent", USER_AGENT)
            // 注意：不要手动设置 Accept-Encoding——手动设置后 OkHttp 不再透明解压响应，
            // 若服务端回 gzip 会把成功响应当乱码，误判为拒收（与 Python requests 行为不同）
            .header("Accept-Language", "zh-CN,zh;q=0.8")
            .header("Cookie", cookieStr)
            .post(multipart.toRequestBody("multipart/form-data; boundary=$boundary".toMediaType()))
            .build()

        http.newCall(request).execute().use { response ->
            val raw = response.body?.bytes() ?: ByteArray(0)
            // 兜底：若响应仍带 gzip 编码（中间代理/手动头导致 OkHttp 未自动解压），
            // 按 gzip 魔数 0x1F 0x8B 自行解压，避免把压缩体当乱码误判为拒收
            val text = if (raw.size >= 2 && raw[0] == 0x1F.toByte() && raw[1] == 0x8B.toByte()) {
                runCatching {
                    java.util.zip.GZIPInputStream(raw.inputStream()).use { gz ->
                        gz.readBytes().toString(Charsets.UTF_8)
                    }
                }.getOrDefault("")
            } else {
                raw.toString(Charsets.UTF_8)
            }
            val code = Regex("\"code\"\\s*:\\s*(\\d+)").find(text)
                ?.let { it.groupValues[1].toIntOrNull() }
            val accepted = code == 200 && text.contains("\"$fileName\"")
            return if (accepted) {
                Pair(true, "")
            } else {
                // 只保留可打印字符，弹窗/提示里不出现乱码
                val readable = text.filter { it.code in 0x20..0x7E || it.code > 0xFF }.take(160)
                Pair(false, "HTTP ${response.code} / ${readable.ifEmpty { "响应体为空或非文本" }}")
            }
        }
    }

    // ────────────────────────────────────────────────────────
    // Cookie 上下文
    // ────────────────────────────────────────────────────────

    private fun parseCookie(raw: String): Map<String, String> =
        raw.split(";").mapNotNull {
            val idx = it.indexOf('=')
            if (idx <= 0) null else it.substring(0, idx).trim() to it.substring(idx + 1).trim()
        }.toMap()

    private fun buildCookieStr(c: Map<String, String>): String {
        // 桌面客户端协议：版本字段强制用桌面端常量，
        // 不继承 cookie 里可能存在的移动版 appver（否则拼出 8.x.x.205354 之类的畸形值）
        val version = APP_VERSION
        val versionCode = APP_VERSION_CODE
        return listOf(
            "JSESSIONID-WYYY=${c["JSESSIONID-WYYY"].orEmpty()}",
            "MUSIC_U=${c["MUSIC_U"].orEmpty()}",
            "NMTID=${c["NMTID"].orEmpty()}",
            "WEVNSM=${c["WEVNSM"] ?: "1.0.0"}",
            "WNMCID=${c["WNMCID"] ?: (randomHex(6) + "." + System.currentTimeMillis() + ".01.0")}",
            "__csrf=${c["__csrf"].orEmpty()}",
            "__remember_me=true",
            "_iuqxldmzr_=33",
            "_ntes_nnid=${c["_ntes_nnid"] ?: ","}",
            "_ntes_nuid=${c["_ntes_nuid"].orEmpty()}",
            "appver=$version.$versionCode",
            "channel=${c["channel"] ?: "netease"}",
            "clientSign=${c["clientSign"].orEmpty()}",
            "deviceId=${c["deviceId"].orEmpty()}",
            "mode=${c["mode"].orEmpty()}",
            "ntes_kaola_ad=1",
            // 桌面客户端日志协议：os 恒为 pc，不继承 app cookie 里可能存在的其他值
            "os=pc",
            "osver=${c["osver"] ?: "Microsoft-Windows-10-Professional-build-19045-64bit"}"
        ).joinToString("; ")
    }

    private fun buildMetaJson(c: Map<String, String>): String {
        val version = APP_VERSION
        val versionCode = APP_VERSION_CODE
        val json = linkedMapOf<String, String>(
            "JSESSIONID-WYYY" to c["JSESSIONID-WYYY"].orEmpty(),
            "MUSIC_U" to c["MUSIC_U"].orEmpty(),
            "NMTID" to c["NMTID"].orEmpty(),
            "WEVNSM" to (c["WEVNSM"] ?: "1.0.0"),
            "WNMCID" to (c["WNMCID"] ?: (randomHex(6) + "." + System.currentTimeMillis() + ".01.0")),
            "__csrf" to c["__csrf"].orEmpty(),
            "_iuqxldmzr_" to "33",
            "_ntes_nnid" to (c["_ntes_nnid"] ?: ","),
            "_ntes_nuid" to c["_ntes_nuid"].orEmpty(),
            "appver" to "$version.$versionCode",
            "channel" to (c["channel"] ?: "netease"),
            "clientSign" to c["clientSign"].orEmpty(),
            "deviceId" to c["deviceId"].orEmpty(),
            "mode" to c["mode"].orEmpty(),
            "ntes_kaola_ad" to "1",
            "os" to "pc",
            "osver" to (c["osver"] ?: "Microsoft-Windows-10-Professional-build-19045-64bit")
        )
        return json.entries.joinToString(",", "{", "}") { (k, v) ->
            "\"$k\":\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        }
    }

    // ────────────────────────────────────────────────────────
    // NCBL v3 二进制封装
    // ────────────────────────────────────────────────────────

    private val RSA_KEY_MAX_FIRST_BYTE = 0xA3

    private fun encryptNcbl(meta: ByteArray, body: ByteArray): ByteArray {
        val keyA = ByteArray(32).also { Random.nextBytes(it) }
        if ((keyA[0].toInt() and 0xFF) >= RSA_KEY_MAX_FIRST_BYTE) {
            keyA[0] = (RSA_KEY_MAX_FIRST_BYTE - 1).toByte()
        }
        val keyB = java.math.BigInteger(1, keyA).modPow(RSA_E, RSA_N).to32Bytes()

        val uuid = ByteArray(16).also { Random.nextBytes(it) }
        uuid[6] = ((uuid[6].toInt() and 0x0F) or 0x40).toByte()
        uuid[8] = ((uuid[8].toInt() and 0x3F) or 0x80).toByte()
        val nonce = uuid.copyOfRange(0, 12)
        val counter = leInt(uuid, 12) ushr 2
        val baseSeq = Random.nextInt(0, 0x10000)

        val metaCipher = ChaCha20.xor(keyB, counter, nonce, meta)
        val metaBlock = ByteArray(4 + metaCipher.size)
        putLe16(metaBlock, 0, META_BLOCK_TYPE)
        putLe16(metaBlock, 2, metaCipher.size)
        metaCipher.copyInto(metaBlock, 4)

        val compressed = zstdRawFrame(body)
        val trailing = java.io.ByteArrayOutputStream()
        var seq = baseSeq
        var off = 0
        do {
            val end = min(off + MAX_FRAME, compressed.size)
            val cipher = ChaCha20.xor(keyA, counter, nonce, compressed.copyOfRange(off, end))
            trailing.write(le16(cipher.size))
            trailing.write(le32(seq))
            trailing.write(cipher)
            seq++
            off = end
        } while (off < compressed.size)

        val trailingBytes = trailing.toByteArray()
        val header = ByteArray(HEADER_FIXED_LEN)
        SIGMA_MAGIC.copyInto(header, 0)
        putLe32(header, 4, NCBL_VERSION)
        putLe16(header, 8, HEADER_FIXED_LEN + metaBlock.size)
        uuid.copyInto(header, 10)
        keyB.copyInto(header, 26)
        putLe32(header, 58, baseSeq)
        putLe32(header, 62, seq - 1)
        putLe32(header, 66, trailingBytes.size)

        return header + metaBlock + trailingBytes
    }

    private const val NCBL_VERSION = 3
    private const val HEADER_FIXED_LEN = 70
    private const val META_BLOCK_TYPE = 0x4343
    private const val MAX_FRAME = 0x8000
    private val SIGMA_MAGIC = byteArrayOf('N'.code.toByte(), 'C'.code.toByte(), 'B'.code.toByte(), 'L'.code.toByte())

    private fun java.math.BigInteger.to32Bytes(): ByteArray {
        val raw = toByteArray() // 含符号位的 BE
        val out = ByteArray(32)
        // 取低 32 字节（modPow 结果不会超过 2^256），右对齐填充（前导零即密钥前导零）
        val src = if (raw.size > 32) raw.copyOfRange(raw.size - 32, raw.size) else raw
        src.copyInto(out, 32 - src.size)
        return out
    }

    /**
     * 构造「全 Raw Block」的合法 zstd 帧（RFC 8878）：
     * 服务端固定按 zstd 解压 body，Raw Block 帧无需任何压缩库即可通过。
     */
    private fun zstdRawFrame(data: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write(byteArrayOf(0x28, 0xB5.toByte(), 0x2F, 0xFD.toByte()))
        val n = data.size
        if (n < 256) {
            out.write(0x20) // Single_Segment=1, FCS_flag=0 -> FCS 1 字节
            out.write(n)
        } else {
            require(n <= 64 * 1024 + 255) { "NCBL body 过大：$n" }
            out.write(0x60) // Single_Segment=1, FCS_flag=1 -> FCS 2 字节（值 = size - 256）
            val fcs = le16(n - 256)
            out.write(fcs)
        }
        // Block_Header 3 字节 LE：Last_Block=1，Block_Type=00（Raw），Size=n
        val bh = 1 or (n shl 3)
        out.write(byteArrayOf((bh and 0xFF).toByte(), ((bh shr 8) and 0xFF).toByte(), ((bh shr 16) and 0xFF).toByte()))
        out.write(data)
        return out.toByteArray()
    }

    // ────────────────────────────────────────────────────────
    // ChaCha20（对齐 ncbl.ts 的实现：state = sigma + key + counter + nonce）
    // ────────────────────────────────────────────────────────

    private object ChaCha20 {
        fun xor(key: ByteArray, counter: Int, nonce: ByteArray, data: ByteArray): ByteArray {
            val out = ByteArray(data.size)
            var off = 0
            var block = counter
            while (off < data.size) {
                val ks = keystreamBlock(key, block, nonce)
                val end = min(off + 64, data.size)
                for (i in off until end) {
                    out[i] = (data[i].toInt() xor ks[i - off].toInt()).toByte()
                }
                off = end
                block++
            }
            return out
        }

        private fun rotl(x: Int, n: Int): Int = (x shl n) or (x ushr (32 - n))

        private fun keystreamBlock(key: ByteArray, counter: Int, nonce: ByteArray): ByteArray {
            val s = IntArray(16)
            s[0] = 0x61707865
            s[1] = 0x3320646E
            s[2] = 0x79622D32
            s[3] = 0x6B206574
            for (i in 0 until 8) s[4 + i] = leInt(key, i * 4)
            s[12] = counter
            for (i in 0 until 3) s[13 + i] = leInt(nonce, i * 4)
            val w = s.copyOf()
            repeat(10) {
                quarterRound(w, 0, 4, 8, 12); quarterRound(w, 1, 5, 9, 13)
                quarterRound(w, 2, 6, 10, 14); quarterRound(w, 3, 7, 11, 15)
                quarterRound(w, 0, 5, 10, 15); quarterRound(w, 1, 6, 11, 12)
                quarterRound(w, 2, 7, 8, 13); quarterRound(w, 3, 4, 9, 14)
            }
            val out = ByteArray(64)
            for (i in 0 until 16) putLe32(out, i * 4, w[i] + s[i])
            return out
        }

        private fun quarterRound(s: IntArray, a: Int, b: Int, c: Int, d: Int) {
            s[a] += s[b]; s[d] = rotl(s[d] xor s[a], 16)
            s[c] += s[d]; s[b] = rotl(s[b] xor s[c], 12)
            s[a] += s[b]; s[d] = rotl(s[d] xor s[a], 8)
            s[c] += s[d]; s[b] = rotl(s[b] xor s[c], 7)
        }
    }

    // ────────────────────────────────────────────────────────
    // 小工具
    // ────────────────────────────────────────────────────────

    private fun leInt(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or
            ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or
            ((b[off + 3].toInt() and 0xFF) shl 24)

    private fun putLe32(b: ByteArray, off: Int, v: Int) {
        b[off] = (v and 0xFF).toByte()
        b[off + 1] = ((v shr 8) and 0xFF).toByte()
        b[off + 2] = ((v shr 16) and 0xFF).toByte()
        b[off + 3] = ((v shr 24) and 0xFF).toByte()
    }

    private fun putLe16(b: ByteArray, off: Int, v: Int) {
        b[off] = (v and 0xFF).toByte()
        b[off + 1] = ((v shr 8) and 0xFF).toByte()
    }

    private fun le16(v: Int): ByteArray = byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())

    private fun le32(v: Int): ByteArray = byteArrayOf(
        (v and 0xFF).toByte(),
        ((v shr 8) and 0xFF).toByte(),
        ((v shr 16) and 0xFF).toByte(),
        ((v shr 24) and 0xFF).toByte()
    )

    private fun randomHex(bytes: Int): String =
        ByteArray(bytes).also { Random.nextBytes(it) }.joinToString("") { "%02x".format(it) }

    private fun uuidHex(): String {
        val b = ByteArray(16).also { Random.nextBytes(it) }
        return b.joinToString("") { "%02x".format(it) }
    }

    /** 极简 JSON 序列化（值全是 string/number/null，无嵌套）。 */
    private fun toJson(map: Map<String, Any?>): String = map.entries.joinToString(",", "{", "}") { (k, v) ->
        val value = when (v) {
            null -> "null"
            is Number, is Boolean -> v.toString()
            else -> "\"" + v.toString().replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        }
        "\"$k\":$value"
    }
}
