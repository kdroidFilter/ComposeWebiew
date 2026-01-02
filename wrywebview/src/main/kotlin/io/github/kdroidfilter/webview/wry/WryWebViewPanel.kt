package io.github.kdroidfilter.webview.wry

import com.sun.jna.Native
import java.awt.BorderLayout
import java.awt.Component
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.Timer
import kotlin.concurrent.thread


class WryWebViewPanel(
    initialUrl: String,
    customUserAgent: String? = null,
) : JPanel() {
    private val host = SkikoInterop.createHost()
    private var webviewId: ULong? = null
    private var parentHandle: ULong = 0UL
    private var parentIsWindow: Boolean = false
    private var pendingUrl: String = initialUrl
    private val customUserAgent: String? = customUserAgent?.trim()?.takeIf { it.isNotEmpty() }
    private var pendingUrlWithHeaders: String? = null
    private var pendingHeaders: Map<String, String> = emptyMap()
    private var pendingHtml: String? = null
    private var createTimer: Timer? = null
    private var destroyTimer: Timer? = null
    private var createInFlight: Boolean = false
    private var gtkTimer: Timer? = null
    private var windowsTimer: Timer? = null
    private var skikoInitialized: Boolean = false
    private var lastBounds: Bounds? = null
    private var pendingBounds: Bounds? = null
    private var boundsTimer: Timer? = null

    init {
        layout = BorderLayout()
        add(host, BorderLayout.CENTER)
        // Request focus when clicked to capture keyboard events
        host.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent?) {
                requestWebViewFocus()
            }
        })
        log("init url=$initialUrl")
    }

    override fun addNotify() {
        super.addNotify()
        stopDestroyTimer()
        log("addNotify displayable=${host.isDisplayable} showing=${host.isShowing} size=${host.width}x${host.height}")
        SwingUtilities.invokeLater { scheduleCreateIfNeeded() }
    }

    override fun removeNotify() {
        log("removeNotify")
        stopCreateTimer()
        if (IS_MAC) {
            scheduleDestroyIfNeeded()
        } else {
            destroyIfNeeded()
        }
        super.removeNotify()
    }

    override fun doLayout() {
        super.doLayout()
        log("doLayout size=${host.width}x${host.height} displayable=${host.isDisplayable} showing=${host.isShowing}")
        updateBounds()
        scheduleCreateIfNeeded()
    }

    fun loadUrl(url: String) {
        loadUrl(url, emptyMap())
    }

    fun loadUrl(url: String, additionalHttpHeaders: Map<String, String>) {
        pendingUrl = url
        pendingHtml = null
        pendingHeaders = additionalHttpHeaders
        pendingUrlWithHeaders = if (additionalHttpHeaders.isNotEmpty()) url else null
        if (pendingUrlWithHeaders != null) {
            pendingUrl = "about:blank"
        }
        if (SwingUtilities.isEventDispatchThread()) {
            webviewId?.let {
                if (additionalHttpHeaders.isNotEmpty()) {
                    NativeBindings.loadUrlWithHeaders(it, url, additionalHttpHeaders)
                } else {
                    NativeBindings.loadUrl(it, url)
                }
            }
                ?: scheduleCreateIfNeeded()
        } else {
            SwingUtilities.invokeLater {
                webviewId?.let {
                    if (additionalHttpHeaders.isNotEmpty()) {
                        NativeBindings.loadUrlWithHeaders(it, url, additionalHttpHeaders)
                    } else {
                        NativeBindings.loadUrl(it, url)
                    }
                } ?: scheduleCreateIfNeeded()
            }
        }
        log("loadUrl url=$url headers=${additionalHttpHeaders.size} webviewId=$webviewId")
    }

    fun loadHtml(html: String) {
        pendingHtml = html
        pendingUrl = "about:blank"
        pendingHeaders = emptyMap()
        pendingUrlWithHeaders = null
        val action = {
            webviewId?.let { NativeBindings.loadHtml(it, html) } ?: scheduleCreateIfNeeded()
        }
        if (SwingUtilities.isEventDispatchThread()) {
            action()
        } else {
            SwingUtilities.invokeLater { action() }
        }
        log("loadHtml bytes=${html.length} webviewId=$webviewId")
    }

    fun goBack() {
        val action = { webviewId?.let { NativeBindings.goBack(it) } }
        if (SwingUtilities.isEventDispatchThread()) {
            action()
        } else {
            SwingUtilities.invokeLater { action() }
        }
        log("goBack webviewId=$webviewId")
    }

    fun goForward() {
        val action = { webviewId?.let { NativeBindings.goForward(it) } }
        if (SwingUtilities.isEventDispatchThread()) {
            action()
        } else {
            SwingUtilities.invokeLater { action() }
        }
        log("goForward webviewId=$webviewId")
    }

    fun reload() {
        val action = { webviewId?.let { NativeBindings.reload(it) } }
        if (SwingUtilities.isEventDispatchThread()) {
            action()
        } else {
            SwingUtilities.invokeLater { action() }
        }
        log("reload webviewId=$webviewId")
    }

    fun stopLoading() {
        val action = { webviewId?.let { NativeBindings.stopLoading(it) } }
        if (SwingUtilities.isEventDispatchThread()) {
            action()
        } else {
            SwingUtilities.invokeLater { action() }
        }
        log("stopLoading webviewId=$webviewId")
    }

    fun evaluateJavaScript(script: String) {
        val action = { webviewId?.let { NativeBindings.evaluateJavaScript(it, script) } }
        if (SwingUtilities.isEventDispatchThread()) {
            action()
        } else {
            SwingUtilities.invokeLater { action() }
        }
        log("evaluateJavaScript bytes=${script.length} webviewId=$webviewId")
    }

    fun getCurrentUrl(): String? {
        return webviewId?.let {
            try {
                NativeBindings.getUrl(it)
            } catch (e: Exception) {
                log("getCurrentUrl failed: ${e.message}")
                null
            }
        }
    }

    fun isLoading(): Boolean {
        return webviewId?.let {
            try {
                NativeBindings.isLoading(it)
            } catch (e: Exception) {
                log("isLoading failed: ${e.message}")
                true
            }
        } ?: true
    }

    fun getTitle(): String? {
        return webviewId?.let {
            try {
                NativeBindings.getTitle(it)
            } catch (e: Exception) {
                log("getTitle failed: ${e.message}")
                null
            }
        }
    }

    fun canGoBack(): Boolean {
        return webviewId?.let {
            try {
                NativeBindings.canGoBack(it)
            } catch (e: Exception) {
                log("canGoBack failed: ${e.message}")
                false
            }
        } ?: false
    }

    fun canGoForward(): Boolean {
        return webviewId?.let {
            try {
                NativeBindings.canGoForward(it)
            } catch (e: Exception) {
                log("canGoForward failed: ${e.message}")
                false
            }
        } ?: false
    }

    fun drainIpcMessages(): List<String> {
        return webviewId?.let {
            try {
                NativeBindings.drainIpcMessages(it)
            } catch (e: Exception) {
                log("drainIpcMessages failed: ${e.message}")
                emptyList()
            }
        } ?: emptyList()
    }

    fun getCookiesForUrl(url: String): List<WebViewCookie> {
        return webviewId?.let {
            try {
                NativeBindings.getCookiesForUrl(it, url)
            } catch (e: Exception) {
                log("getCookiesForUrl failed: ${e.message}")
                emptyList()
            }
        } ?: emptyList()
    }

    fun clearCookiesForUrl(url: String) {
        val action = { webviewId?.let { NativeBindings.clearCookiesForUrl(it, url) } }
        if (SwingUtilities.isEventDispatchThread()) {
            action()
        } else {
            SwingUtilities.invokeLater { action() }
        }
    }

    fun clearAllCookies() {
        val action = { webviewId?.let { NativeBindings.clearAllCookies(it) } }
        if (SwingUtilities.isEventDispatchThread()) {
            action()
        } else {
            SwingUtilities.invokeLater { action() }
        }
    }

    fun setCookie(cookie: WebViewCookie) {
        val action = { webviewId?.let { NativeBindings.setCookie(it, cookie) } }
        if (SwingUtilities.isEventDispatchThread()) {
            action()
        } else {
            SwingUtilities.invokeLater { action() }
        }
    }

    fun isReady(): Boolean = webviewId != null

    fun requestWebViewFocus() {
        val action = { webviewId?.let { NativeBindings.focus(it) } }
        if (SwingUtilities.isEventDispatchThread()) {
            action()
        } else {
            SwingUtilities.invokeLater { action() }
        }
        log("requestWebViewFocus webviewId=$webviewId")
    }

    private fun createIfNeeded(): Boolean {
        if (webviewId != null) return true
        if (createInFlight) return false
        if (!host.isDisplayable || !host.isShowing) return false
        if (host.width <= 0 || host.height <= 0) return false
        // On Windows, wait for the window to be fully visible
        if (IS_WINDOWS) {
            val window = SwingUtilities.getWindowAncestor(host)
            if (window == null || !window.isShowing) return false
        }
        if (!skikoInitialized) {
            skikoInitialized = try {
                val initResult = SkikoInterop.init(host)
                log("skiko init result=$initResult")
                initResult
            } catch (e: RuntimeException) {
                log("skiko init failed: ${e.message}")
                false
            }
        }
        val resolved = resolveParentHandle() ?: run {
            log("createIfNeeded no parent handle; host displayable=${host.isDisplayable} showing=${host.isShowing} size=${host.width}x${host.height}")
            return false
        }
        parentHandle = resolved.handle
        parentIsWindow = resolved.isWindow
        log("createIfNeeded handle=$parentHandle parentIsWindow=$parentIsWindow size=${host.width}x${host.height}")
        val width = host.width.coerceAtLeast(1)
        val height = host.height.coerceAtLeast(1)
        val userAgent = customUserAgent
        val initialUrl = pendingUrl
        val handleSnapshot = parentHandle
        if (!IS_MAC) {
            return try {
                webviewId =
                    if (userAgent == null) {
                        NativeBindings.createWebview(handleSnapshot, width, height, initialUrl)
                    } else {
                        NativeBindings.createWebviewWithUserAgent(handleSnapshot, width, height, initialUrl, userAgent)
                    }
                updateBounds()
                startGtkPumpIfNeeded()
                startWindowsPumpIfNeeded()
                // Apply any pending content that requires an explicit call after creation.
                val id = webviewId
                val html = pendingHtml
                val urlWithHeaders = pendingUrlWithHeaders
                val headers = pendingHeaders
                if (id != null) {
                    when {
                        html != null -> {
                            pendingHtml = null
                            NativeBindings.loadHtml(id, html)
                        }
                        urlWithHeaders != null && headers.isNotEmpty() -> {
                            pendingUrlWithHeaders = null
                            pendingHeaders = emptyMap()
                            NativeBindings.loadUrlWithHeaders(id, urlWithHeaders, headers)
                        }
                    }
                }
                log("createIfNeeded success id=$webviewId")
                true
            } catch (e: RuntimeException) {
                System.err.println("Failed to create Wry webview: ${e.message}")
                e.printStackTrace()
                true
            }
        }
        createInFlight = true
        stopCreateTimer()
        thread(name = "wry-webview-create", isDaemon = true) {
            val createdId = try {
                if (userAgent == null) {
                    NativeBindings.createWebview(handleSnapshot, width, height, initialUrl)
                } else {
                    NativeBindings.createWebviewWithUserAgent(handleSnapshot, width, height, initialUrl, userAgent)
                }
            } catch (e: RuntimeException) {
                System.err.println("Failed to create Wry webview: ${e.message}")
                e.printStackTrace()
                null
            }
            SwingUtilities.invokeLater {
                createInFlight = false
                if (createdId == null) {
                    scheduleCreateIfNeeded()
                    return@invokeLater
                }
                if (webviewId != null) {
                    NativeBindings.destroyWebview(createdId)
                    return@invokeLater
                }
                if (!host.isDisplayable || !host.isShowing) {
                    NativeBindings.destroyWebview(createdId)
                    return@invokeLater
                }
                webviewId = createdId
                updateBounds()
                startGtkPumpIfNeeded()
                startWindowsPumpIfNeeded()
                // Apply any pending content that requires an explicit call after creation.
                val html = pendingHtml
                val urlWithHeaders = pendingUrlWithHeaders
                val headers = pendingHeaders
                when {
                    html != null -> {
                        pendingHtml = null
                        NativeBindings.loadHtml(createdId, html)
                    }
                    urlWithHeaders != null && headers.isNotEmpty() -> {
                        pendingUrlWithHeaders = null
                        pendingHeaders = emptyMap()
                        NativeBindings.loadUrlWithHeaders(createdId, urlWithHeaders, headers)
                    }
                    pendingUrl != initialUrl -> {
                        NativeBindings.loadUrl(createdId, pendingUrl)
                    }
                }
                log("createIfNeeded success id=$webviewId")
            }
        }
        return true
    }

    private fun destroyIfNeeded() {
        stopDestroyTimer()
        stopGtkPump()
        stopWindowsPump()
        stopBoundsTimer()
        webviewId?.let {
            log("destroy id=$it")
            NativeBindings.destroyWebview(it)
        }
        webviewId = null
        parentHandle = 0UL
        parentIsWindow = false
        lastBounds = null
    }

    private fun updateBounds() {
        val id = webviewId ?: return
        val bounds = boundsInParent()
        if (IS_LINUX || IS_MAC) {
            pendingBounds = bounds
            if (boundsTimer == null) {
                boundsTimer = Timer(16) {
                    val currentId = webviewId ?: return@Timer
                    val toSend = pendingBounds ?: return@Timer
                    pendingBounds = null
                    if (toSend != lastBounds) {
                        lastBounds = toSend
                        log("setBounds id=$currentId pos=(${toSend.x}, ${toSend.y}) size=${toSend.width}x${toSend.height}")
                        NativeBindings.setBounds(currentId, toSend.x, toSend.y, toSend.width, toSend.height)
                    }
                    if (pendingBounds == null) {
                        stopBoundsTimer()
                    }
                }.apply { start() }
            }
            return
        }
        if (bounds == lastBounds) return
        lastBounds = bounds
        log("setBounds id=$id pos=(${bounds.x}, ${bounds.y}) size=${bounds.width}x${bounds.height}")
        NativeBindings.setBounds(id, bounds.x, bounds.y, bounds.width, bounds.height)
    }

    private fun startGtkPumpIfNeeded() {
        if (!IS_LINUX || gtkTimer != null) return
        log("startGtkPump (noop, handled in native GTK thread)")
    }

    private fun stopGtkPump() {
        gtkTimer?.stop()
        gtkTimer = null
    }

    private fun startWindowsPumpIfNeeded() {
        if (!IS_WINDOWS || windowsTimer != null) return
        log("startWindowsPump")
        windowsTimer = Timer(16) { NativeBindings.pumpWindowsEvents() }.apply { start() }
    }

    private fun stopWindowsPump() {
        windowsTimer?.stop()
        windowsTimer = null
    }

    private fun scheduleCreateIfNeeded() {
        if (webviewId != null || createTimer != null || createInFlight) return
        log("scheduleCreateIfNeeded")
        val delay = if (IS_WINDOWS) 100 else 16
        createTimer = Timer(delay) {
            if (createIfNeeded()) {
                stopCreateTimer()
            }
        }.apply { start() }
    }

    private fun stopCreateTimer() {
        createTimer?.stop()
        createTimer = null
    }

    private fun scheduleDestroyIfNeeded() {
        if (destroyTimer != null) return
        if (webviewId == null && !createInFlight) return
        destroyTimer = Timer(400) {
            stopDestroyTimer()
            if (!host.isDisplayable || !host.isShowing) {
                destroyIfNeeded()
            }
        }.apply {
            isRepeats = false
            start()
        }
    }

    private fun stopDestroyTimer() {
        destroyTimer?.stop()
        destroyTimer = null
    }

    private fun stopBoundsTimer() {
        boundsTimer?.stop()
        boundsTimer = null
        pendingBounds = null
    }

    private fun componentHandle(component: Component): ULong {
        return try {
            Native.getComponentID(component).toULong()
        } catch (e: RuntimeException) {
            log("componentHandle failed for ${component.javaClass.name}: ${e.message}")
            0UL
        }
    }

    private fun log(message: String) {
        if (LOG_ENABLED) {
            System.err.println("[WryWebViewPanel] $message")
        }
    }

    private fun resolveParentHandle(): ParentHandle? {
        val contentHandle = safeSkikoHandle("content") { SkikoInterop.getContentHandle(host) }
        val windowHandle = safeSkikoHandle("window") { SkikoInterop.getWindowHandle(host) }
        if (IS_WINDOWS) {
            // On Windows, use the window handle and position webview manually
            // Canvas HWND doesn't work well as WebView2 parent
            val window = SwingUtilities.getWindowAncestor(host)
            if (window != null && window.isDisplayable && window.isShowing) {
                val windowHandleJna = componentHandle(window)
                if (windowHandleJna != 0UL) {
                    log("resolveParentHandle jna window=0x${windowHandleJna.toString(16)} (windows)")
                    return ParentHandle(windowHandleJna, true)
                }
            }
        } else if (IS_MAC) {
            if (contentHandle != 0L && contentHandle != windowHandle) {
                log("resolveParentHandle skiko content=0x${contentHandle.toString(16)} window=0x${windowHandle.toString(16)} (macOS content)")
                return ParentHandle(contentHandle.toULong(), false)
            }
            if (windowHandle != 0L) {
                log("resolveParentHandle skiko window=0x${windowHandle.toString(16)} (macOS)")
                return ParentHandle(windowHandle.toULong(), true)
            }
            if (contentHandle != 0L) {
                log("resolveParentHandle skiko content=0x${contentHandle.toString(16)} (macOS fallback)")
                return ParentHandle(contentHandle.toULong(), true)
            }
        } else {
            if (contentHandle != 0L) {
                log("resolveParentHandle skiko content=0x${contentHandle.toString(16)} window=0x${windowHandle.toString(16)}")
                return ParentHandle(contentHandle.toULong(), false)
            }
            if (windowHandle != 0L) {
                log("resolveParentHandle skiko content=0 window=0x${windowHandle.toString(16)} (using window)")
                return ParentHandle(windowHandle.toULong(), true)
            }
        }

        val hostHandle = componentHandle(host)
        if (hostHandle != 0UL) {
            log("resolveParentHandle jna host=0x${hostHandle.toString(16)}")
            return ParentHandle(hostHandle, false)
        }
        val window = SwingUtilities.getWindowAncestor(host) ?: return null
        if (!window.isDisplayable || !window.isShowing) return null
        val windowHandleFallback = componentHandle(window)
        if (windowHandleFallback != 0UL) {
            log("resolveParentHandle jna window=0x${windowHandleFallback.toString(16)}")
            return ParentHandle(windowHandleFallback, true)
        }
        log("resolveParentHandle no handles (content=0 window=0)")
        return null
    }

    private fun safeSkikoHandle(name: String, getter: () -> Long): Long {
        return try {
            getter()
        } catch (e: RuntimeException) {
            log("skiko $name handle failed: ${e.message}")
            0L
        }
    }

    private fun boundsInParent(): Bounds {
        val width = host.width.coerceAtLeast(1)
        val height = host.height.coerceAtLeast(1)
        if (!parentIsWindow) {
            return Bounds(0, 0, width, height)
        }
        val window = SwingUtilities.getWindowAncestor(host) ?: return Bounds(0, 0, width, height)
        val point = SwingUtilities.convertPoint(host, 0, 0, window)
        val insets = window.insets
        val x = point.x - insets.left
        val y = point.y - insets.top
        log("boundsInParent windowOffset=(${x}, ${y}) insets=${insets}")
        return Bounds(x, y, width, height)
    }

    private data class ParentHandle(val handle: ULong, val isWindow: Boolean)
    private data class Bounds(val x: Int, val y: Int, val width: Int, val height: Int)

    private companion object {
        private val OS_NAME = System.getProperty("os.name")?.lowercase().orEmpty()
        private val IS_LINUX = OS_NAME.contains("linux")
        private val IS_MAC = OS_NAME.contains("mac")
        private val IS_WINDOWS = OS_NAME.contains("windows")
        private val LOG_ENABLED = run {
            val raw = System.getProperty("composewebview.wry.log") ?: System.getenv("WRYWEBVIEW_LOG")
            when {
                raw == null -> false
                raw == "1" -> true
                raw.equals("true", ignoreCase = true) -> true
                raw.equals("yes", ignoreCase = true) -> true
                raw.equals("debug", ignoreCase = true) -> true
                else -> false
            }
        }
    }
}

