package com.example.lumo

import android.annotation.SuppressLint
import android.app.PictureInPictureParams
import android.content.res.Configuration
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Message
import android.util.Rational
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.example.lumo.ui.theme.LumoTheme

private const val PREFS_NAME = "lumo_preferences"
private const val PREF_SERVER_URL = "server_url"
private const val DEFAULT_URL = "https://arr-jellyfin.dace-hadar.ts.net/web/cinematic/index.html#home"

class MainActivity : ComponentActivity() {
    internal lateinit var mediaController: LumoMediaController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mediaController = LumoMediaController(this)
        enableEdgeToEdge()
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        hideSystemBars()

        val preferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        setContent {
            LumoTheme(dynamicColor = false, darkTheme = true) {
                var serverUrl by remember {
                    mutableStateOf(preferences.getString(PREF_SERVER_URL, null))
                }

                if (serverUrl == null) {
                    SetupScreen { url ->
                        preferences.edit().putString(PREF_SERVER_URL, url).apply()
                        serverUrl = url
                    }
                } else {
                    LumoWebView(
                        initialUrl = serverUrl!!,
                        onChangeAddress = {
                            preferences.edit().remove(PREF_SERVER_URL).apply()
                            serverUrl = null
                        }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !isInPictureInPictureMode) hideSystemBars()
    }

    override fun onUserLeaveHint() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && mediaController.isPlaying) {
            val width = mediaController.videoWidth.coerceAtLeast(1)
            val height = mediaController.videoHeight.coerceAtLeast(1)
            val ratio = width.toFloat() / height.toFloat()
            val aspectRatio = when {
                ratio > 2.39f -> Rational(239, 100)
                ratio < 0.418f -> Rational(100, 239)
                else -> Rational(width, height)
            }
            enterPictureInPictureMode(
                PictureInPictureParams.Builder()
                    .setAspectRatio(aspectRatio)
                    .build()
            )
        }
        super.onUserLeaveHint()
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (!isInPictureInPictureMode) hideSystemBars()
    }

    override fun onStop() {
        super.onStop()
        if (!isInPictureInPictureMode && !isChangingConfigurations && mediaController.hasMedia) {
            mediaController.pause()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        mediaController.refreshNotificationAfterPermission()
        hideSystemBars()
    }

    override fun onDestroy() {
        mediaController.release()
        super.onDestroy()
    }

    internal fun hideSystemBars() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
            systemBarsBehavior =
                androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }
}

