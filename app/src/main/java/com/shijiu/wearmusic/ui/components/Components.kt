package com.shijiu.wearmusic.ui.components

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import com.ohmusic.app.data.model.Song
import com.shijiu.wearmusic.ui.CardBg
import com.shijiu.wearmusic.ui.CoverImage
import com.shijiu.wearmusic.ui.NeteaseRed
import com.shijiu.wearmusic.ui.SectionTitle
import com.shijiu.wearmusic.ui.TextSecondary
import com.shijiu.wearmusic.ui.chipColors

/** 曲目行：小封面 + 标题/歌手 + 菜单。 */
@Composable
fun SongRow(
    song: Song,
    isCurrent: Boolean = false,
    indexLabel: String? = null,
    onPlay: () -> Unit,
    onMenu: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (isCurrent) CardBg.copy(alpha = 0.95f) else CardBg, RoundedCornerShape(14.dp))
            .clickable(onClick = onPlay)
            .padding(start = 8.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CoverImage(song.smallCoverUrl ?: song.coverUrl, 40.dp)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                (indexLabel?.let { "$it " } ?: "") + song.title,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (isCurrent) NeteaseRed else Color.White,
                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal
            )
            Text(
                song.artist,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = TextSecondary
            )
        }
        if (onMenu != null) {
            Button(
                onClick = onMenu,
                modifier = Modifier.size(36.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent)
            ) {
                Icon(Icons.Filled.MoreVert, contentDescription = "更多", modifier = Modifier.size(22.dp))
            }
        }
    }
}

/** 通用图文卡片行（歌单 / 专辑 / 播客 / 歌手复用）。 */
@Composable
fun MediaRow(
    title: String,
    subtitle: String,
    coverUrl: String?,
    onClick: () -> Unit,
    badge: String? = null,
    onMenu: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(CardBg, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(start = 8.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CoverImage(coverUrl, 44.dp)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = Color.White
            )
            Text(
                subtitle,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = TextSecondary
            )
        }
        if (badge != null) {
            Box(
                modifier = Modifier
                    .background(NeteaseRed.copy(alpha = 0.85f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 5.dp, vertical = 2.dp)
            ) {
                Text(badge, fontSize = 9.sp, color = Color.White)
            }
            Spacer(Modifier.width(4.dp))
        }
        if (onMenu != null) {
            Button(
                onClick = onMenu,
                modifier = Modifier.size(36.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent)
            ) {
                Icon(Icons.Filled.MoreVert, contentDescription = "更多", modifier = Modifier.size(22.dp))
            }
        }
    }
}

/** 菜单项。 */
data class MenuItem(
    val icon: ImageVector,
    val label: String,
    val danger: Boolean = false,
    val action: () -> Unit
)

/** 全屏菜单对话框（歌曲操作等）。Material 3 移除了 wear Dialog，改用平台 Dialog + 遮罩。 */
@Composable
fun MenuDialog(
    showDialog: Boolean,
    title: String,
    items: List<MenuItem>,
    onDismiss: () -> Unit
) {
    if (!showDialog) return
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.8f)),
            contentAlignment = Alignment.Center
        ) {
            androidx.wear.compose.foundation.lazy.ScalingLazyColumn(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(vertical = 20.dp)
            ) {
                item { SectionTitle(title) }
                items(items.size) { i ->
                    val item = items[i]
                    Button(
                        onClick = {
                            onDismiss()
                            item.action()
                        },
                        colors = chipColors(false),
                        modifier = Modifier
                            .padding(horizontal = 16.dp, vertical = 3.dp)
                            .fillMaxWidth()
                    ) {
                        Icon(
                            item.icon,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = if (item.danger) NeteaseRed else TextSecondary
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            item.label,
                            color = if (item.danger) NeteaseRed else Color.White,
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                item {
                    Button(
                        onClick = onDismiss,
                        colors = chipColors(false),
                        modifier = Modifier
                            .padding(horizontal = 16.dp, vertical = 3.dp)
                            .fillMaxWidth()
                    ) {
                        Text("取消", fontSize = 13.sp, color = TextSecondary)
                    }
                }
            }
        }
    }
}

/** 确认对话框。 */
@Composable
fun ConfirmDialog(
    showDialog: Boolean,
    title: String,
    message: String,
    confirmText: String = "确定",
    danger: Boolean = false,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    if (!showDialog) return
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.8f)),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp)
            ) {
                Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Spacer(Modifier.height(6.dp))
                Text(
                    message,
                    fontSize = 12.sp,
                    color = TextSecondary,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onDismiss,
                        modifier = Modifier.height(36.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = CardBg)
                    ) {
                        Text("取消", fontSize = 12.sp)
                    }
                    Button(
                        onClick = {
                            onDismiss()
                            onConfirm()
                        },
                        modifier = Modifier.height(36.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (danger) NeteaseRed else Color.White,
                            contentColor = if (danger) Color.White else Color.Black
                        )
                    ) {
                        Text(confirmText, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

/** 轻提示（页面内短暂展示的操作结果）。 */
@Composable
fun InlineNotice(text: String?) {
    if (text.isNullOrBlank()) return
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(NeteaseRed.copy(alpha = 0.25f), RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(text, fontSize = 11.sp, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
