package com.shijiu.wearmusic.ui.login

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sms
import com.ohmusic.app.data.remote.api.QrLoginSession
import com.shijiu.wearmusic.ServiceLocator
import com.shijiu.wearmusic.ui.NeteaseRed
import com.shijiu.wearmusic.ui.Routes
import com.shijiu.wearmusic.ui.ScreenScaffold
import com.shijiu.wearmusic.ui.SectionTitle
import com.shijiu.wearmusic.ui.TextSecondary
import com.shijiu.wearmusic.ui.buttonColors
import com.shijiu.wearmusic.ui.chipColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private enum class LoginMode { MENU, QR, PHONE }

@Composable
fun LoginScreen(nav: NavHostController) {
    var mode by remember { mutableStateOf(LoginMode.MENU) }
    val guestScope = rememberCoroutineScope()
    ScreenScaffold {
        when (mode) {
            LoginMode.MENU -> MenuPane(
                onQr = { mode = LoginMode.QR },
                onPhone = { mode = LoginMode.PHONE },
                onGuest = {
                    guestScope.launch {
                        ServiceLocator.container.accountRepo.loginGuest()
                        nav.popBackStack()
                    }
                }
            )
            LoginMode.QR -> QrPane(nav, onBack = { mode = LoginMode.MENU })
            LoginMode.PHONE -> PhonePane(nav, onBack = { mode = LoginMode.MENU })
        }
    }
}

@Composable
private fun MenuPane(onQr: () -> Unit, onPhone: () -> Unit, onGuest: () -> Unit) {
    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 44.dp, bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        item { SectionTitle("登录网易云账号") }
        item {
            Button(
                onClick = onQr,
                colors = chipColors(true),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Filled.QrCode2, null, Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Column {
                    Text("扫码登录", fontSize = 13.sp)
                    Text("手机网易 App 扫码", fontSize = 10.sp, color = TextSecondary)
                }
            }
        }
        item {
            Button(
                onClick = onPhone,
                colors = chipColors(false),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Filled.Sms, null, Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Column {
                    Text("手机验证码登录", fontSize = 13.sp)
                    Text("输入手机号 + 短信验证码", fontSize = 10.sp, color = TextSecondary)
                }
            }
        }
        item {
            Button(
                onClick = onGuest,
                colors = chipColors(false),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Filled.Person, null, Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Column {
                    Text("游客模式", fontSize = 13.sp)
                    Text("仅浏览与试听，无需账号", fontSize = 10.sp, color = TextSecondary)
                }
            }
        }
        item {
            Text(
                "每日推荐 / 云盘 / 私人漫游 / 雷达 /\n心动 / 收藏与打卡需要登录",
                fontSize = 10.sp,
                color = TextSecondary,
                lineHeight = 14.sp
            )
        }
    }
}

@Composable
private fun QrPane(nav: NavHostController, onBack: () -> Unit) {
    val authApi = ServiceLocator.container.authApi
    val accountRepo = ServiceLocator.container.accountRepo
    var regenerate by remember { mutableIntStateOf(0) }
    var session by remember { mutableStateOf<QrLoginSession?>(null) }
    var status by remember { mutableStateOf("正在生成二维码…") }
    var done by remember { mutableStateOf(false) }

    LaunchedEffect(regenerate) {
        done = false
        status = "正在生成二维码…"
        val s = runCatching { authApi.createQrLogin() }.getOrNull()
        session = s
        if (s == null) {
            status = "二维码生成失败，请重试"
            return@LaunchedEffect
        }
        status = "等待扫码…"
        while (isActive && !done) {
            delay(2500)
            val check = runCatching { authApi.checkQrLogin(s.key) }.getOrNull() ?: continue
            when {
                check.isWaitingConfirm -> status = "已扫码，请在手机上确认"
                check.isAuthorized -> {
                    status = "登录成功"
                    accountRepo.saveQrCookie(check.cookie.orEmpty())
                    done = true
                    nav.popBackStack()
                }
                check.isExpired -> {
                    status = "二维码已过期"
                    break
                }
            }
        }
    }

    val qrBitmap = remember(session?.qrImage, regenerate) {
        val dataUrl = session?.qrImage.orEmpty()
        val base64 = dataUrl.substringAfter("base64,", "")
        if (base64.isBlank()) null
        else runCatching { Base64.decode(base64, Base64.DEFAULT) }.getOrNull()
            ?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }?.asImageBitmap()
    }

    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 40.dp, bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        item {
            if (qrBitmap != null) {
                Image(
                    bitmap = qrBitmap,
                    contentDescription = "登录二维码",
                    modifier = Modifier
                        .size(128.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.White)
                )
            } else {
                Box(
                    Modifier.size(128.dp),
                    contentAlignment = Alignment.Center
                ) { CircularProgressIndicator() }
            }
        }
        item {
            Text(status, fontSize = 12.sp, color = if (done) NeteaseRed else TextSecondary)
        }
        if (status.contains("过期") || status.contains("失败")) {
            item {
                Button(
                    onClick = { regenerate++ },
                    modifier = Modifier.height(34.dp),
                    colors = buttonColors(true)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(Icons.Filled.Refresh, null, Modifier.size(16.dp))
                        Text("重新生成", fontSize = 12.sp)
                    }}
            }
        }
        item {
            Button(
                onClick = onBack,
                modifier = Modifier.height(32.dp),
                colors = buttonColors(false)
            ) { Text("返回", fontSize = 12.sp) }
        }
    }
}