@Composable
private fun SetupScreen(onSave: (String) -> Unit) {
    var address by remember { mutableStateOf(DEFAULT_URL) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    fun validateAndSave() {
        val candidate = address.trim().let {
            if (it.startsWith("http://") || it.startsWith("https://")) it else "https://$it"
        }
        val uri = runCatching { Uri.parse(candidate) }.getOrNull()
        if (uri?.scheme in listOf("http", "https") && !uri?.host.isNullOrBlank()) {
            onSave(candidate)
        } else {
            errorMessage = "Saisissez une adresse web valide."
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ComposeColor(0xFF080808))
            .padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Image(
            bitmap = androidx.compose.ui.graphics.ImageBitmap.imageResource(R.drawable.lumo_logo),
            contentDescription = "Logo LUMO",
            modifier = Modifier.size(150.dp)
        )
        Spacer(Modifier.height(20.dp))
        Text(
            text = "LUMO",
            color = ComposeColor(0xFFFFC94A),
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = "Adresse de votre interface",
            color = ComposeColor.White,
            style = MaterialTheme.typography.titleMedium
        )
        Text(
            text = "Elle sera mémorisée sur cet appareil.",
            color = ComposeColor(0xFFAAAAAA),
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(
            value = address,
            onValueChange = {
                address = it
                errorMessage = null
            },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("URL LUMO") },
            singleLine = true,
            isError = errorMessage != null,
            supportingText = errorMessage?.let { message -> { Text(message) } },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { validateAndSave() })
        )
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = { validateAndSave() },
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = ComposeColor(0xFFFFC94A),
                contentColor = ComposeColor(0xFF1B1300)
            )
        ) {
            Text("Ouvrir LUMO", fontWeight = FontWeight.Bold)
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun LumoWebView(initialUrl: String, onChangeAddress: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val activity = context as MainActivity
    var webView by remember { mutableStateOf<WebView?>(null) }
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }

    BackHandler {
        if (webView?.canGoBack() == true) webView?.goBack() else activity.finish()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ComposeColor.Black)
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { viewContext ->
                WebView(viewContext).apply webViewScope@{
                    setBackgroundColor(Color.BLACK)
                    settings.apply {
                        javaScriptEnabled = true
                        javaScriptCanOpenWindowsAutomatically = true
                        setSupportMultipleWindows(true)
                        domStorageEnabled = true
                        databaseEnabled = true
                        mediaPlaybackRequiresUserGesture = false
                        loadsImagesAutomatically = true
                        allowFileAccess = false
                        allowContentAccess = false
                        mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            safeBrowsingEnabled = true
                        }
                        userAgentString = "$userAgentString LUMO-Android/1.2"
                    }

                    CookieManager.getInstance().apply {
                        setAcceptCookie(true)
                        setAcceptThirdPartyCookies(this@webViewScope, true)
                    }

                    activity.mediaController.attach(this)

                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                            loading = true
                            loadError = null
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            loading = false
                            CookieManager.getInstance().flush()
                            activity.mediaController.injectBridge(view)
                        }

                        override fun onReceivedError(
                            view: WebView?,
                            request: WebResourceRequest?,
                            error: WebResourceError?
                        ) {
                            if (request?.isForMainFrame == true) {
                                loading = false
                                loadError = error?.description?.toString() ?: "Connexion impossible"
                            }
                        }

                        override fun onReceivedSslError(
                            view: WebView?,
                            handler: SslErrorHandler?,
                            error: SslError?
                        ) {
                            handler?.cancel()
                            loading = false
                            loadError = "Le certificat de sécurité du serveur n’est pas valide."
                        }

                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): Boolean {
                            val target = request?.url ?: return false
                            if (target.scheme == "http" || target.scheme == "https") return false
                            return runCatching {
                                viewContext.startActivity(Intent(Intent.ACTION_VIEW, target))
                                true
                            }.getOrDefault(true)
                        }
                    }
                    webChromeClient = LumoChromeClient(activity, this)
                    loadUrl(initialUrl)
                    webView = this
                }
            }
        )

        if (loading && loadError == null) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
                color = ComposeColor(0xFFFFC94A)
            )
        }

        loadError?.let { message ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(ComposeColor(0xF2080808))
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text("Impossible d’ouvrir LUMO", color = ComposeColor.White, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text(message, color = ComposeColor(0xFFBBBBBB))
                Spacer(Modifier.height(24.dp))
                Button(onClick = { webView?.reload() }) {
                    Text("Réessayer")
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onChangeAddress) {
                    Text("Changer l’adresse")
                }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            webView?.apply {
                activity.mediaController.detach(this)
                stopLoading()
                clearHistory()
                removeAllViews()
                destroy()
            }
        }
    }
}

private class LumoChromeClient(
    private val activity: MainActivity,
    private val hostWebView: WebView
) : WebChromeClient() {
    private var customView: View? = null
    private var customViewCallback: CustomViewCallback? = null

    override fun onPermissionRequest(request: PermissionRequest?) {
        val protectedMedia = request?.resources
            ?.filter { it == PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID }
            ?.toTypedArray()
            .orEmpty()
        activity.runOnUiThread {
            if (protectedMedia.isNotEmpty()) request?.grant(protectedMedia) else request?.deny()
        }
    }

    override fun onCreateWindow(
        view: WebView?,
        isDialog: Boolean,
        isUserGesture: Boolean,
        resultMsg: Message?
    ): Boolean {
        val message = resultMsg ?: return false
        val popup = WebView(activity)
        popup.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                request?.url?.toString()?.let(hostWebView::loadUrl)
                view?.destroy()
                return true
            }
        }
        val transport = message.obj as? WebView.WebViewTransport ?: return false
        transport.webView = popup
        message.sendToTarget()
        return true
    }

    override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
        if (customView != null || view == null) {
            callback?.onCustomViewHidden()
            return
        }
        customView = view
        customViewCallback = callback
        val decor = activity.window.decorView as FrameLayout
        decor.addView(
            view,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        WindowCompat.getInsetsController(activity.window, decor)
            .hide(WindowInsetsCompat.Type.systemBars())
    }

    override fun onHideCustomView() {
        val view = customView ?: return
        val decor = activity.window.decorView as FrameLayout
        decor.removeView(view)
        customView = null
        customViewCallback?.onCustomViewHidden()
        customViewCallback = null
        activity.hideSystemBars()
    }
}
