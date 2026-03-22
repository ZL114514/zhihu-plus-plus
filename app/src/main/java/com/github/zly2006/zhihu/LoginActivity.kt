package com.github.zly2006.zhihu

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.appcompat.app.AlertDialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.github.zly2006.zhihu.data.AccountData
import com.github.zly2006.zhihu.ui.PREFERENCE_NAME
import com.github.zly2006.zhihu.ui.components.CustomWebView
import com.github.zly2006.zhihu.ui.components.WebviewComp
import com.github.zly2006.zhihu.ui.components.setupUpWebviewClient
import com.github.zly2006.zhihu.util.enableEdgeToEdgeCompat
import com.github.zly2006.zhihu.util.telemetry
import kotlinx.coroutines.launch

class LoginActivity : ComponentActivity() {
    private fun buildCookieMap(): Map<String, String> = AccountData.parseCookieHeader(
        CookieManager
            .getInstance()
            .getCookie("https://www.zhihu.com/")
            .orEmpty(),
    )

    private fun resolveLoginUserAgent(useDesktopUserAgent: Boolean): String = if (useDesktopUserAgent) {
        AccountData.DESKTOP_USER_AGENT
    } else {
        AccountData.ANDROID_USER_AGENT
    }

    private fun applyUserAgent(
        webView: WebView,
        useDesktopUserAgent: Boolean,
    ) {
        webView.settings.userAgentString = resolveLoginUserAgent(useDesktopUserAgent)
    }

    private fun onLoginSuccess() {
        val data = AccountData.loadData(this)
        val preferences = getSharedPreferences(PREFERENCE_NAME, MODE_PRIVATE)
        print(preferences.toString())

        AlertDialog
            .Builder(this)
            .apply {
                setTitle("登录成功")
                setMessage("欢迎回来，${data.username}")
                setPositiveButton("OK") { _, _ ->
                }
            }.create()
            .show()
        AccountData.saveData(this, data)
        telemetry(this, "login")
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdgeCompat()

        setContent {
            val scope = rememberCoroutineScope()
            var showImportDialog by remember { mutableStateOf(false) }
            var importText by remember { mutableStateOf("") }
            var useDesktopUserAgent by remember {
                mutableStateOf(intent.getBooleanExtra(AccountData.EXTRA_ACCOUNT_USE_DESKTOP_UA, false))
            }
            var isWebViewInitialized by remember { mutableStateOf(false) }
            var webViewRef by remember { mutableStateOf<CustomWebView?>(null) }

            Surface(
                modifier = Modifier
                    .fillMaxSize()
                    .systemBarsPadding(),
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Button(
                            onClick = { showImportDialog = true },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("导入账号")
                        }
                        Button(
                            onClick = {
                                useDesktopUserAgent = !useDesktopUserAgent
                                webViewRef?.let { webView ->
                                    applyUserAgent(webView, useDesktopUserAgent)
                                    webView.loadUrl("https://www.zhihu.com/signin")
                                }
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(if (useDesktopUserAgent) "桌面 UA：开" else "桌面 UA：关")
                        }
                    }

                    WebviewComp(
                        modifier = Modifier.fillMaxSize(),
                        onLoad = { webView ->
                            webViewRef = webView
                            applyUserAgent(webView, useDesktopUserAgent)
                            if (isWebViewInitialized) {
                                return@WebviewComp
                            }
                            isWebViewInitialized = true
                            webView.setupUpWebviewClient()
                            @SuppressLint("SetJavaScriptEnabled")
                            webView.settings.javaScriptEnabled = true
                            CookieManager.getInstance().removeAllCookies { }
                            webView.webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(
                                    view: WebView?,
                                    request: WebResourceRequest,
                                ): Boolean {
                                    if (request.url.toString() == "https://www.zhihu.com/") {
                                        applyUserAgent(webView, useDesktopUserAgent)
                                    }
                                    return request.url?.scheme == "zhihu"
                                }

                                override fun onPageFinished(view: WebView?, url: String?) {
                                    super.onPageFinished(view, url)
                                    if (url == "https://www.zhihu.com/") {
                                        scope.launch {
                                            if (
                                                AccountData.verifyLogin(
                                                    context = this@LoginActivity,
                                                    cookies = buildCookieMap(),
                                                    userAgent = resolveLoginUserAgent(useDesktopUserAgent),
                                                )
                                            ) {
                                                onLoginSuccess()
                                            } else {
                                                AlertDialog
                                                    .Builder(this@LoginActivity)
                                                    .apply {
                                                        setTitle("登录失败")
                                                        setMessage("请检查用户名和密码")
                                                        setPositiveButton("OK") { _, _ ->
                                                        }
                                                    }.create()
                                                    .show()
                                            }
                                        }
                                    }
                                }
                            }
                            CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
                            webView.loadUrl("https://www.zhihu.com/signin")
                        },
                    )
                }

                if (showImportDialog) {
                    androidx.compose.material3.AlertDialog(
                        onDismissRequest = {
                            showImportDialog = false
                            importText = ""
                        },
                        title = { Text("导入账号") },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    "支持粘贴导出串、JSON 或完整 Cookie 字符串。",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                OutlinedTextField(
                                    value = importText,
                                    onValueChange = { importText = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    minLines = 4,
                                    label = { Text("账号内容") },
                                )
                            }
                        },
                        confirmButton = {
                            Button(
                                onClick = {
                                    scope.launch {
                                        val success = runCatching {
                                            AccountData.importAccount(
                                                context = this@LoginActivity,
                                                payload = importText,
                                                useDesktopUserAgent = useDesktopUserAgent,
                                            )
                                        }.getOrElse {
                                            false
                                        }
                                        if (success) {
                                            Toast.makeText(this@LoginActivity, "账号导入成功", Toast.LENGTH_SHORT).show()
                                            showImportDialog = false
                                            importText = ""
                                            finish()
                                        } else {
                                            Toast.makeText(this@LoginActivity, "账号导入失败，请检查内容是否有效", Toast.LENGTH_LONG).show()
                                        }
                                    }
                                },
                            ) {
                                Text("导入")
                            }
                        },
                        dismissButton = {
                            Button(
                                onClick = {
                                    showImportDialog = false
                                    importText = ""
                                },
                            ) {
                                Text("取消")
                            }
                        },
                    )
                }
            }
        }
    }
}
