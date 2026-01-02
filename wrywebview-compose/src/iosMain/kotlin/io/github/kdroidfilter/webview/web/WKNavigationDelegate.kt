package io.github.kdroidfilter.webview.web

import io.github.kdroidfilter.webview.request.WebRequest
import io.github.kdroidfilter.webview.request.WebRequestInterceptResult
import io.github.kdroidfilter.webview.util.KLogger
import kotlinx.cinterop.ObjCSignatureOverride
import platform.Foundation.HTTPMethod
import platform.Foundation.NSError
import platform.Foundation.allHTTPHeaderFields
import platform.WebKit.WKNavigation
import platform.WebKit.WKNavigationAction
import platform.WebKit.WKNavigationActionPolicy
import platform.WebKit.WKNavigationDelegateProtocol
import platform.WebKit.WKWebView
import platform.darwin.NSObject

@Suppress("CONFLICTING_OVERLOADS")
internal class WKNavigationDelegate(
    private val state: WebViewState,
    private val navigator: WebViewNavigator,
) : NSObject(),
    WKNavigationDelegateProtocol {
    private var isRedirect = false

    @ObjCSignatureOverride
    override fun webView(
        webView: WKWebView,
        didStartProvisionalNavigation: WKNavigation?,
    ) {
        state.loadingState = LoadingState.Loading(0f)
        state.lastLoadedUrl = webView.URL?.absoluteString
        state.errorsForCurrentRequest.clear()
        KLogger.i(tag = "WKNavigationDelegate") { "didStartProvisionalNavigation" }
    }

    @ObjCSignatureOverride
    override fun webView(
        webView: WKWebView,
        didCommitNavigation: WKNavigation?,
    ) {
        val supportZoom = if (state.webSettings.supportZoom) "yes" else "no"
        val script =
            "var meta = document.createElement('meta');meta.setAttribute('name', 'viewport');meta.setAttribute('content', 'width=device-width, initial-scale=${state.webSettings.zoomLevel}, maximum-scale=10.0, minimum-scale=0.1,user-scalable=$supportZoom');document.getElementsByTagName('head')[0].appendChild(meta);"
        webView.evaluateJavaScript(script, completionHandler = null)
        KLogger.i(tag = "WKNavigationDelegate") { "didCommitNavigation" }
    }

    @ObjCSignatureOverride
    override fun webView(
        webView: WKWebView,
        didFinishNavigation: WKNavigation?,
    ) {
        state.pageTitle = webView.title
        state.lastLoadedUrl = webView.URL?.absoluteString
        state.loadingState = LoadingState.Finished
        navigator.canGoBack = webView.canGoBack
        navigator.canGoForward = webView.canGoForward
        KLogger.i(tag = "WKNavigationDelegate") { "didFinishNavigation ${state.lastLoadedUrl}" }
    }

    override fun webView(
        webView: WKWebView,
        didFailProvisionalNavigation: WKNavigation?,
        withError: NSError,
    ) {
        KLogger.e(tag = "WKNavigationDelegate") {
            "WebView Loading Failed with error: ${withError.localizedDescription}"
        }
        state.errorsForCurrentRequest.add(
            WebViewError(
                code = withError.code.toInt(),
                description = withError.localizedDescription,
                isFromMainFrame = true,
            ),
        )
    }

    override fun webView(
        webView: WKWebView,
        decidePolicyForNavigationAction: WKNavigationAction,
        decisionHandler: (WKNavigationActionPolicy) -> Unit,
    ) {
        val url = decidePolicyForNavigationAction.request.URL?.absoluteString

        if (
            url != null &&
            !isRedirect &&
            navigator.requestInterceptor != null &&
            decidePolicyForNavigationAction.targetFrame?.mainFrame != false
        ) {
            val request = decidePolicyForNavigationAction.request
            val headerMap = mutableMapOf<String, String>()
            request.allHTTPHeaderFields?.forEach {
                headerMap[it.key.toString()] = it.value.toString()
            }

            val webRequest =
                WebRequest(
                    url = request.URL?.absoluteString.orEmpty(),
                    headers = headerMap,
                    isForMainFrame = decidePolicyForNavigationAction.targetFrame?.mainFrame ?: false,
                    isRedirect = isRedirect,
                    method = request.HTTPMethod ?: "GET",
                )

            when (val interceptResult = navigator.requestInterceptor.onInterceptUrlRequest(webRequest, navigator)) {
                WebRequestInterceptResult.Allow ->
                    decisionHandler(WKNavigationActionPolicy.WKNavigationActionPolicyAllow)

                WebRequestInterceptResult.Reject ->
                    decisionHandler(WKNavigationActionPolicy.WKNavigationActionPolicyCancel)

                is WebRequestInterceptResult.Modify -> {
                    isRedirect = true
                    interceptResult.request.let { modified ->
                        navigator.stopLoading()
                        navigator.loadUrl(modified.url, modified.headers)
                    }
                    decisionHandler(WKNavigationActionPolicy.WKNavigationActionPolicyCancel)
                }
            }
        } else {
            isRedirect = false
            decisionHandler(WKNavigationActionPolicy.WKNavigationActionPolicyAllow)
        }
    }
}