@Composable
private fun PhonePane(nav: NavHostController, onBack: () -> Unit) {
    val accountRepo = ServiceLocator.container.accountRepo
    val scope = rememberCoroutineScope()
    var phone by remember { mutableStateOf("") }
    var captcha by remember { mutableStateOf("") }
    var sent by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 44.dp, bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        item { SectionTitle("手机验证码登录") }
        item {
            InputField(
                value = phone,
                onValueChange = { phone = it.filter(Char::isDigit).take(11) },
                hint = "手机号",
                keyboardType = KeyboardType.Phone
            )
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    InputField(
                        value = captcha,
                        onValueChange = { captcha = it.filter(Char::isDigit).take(6) },
                        hint = "验证码",
                        keyboardType = KeyboardType.Number
                    )
                }
                Spacer(Modifier.size(6.dp))
                Button(
                    onClick = {
                        scope.launch {
                            busy = true
                            message = null
                            val ok = accountRepo.sendCaptcha(phone)
                            message = if (ok) "验证码已发送" else "发送失败，请检查手机号"
                            sent = ok
                            busy = false
                        }
                    },
                    enabled = phone.length == 11 && !busy,
                    modifier = Modifier.height(38.dp),
                    colors = buttonColors(true)
                ) { Text("发送", fontSize = 11.sp) }
            }
        }
        if (sent) {
            item {
                Button(
                    onClick = {
                        scope.launch {
                            busy = true
                            message = "登录中…"
                            val ok = accountRepo.loginByPhone(phone, captcha)
                            message = if (ok) "登录成功" else "登录失败，请检查验证码"
                            busy = false
                            if (ok) nav.popBackStack()
                        }
                    },
                    enabled = captcha.length >= 4 && !busy,
                    modifier = Modifier.height(38.dp),
                    colors = buttonColors(true)
                ) { Text("登录", fontSize = 12.sp) }
            }
        }
        message?.let {
            item { Text(it, fontSize = 11.sp, color = TextSecondary) }
        }
        if (busy) item { CircularProgressIndicator(modifier = Modifier.size(24.dp)) }
        item {
            Button(
                onClick = onBack,
                modifier = Modifier.height(32.dp),
                colors = buttonColors(false)
            ) { Text("返回", fontSize = 12.sp) }
        }
    }
}

/** 手表上的文本输入框。 */
@Composable
fun InputField(
    value: String,
    onValueChange: (String) -> Unit,
    hint: String,
    keyboardType: KeyboardType = KeyboardType.Text
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = TextStyle(color = Color.White, fontSize = 13.sp),
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = ImeAction.Done),
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(Color(0xFF2B2B31))
            .padding(horizontal = 16.dp, vertical = 11.dp),
        decorationBox = { inner ->
            Box {
                if (value.isEmpty()) {
                    Text(hint, fontSize = 13.sp, color = Color(0xFF77777F))
                }
                inner()
            }
        }
    )
}
