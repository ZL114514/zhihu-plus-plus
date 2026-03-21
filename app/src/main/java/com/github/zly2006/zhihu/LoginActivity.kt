package com.github.zly2006.zhihu

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.appcompat.app.AlertDialog
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.github.zly2006.zhihu.data.AccountData
import com.github.zly2006.zhihu.ui.PREFERENCE_NAME
import com.github.zly2006.zhihu.ui.components.WebviewComp
import com.github.zly2006.zhihu.ui.components.setupUpWebviewClient
import com.github.zly2006.zhihu.ui.raiseForStatus
import com.github.zly2006.zhihu.util.enableEdgeToEdgeCompat
import com.github.zly2006.zhihu.util.signFetchRequest
import com.github.zly2006.zhihu.util.telemetry
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import io.ktor.client.call.body
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlin.random.Random

class LoginActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdgeCompat()

        setContent {
            var useQrLogin by remember { mutableStateOf(false) }
            Surface(
                modifier = Modifier
                    .fillMaxSize()
                    .systemBarsPadding(),
            ) {
                if (useQrLogin) {
                    QRCodeLoginContent(
                        onBackToWebLogin = { useQrLogin = false },
                        onLoginSuccess = { finish() },
                    )
                } else {
                    WebLoginContent(
                        onOpenQrLogin = { useQrLogin = true },
                    )
                }
            }
        }
    }
}

@Composable
private fun WebLoginContent(onOpenQrLogin: () -> Unit) {
    val context = LocalContext.current
    Column(
        modifier = Modifier.fillMaxSize(),
    ) {
        WebviewComp(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            onLoad = { webView ->
                webView.setupUpWebviewClient()
                @SuppressLint("SetJavaScriptEnabled")
                webView.settings.javaScriptEnabled = true
                webView.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: WebResourceRequest,
                    ): Boolean {
                        if (request.url.toString() == "https://www.zhihu.com/") {
                            webView.settings.userAgentString = AccountData.ANDROID_USER_AGENT
                        }
                        return request.url?.scheme == "zhihu"
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        if (url == "https://www.zhihu.com/") {
                            val cookies =
                                CookieManager
                                    .getInstance()
                                    .getCookie("https://www.zhihu.com/")
                                    .orEmpty()
                                    .split(";")
                                    .mapNotNull {
                                        if (!it.contains("=")) return@mapNotNull null
                                        it.substringBefore("=").trim() to it.substringAfter("=")
                                    }.toMap()
                            runBlocking {
                                if (AccountData.verifyLogin(context, cookies)) {
                                    val data = AccountData.loadData(context)
                                    val preferences = context.getSharedPreferences(PREFERENCE_NAME, android.content.Context.MODE_PRIVATE)
                                    print(preferences.toString())
                                    AlertDialog
                                        .Builder(context)
                                        .apply {
                                            setTitle("登录成功")
                                            setMessage("欢迎回来，${data.username}")
                                            setPositiveButton("OK") { _, _ -> }
                                        }.create()
                                        .show()
                                    AccountData.saveData(context, data)
                                    telemetry(context, "login")
                                    (context as? LoginActivity)?.finish()
                                } else {
                                    AlertDialog
                                        .Builder(context)
                                        .apply {
                                            setTitle("登录失败")
                                            setMessage("请检查用户名和密码")
                                            setPositiveButton("OK") { _, _ -> }
                                        }.create()
                                        .show()
                                }
                            }
                        }
                    }
                }
                if (webView.url.isNullOrEmpty()) {
                    CookieManager.getInstance().removeAllCookies { }
                    CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
                    webView.loadUrl("https://www.zhihu.com/signin")
                }
            },
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.Center,
        ) {
            Button(onClick = onOpenQrLogin) {
                Text("使用二维码登录")
            }
        }
    }
}

@Serializable
private data class QrCodeCreateResponse(
    val expiresAt: Long,
    val link: String,
    val token: String,
)

@Serializable
private data class QrCodePollResponse(
    val status: Int,
)