private object NativeBindings {
    fun createWebview(parentHandle: ULong, width: Int, height: Int, url: String): ULong {
        return io.github.kdroidfilter.webview.wry.createWebview(parentHandle, width, height, url)
    }

    fun createWebviewWithUserAgent(
        parentHandle: ULong,
        width: Int,
        height: Int,
        url: String,
        userAgent: String,
    ): ULong {
        return io.github.kdroidfilter.webview.wry.createWebviewWithUserAgent(
            parentHandle,
            width,
            height,
            url,
            userAgent,
        )
    }

    fun setBounds(id: ULong, x: Int, y: Int, width: Int, height: Int) {
        io.github.kdroidfilter.webview.wry.setBounds(id, x, y, width, height)
    }

    fun loadUrl(id: ULong, url: String) {
        io.github.kdroidfilter.webview.wry.loadUrl(id, url)
    }

    fun loadUrlWithHeaders(id: ULong, url: String, additionalHttpHeaders: Map<String, String>) {
        io.github.kdroidfilter.webview.wry.loadUrlWithHeaders(
            id,
            url,
            additionalHttpHeaders.map { (name, value) -> HttpHeader(name, value) },
        )
    }

    fun loadHtml(id: ULong, html: String) {
        io.github.kdroidfilter.webview.wry.loadHtml(id, html)
    }

