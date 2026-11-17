package com.example.ui.screens

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.data.model.MoodleSessionCredentials
import com.example.ui.SyncStatusUi
import com.example.ui.components.StatusIndicatorPill

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoodleConnectScreen(
    session: MoodleSessionCredentials,
    syncStatus: SyncStatusUi,
    onConnectBrowserSession: (baseUrl: String, cookie: String, sesskey: String, name: String) -> Unit,
    onConnectWsCredentials: (baseUrl: String, username: String, password: String) -> Unit,
    onConnectWsToken: (baseUrl: String, token: String) -> Unit,
    onBack: () -> Unit,
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val moodleBaseUrl = remember { mutableStateOf(session.baseUrl.ifBlank { "https://moodle.1skola.lv" }) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Подключение Moodle") },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("back_button")) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Назад")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Lock,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "Пароль никогда не сохраняется. Используется защищённая Keystore-сессия.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    StatusIndicatorPill(
                        label = if (session.isConnected) "Активно" else "Не подключено",
                        isPositive = session.isConnected
                    )
                }
            }

            if (syncStatus is SyncStatusUi.Syncing) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(
                    text = syncStatus.message,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            } else if (syncStatus is SyncStatusUi.Error) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = syncStatus.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            } else if (syncStatus is SyncStatusUi.Success) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                Icons.Rounded.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = syncStatus.message,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = onBack,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.testTag("moodle_connect_done_button")
                        ) {
                            Text("К курсам")
                        }
                    }
                }
            }

            TabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("Вход через браузер") },
                    icon = { Icon(Icons.Rounded.Language, contentDescription = null) },
                    modifier = Modifier.testTag("tab_browser_login")
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("Web Services API") },
                    icon = { Icon(Icons.Rounded.Key, contentDescription = null) },
                    modifier = Modifier.testTag("tab_ws_api")
                )
            }

            if (selectedTab == 0) {
                MoodleBrowserLoginTab(
                    baseUrl = moodleBaseUrl.value,
                    isSyncing = syncStatus is SyncStatusUi.Syncing,
                    onSessionCaptured = { cookie, sesskey, name ->
                        onConnectBrowserSession(moodleBaseUrl.value, cookie, sesskey, name)
                    }
                )
            } else {
                MoodleWebServiceTab(
                    baseUrl = moodleBaseUrl.value,
                    onBaseUrlChange = { moodleBaseUrl.value = it },
                    isSyncing = syncStatus is SyncStatusUi.Syncing,
                    onSubmitCredentials = { u, p ->
                        onConnectWsCredentials(moodleBaseUrl.value, u, p)
                    },
                    onSubmitToken = { token ->
                        onConnectWsToken(moodleBaseUrl.value, token)
                    }
                )
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun MoodleBrowserLoginTab(
    baseUrl: String,
    isSyncing: Boolean,
    onSessionCaptured: (cookie: String, sesskey: String, name: String) -> Unit,
) {
    var currentUrl by remember { mutableStateOf("$baseUrl/my/courses.php") }
    var pageLoading by remember { mutableStateOf(true) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var autoCaptured by remember { mutableStateOf(false) }

    fun inspectAndCaptureSession(view: WebView?) {
        val cm = CookieManager.getInstance()
        cm.flush()
        val cookie = cm.getCookie(baseUrl).orEmpty()
        if (!cookie.contains("MoodleSession")) {
            onSessionCaptured("", "", "")
            return
        }
        view?.evaluateJavascript(
            """
            (function() {
              var sk = (window.M && M.cfg && M.cfg.sesskey) ? M.cfg.sesskey : '';
              var userEl = document.querySelector('.usertext, .usermenu .logininfo, [data-userid]');
              var uname = userEl ? (userEl.innerText || '').trim() : '';
              return JSON.stringify({sesskey: sk, name: uname});
            })();
            """.trimIndent()
        ) { raw ->
            var sesskey = ""
            var name = ""
            runCatching {
                val parsed = org.json.JSONTokener(raw.orEmpty()).nextValue()
                val jsonStr = parsed as? String ?: raw.orEmpty()
                val obj = org.json.JSONObject(jsonStr)
                sesskey = obj.optString("sesskey", "")
                name = obj.optString("name", "")
            }
            onSessionCaptured(cookie, sesskey, name)
        } ?: onSessionCaptured(cookie, "", "")
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = currentUrl,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.weight(1f)
            )
            IconButton(
                onClick = { webViewRef?.reload() },
                modifier = Modifier.testTag("webview_reload_button")
            ) {
                Icon(Icons.Rounded.Refresh, contentDescription = "Обновить страницу")
            }
            Button(
                onClick = { inspectAndCaptureSession(webViewRef) },
                enabled = !isSyncing,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.testTag("capture_moodle_session_button")
            ) {
                Text("Сохранить сессию")
            }
        }

        if (pageLoading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(
                factory = { ctx ->
                    val cm = CookieManager.getInstance()
                    cm.setAcceptCookie(true)
                    WebView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        cm.setAcceptThirdPartyCookies(this, true)
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.loadsImagesAutomatically = true
                        settings.javaScriptCanOpenWindowsAutomatically = true
                        webChromeClient = WebChromeClient()
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                pageLoading = false
                                val loadedUrl = url.orEmpty()
                                currentUrl = loadedUrl
                                // Automatically verify & capture once redirected away from /login/ onto /my/
                                val isLoggedInPage = loadedUrl.startsWith(baseUrl) &&
                                    (loadedUrl.contains("/my/") || loadedUrl.contains("/course/")) &&
                                    !loadedUrl.contains("/login/")
                                if (isLoggedInPage && !autoCaptured) {
                                    autoCaptured = true
                                    inspectAndCaptureSession(view)
                                }
                            }
                        }
                        loadUrl("$baseUrl/my/courses.php")
                        webViewRef = this
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@Composable
private fun MoodleWebServiceTab(
    baseUrl: String,
    onBaseUrlChange: (String) -> Unit,
    isSyncing: Boolean,
    onSubmitCredentials: (username: String, password: String) -> Unit,
    onSubmitToken: (token: String) -> Unit,
) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var manualToken by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Получение токена Moodle Web Services",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Если на moodle.1skola.lv включены мобильные Web Services, введите логин и пароль для разового обмена на токен (пароль не сохраняется). Если API отключён администратором школы — используйте вкладку «Вход через браузер».",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = onBaseUrlChange,
                    label = { Text("Адрес Moodle") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("Логин Moodle") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("ws_username_input")
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Пароль (не сохраняется)") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("ws_password_input")
                )
                Button(
                    onClick = { onSubmitCredentials(username, password) },
                    enabled = !isSyncing,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("ws_login_button")
                ) {
                    if (isSyncing) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Text("Получить токен и подключиться")
                    }
                }
            }
        }

        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Или введите готовый токен безопасности",
                    style = MaterialTheme.typography.titleMedium
                )
                OutlinedTextField(
                    value = manualToken,
                    onValueChange = { manualToken = it },
                    label = { Text("Moodle wstoken") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("ws_manual_token_input")
                )
                OutlinedButton(
                    onClick = { onSubmitToken(manualToken) },
                    enabled = !isSyncing && manualToken.isNotBlank(),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("ws_manual_token_button")
                ) {
                    Text("Проверить и подключить токен")
                }
            }
        }
    }
}