@Composable
private fun QRCodeLoginContent(
    onBackToWebLogin: () -> Unit,
    onLoginSuccess: () -> Unit,
) {
    val context = LocalContext.current
    var loading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var qrHint by remember { mutableStateOf("请使用知乎 App 扫码") }

    LaunchedEffect(Unit) {
        val loginCookies = mutableMapOf<String, String>()
        val randomUa = randomLoginUserAgent()
        val client = AccountData.httpClient(context, loginCookies, randomUa)

        runCatching {
            client
                .get("https://www.zhihu.com/signin") {
                    applyQrLoginHeaders(randomUa, referer = "https://www.zhihu.com/signin")
                }.raiseForStatus()

            client
                .post("https://www.zhihu.com/udid") {
                    applyQrLoginHeaders(randomUa, referer = "https://www.zhihu.com/signin", fetch = true)
                }.raiseForStatus()

            val createResponse = client
                .post("https://www.zhihu.com/api/v3/account/api/login/qrcode") {
                    signFetchRequest()
                    applyQrLoginHeaders(randomUa, referer = "https://www.zhihu.com/signin", fetch = true)
                }.raiseForStatus()
                .body<JsonObject>()

            val qr = AccountData.decodeJson<QrCodeCreateResponse>(createResponse)
            if (System.currentTimeMillis() / 1000 >= qr.expiresAt) {
                error("二维码已过期，请重试")
            }

            qrBitmap = generateQrBitmap(qr.link)
            loading = false

            if (qr.token.isBlank()) error("二维码令牌为空")

            while (System.currentTimeMillis() / 1000 < qr.expiresAt) {
                val pollResponse = client
                    .get("https://www.zhihu.com/api/v3/account/api/login/qrcode/${qr.token}") {
                        signFetchRequest()
                        applyQrLoginHeaders(randomUa, referer = qr.link, fetch = true)
                    }.raiseForStatus()
                    .body<JsonObject>()

                val pollStatus = AccountData.decodeJson<QrCodePollResponse>(pollResponse).status
                if (pollStatus == 3) {
                    if (AccountData.verifyLogin(context, loginCookies, randomUa)) {
                        telemetry(context, "login")
                        onLoginSuccess()
                        return@LaunchedEffect
                    }
                    error("扫码完成，但登录校验失败")
                }
                qrHint = "等待扫码确认..."
                delay(2_000)
            }
            error("二维码已过期，请返回重试")
        }.onFailure {
            loading = false
            errorMessage = it.message ?: "二维码登录失败"
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (loading) {
            CircularProgressIndicator()
            Spacer(Modifier.height(12.dp))
            Text("正在生成二维码...")
        } else {
            qrBitmap?.let {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = "登录二维码",
                    modifier = Modifier.size(280.dp),
                )
                Spacer(Modifier.height(12.dp))
                Text(qrHint, style = MaterialTheme.typography.bodyMedium)
            }
            errorMessage?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onBackToWebLogin) {
                    Text("返回网页登录")
                }
            }
        }
    }
}

private fun randomLoginUserAgent(): String {
    val chromeVersion = "${Random.nextInt(123, 136)}.0.${Random.nextInt(5600, 7100)}.${Random.nextInt(80, 220)}"
    return listOf(
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$chromeVersion Safari/537.36",
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$chromeVersion Safari/537.36",
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$chromeVersion Safari/537.36",
    ).random()
}

private fun generateQrBitmap(content: String, size: Int = 800): Bitmap {
    val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size)
    val pixels = IntArray(size * size)
    for (y in 0 until size) {
        for (x in 0 until size) {
            pixels[y * size + x] = if (matrix[x, y]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
    }
    return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
}

private fun HttpRequestBuilder.applyQrLoginHeaders(
    userAgent: String,
    referer: String,
    fetch: Boolean = false,
) {
    header("User-Agent", userAgent)
    header("Accept", "application/json, text/plain, */*")
    header("Accept-Language", "zh-CN,zh;q=0.9,en-US;q=0.8,en;q=0.7")
    header("Origin", "https://www.zhihu.com")
    header("Referer", referer)
    if (fetch) {
        header("X-Requested-With", "fetch")
        header("Sec-Fetch-Dest", "empty")
        header("Sec-Fetch-Mode", "cors")
        header("Sec-Fetch-Site", "same-origin")
    } else {
        header("Sec-Fetch-Dest", "document")
        header("Sec-Fetch-Mode", "navigate")
        header("Sec-Fetch-Site", "same-origin")
    }
}