    fun goBack(id: ULong) {
        io.github.kdroidfilter.webview.wry.goBack(id)
    }

    fun goForward(id: ULong) {
        io.github.kdroidfilter.webview.wry.goForward(id)
    }

    fun reload(id: ULong) {
        io.github.kdroidfilter.webview.wry.reload(id)
    }

    fun stopLoading(id: ULong) {
        io.github.kdroidfilter.webview.wry.stopLoading(id)
    }

    fun evaluateJavaScript(id: ULong, script: String) {
        io.github.kdroidfilter.webview.wry.evaluateJavascript(id, script)
    }

    fun getUrl(id: ULong): String {
        return io.github.kdroidfilter.webview.wry.getUrl(id)
    }

    fun isLoading(id: ULong): Boolean {
        return io.github.kdroidfilter.webview.wry.isLoading(id)
    }

    fun getTitle(id: ULong): String {
        return io.github.kdroidfilter.webview.wry.getTitle(id)
    }

    fun canGoBack(id: ULong): Boolean {
        return io.github.kdroidfilter.webview.wry.canGoBack(id)
    }

    fun canGoForward(id: ULong): Boolean {
        return io.github.kdroidfilter.webview.wry.canGoForward(id)
    }

    fun drainIpcMessages(id: ULong): List<String> {
        return io.github.kdroidfilter.webview.wry.drainIpcMessages(id)
    }

    fun getCookiesForUrl(id: ULong, url: String): List<WebViewCookie> {
        return io.github.kdroidfilter.webview.wry.getCookiesForUrl(id, url)
    }

    fun clearCookiesForUrl(id: ULong, url: String) {
        io.github.kdroidfilter.webview.wry.clearCookiesForUrl(id, url)
    }

    fun clearAllCookies(id: ULong) {
        io.github.kdroidfilter.webview.wry.clearAllCookies(id)
    }

    fun setCookie(id: ULong, cookie: WebViewCookie) {
        io.github.kdroidfilter.webview.wry.setCookie(id, cookie)
    }

    fun destroyWebview(id: ULong) {
        io.github.kdroidfilter.webview.wry.destroyWebview(id)
    }

    fun pumpGtkEvents() {
        io.github.kdroidfilter.webview.wry.pumpGtkEvents()
    }

    fun pumpWindowsEvents() {
        io.github.kdroidfilter.webview.wry.pumpWindowsEvents()
    }

    fun focus(id: ULong) {
        io.github.kdroidfilter.webview.wry.focus(id)
    }
}
