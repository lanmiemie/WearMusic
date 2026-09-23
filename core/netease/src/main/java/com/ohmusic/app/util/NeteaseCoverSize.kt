package com.ohmusic.app.util

/**
 * 封面清晰度档位。
 *
 * 背景：网易云下发的 `picUrl` 指向**原图**，实测单张可达 MB 级。
 * 如果所有位置都拉原图，列表滑动会疯狂抖动、流量也吃不消；
 * 反过来若全部只拉小图，播放页那张接近全屏的大封面又会糊。
 * 所以按「这张图最终会被画成多大」分档，而不是一刀切。
 *
 * | 档位 | 目标边长 | 典型位置 | 实测体积 |
 * |---|---|---|---|
 * | [Thumb] | 100 | 列表 / 歌单行的 48dp 小方图 | 3.9 KB |
 * | [List] | 320 | 发现页歌单卡、搜索结果 | 23.3 KB |
 * | [Detail] | 480 | 歌单详情页头图、模糊背景 | 43.5 KB |
 * | [Full] | 720 | 播放页大封面 | 54.7 KB |
 *
 * （体积为 `?param=WxW` 实测值，测试封面原图 47.7 KB。）
 *
 * ### 关于「最大能要到多大」——这里有个坑
 *
 * `param` **不是**「想要多大就给多大」。服务端有一个阈值，超过之后它不会拒绝、
 * 不会报错，而是**直接把原始 JPEG 原样吐回来**，完全不理会你要的尺寸。
 *
 * 以一张 500×500 的封面为例（不带参数时 47.7 KB）：
 *
 * ```
 * ?param=320y320  →   23.3 KB   320×320   ← 真的裁了
 * ?param=400y400  →   33.2 KB   400×400   ← 真的裁了
 * ?param=420y420  →   102.0 KB  420×420   ← 开始「放大重编码」，反而变大
 * ?param=800y800  →   132.6 KB  500×500   ← 回退原图，比原图还大 3 倍
 * ```
 *
 * 也就是说，**把请求值调大不但不会更清晰，还会踩进最慢的那条路径**：
 * 拿到的是一张被重新编码、体积远超原图的图，加载器再把它缩到控件大小。
 * 而常见封面的较短边多在 300–640 之间，阈值同样落在这个区间里。
 *
 * 取 720 是权衡后的结论：仍然明显高于 [Detail]，播放页的观感有实质提升；
 * 同时因为低于常见封面的短边，绝大多数情况下不会触发回退。
 * 即便个别封面真的回退了，损失的也只是流量，不会更糊。
 *
 * **不要**为了「更清晰」把它调大——实测 800 就已经会让部分封面退化。
 */
object ArtworkSize {
    /** 列表缩略图，对应 UI 上约 48dp 的方形封面。 */
    const val Thumb = 100

    /** 常规列表项，如发现页歌单卡。 */
    const val List = 320

    /** 较大的展示位，如歌单详情页头图与模糊背景。 */
    const val Detail = 480

    /** 播放页主封面。上限受服务端「回退原图」阈值约束，详见上文。 */
    const val Full = 720
}

/**
 * 网易云封面 CDN 支持通过 `?param=WxH` 让服务端按需裁剪。
 *
 * 统一在请求时追加尺寸参数，既解决「列表拉原图慢到超时、封面全部退回默认图」
 * 的老问题，也让不同展示位拿到各自合适的清晰度。
 */
private const val NET_EASE_COVER_HOST_SUFFIX = "music.126.net"

/**
 * 给网易云封面地址追加尺寸参数。
 *
 * 只处理网易云自家的 CDN，其它地址（本地 content Uri、用户自建网关等）原样返回。
 * 已经带过 `param=` 的地址保持不动，避免叠加出 `?param=..&param=..` 这种无意义参数。
 *
 * ⚠️ [size] 不要随便调大：服务端只在 `size` 不超过原图**较短边**时才真的裁剪，
 * 超出后会静默回退成完整原图（更大、更慢，且完全不是你要的尺寸）。
 * 取值请走 [ArtworkSize] 中已实测过的档位。
 */
fun String.withNetEaseCoverSize(size: Int = ArtworkSize.List): String {
    if (!startsWith("http") || !contains(NET_EASE_COVER_HOST_SUFFIX)) return this
    if (contains("param=")) return this
    val separator = if (contains('?')) '&' else '?'
    return "$this$separator" + "param=${size}y$size"
}

/** 可空版本，方便直接接在 `?.` 链式调用后面。 */
fun String?.withNetEaseCoverSizeOrNull(size: Int = ArtworkSize.List): String? =
    this?.withNetEaseCoverSize(size)
