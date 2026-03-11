package com.github.copilotsilent.ui.webview

import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.Disposer
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefJSQuery
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.callback.CefCallback
import org.cef.handler.CefLoadHandlerAdapter
import org.cef.handler.CefRequestHandlerAdapter
import org.cef.handler.CefResourceHandler
import org.cef.handler.CefResourceRequestHandler
import org.cef.handler.CefResourceRequestHandlerAdapter
import org.cef.misc.BoolRef
import org.cef.misc.IntRef
import org.cef.misc.StringRef
import org.cef.network.CefRequest
import org.cef.network.CefResponse
import java.io.InputStream
import java.util.Base64
import javax.swing.JComponent

/**
 * JCEF browser panel that serves a React app from plugin resources via a custom
 * resource handler (fake origin: copilot-webview).
 *
 * Provides a bidirectional bridge:
 * - JS → Kotlin: window.__bridge.postMessage(json) via JBCefJSQuery
 * - Kotlin → JS: pushData(channel, json) via executeJavaScript + CustomEvent
 *
 * Based on the pattern from agent-codebase-tools JcefBrowserPanel.
 */
class JcefBrowserPanel(
    parentDisposable: Disposable,
    private val deferLoad: Boolean = false,
) : Disposable {

    private val log = Logger.getInstance(JcefBrowserPanel::class.java)
    private val browser: JBCefBrowser
    private val jsQuery: JBCefJSQuery
    private val loaded = java.util.concurrent.atomic.AtomicBoolean(false)

    /** External handler for messages sent from the webview via postMessage(). */
    var messageHandler: ((String) -> Unit)? = null

    /** Called once after the JS bridge is injected and ready. */
    var onBridgeReady: (() -> Unit)? = null

    private val startUrl: String

    init {
        val devMode = System.getProperty("copilotsilent.webview.dev")?.toBoolean() == true
        startUrl = if (devMode) DEV_URL else RESOURCE_URL

        // Always create browser WITHOUT a URL. The caller triggers loading
        // by calling load() after setting onBridgeReady, or via HierarchyListener
        // for deferred (file editor) mode. This avoids the race where onLoadEnd
        // fires before onBridgeReady is assigned.
        browser = JBCefBrowser.createBuilder().build()
        log.info("Creating webview, deferLoad=$deferLoad, startUrl=$startUrl")

        Disposer.register(parentDisposable, this)

        if (!devMode) {
            registerResourceHandler()
        }

        @Suppress("DEPRECATION")
        jsQuery = JBCefJSQuery.create(browser as JBCefBrowserBase)
        jsQuery.addHandler { message ->
            handleJsMessage(message)
            null
        }

        browser.jbCefClient.addLoadHandler(object : CefLoadHandlerAdapter() {
            override fun onLoadEnd(cefBrowser: CefBrowser, frame: CefFrame, httpStatusCode: Int) {
                if (frame.isMain && cefBrowser.url != "about:blank") {
                    injectBridge(cefBrowser)
                }
            }
        }, browser.cefBrowser)

        if (deferLoad) {
            // FileEditor: load URL once the component enters the Swing hierarchy
            browser.component.addHierarchyListener { e ->
                if (e.changeFlags and java.awt.event.HierarchyEvent.SHOWING_CHANGED.toLong() != 0L
                    && browser.component.isShowing
                    && loaded.compareAndSet(false, true)
                ) {
                    browser.loadURL(startUrl)
                }
            }
        }
    }

    /**
     * Start loading the page. Call this AFTER setting [onBridgeReady] and [messageHandler].
     * For deferLoad=true panels, loading is automatic via HierarchyListener — this is a no-op.
     */
    fun load() {
        if (!deferLoad && loaded.compareAndSet(false, true)) {
            log.info("load() called — loading $startUrl")
            browser.loadURL(startUrl)
        }
    }

    val component: JComponent
        get() = browser.component

    /**
     * Push data to the webview on a named channel.
     * The React app listens via: window.addEventListener('jcef-data', e => { ... })
     */
    fun pushData(channel: String, jsonPayload: String) {
        // Base64 encode the UTF-8 bytes, then decode in JS using
        // Uint8Array + TextDecoder to handle multi-byte characters correctly.
        // Plain atob() corrupts non-ASCII text (e.g. Copilot reply content).
        val encoded = Base64.getEncoder().encodeToString(jsonPayload.toByteArray(Charsets.UTF_8))
        val js = """
            (function() {
                try {
                    var binary = atob('$encoded');
                    var bytes = new Uint8Array(binary.length);
                    for (var i = 0; i < binary.length; i++) {
                        bytes[i] = binary.charCodeAt(i);
                    }
                    var decoded = new TextDecoder('utf-8').decode(bytes);
                    var payload = JSON.parse(decoded);
                    window.dispatchEvent(new CustomEvent('jcef-data', {
                        detail: { channel: '$channel', payload: payload }
                    }));
                } catch(e) {
                    console.error('[pushData] Failed on channel=$channel', e);
                }
            })();
        """.trimIndent()
        browser.cefBrowser.executeJavaScript(js, browser.cefBrowser.url, 0)
    }

    private fun handleJsMessage(message: String) {
        log.info("Message from JS: $message")
        messageHandler?.invoke(message)
    }

    private fun injectBridge(cefBrowser: CefBrowser) {
        log.info("Injecting JCEF bridge into ${cefBrowser.url}")
        val postMessageJs = jsQuery.inject("message")
        val js = """
            window.__bridge = {
                postMessage: function(message) {
                    $postMessageJs
                }
            };
            window.dispatchEvent(new Event('bridge-ready'));
            console.log('[JcefBrowserPanel] Bridge injected');
        """.trimIndent()
        cefBrowser.executeJavaScript(js, cefBrowser.url, 0)
        log.info("Bridge injection JS executed, onBridgeReady=${if (onBridgeReady != null) "SET" else "NULL"}")
        onBridgeReady?.invoke()
    }

    /**
     * Registers a custom CefRequestHandler that intercepts requests to
     * the copilot-webview origin and serves them from plugin classpath resources.
     */
    private fun registerResourceHandler() {
        val urlPrefix = "$PROTOCOL://$HOST/"
        val requestHandler = object : CefRequestHandlerAdapter() {
            override fun getResourceRequestHandler(
                browser: CefBrowser,
                frame: CefFrame?,
                request: CefRequest,
                isNavigation: Boolean,
                isDownload: Boolean,
                requestInitiator: String?,
                disableDefaultHandling: BoolRef?
            ): CefResourceRequestHandler? {
                val url = request.url ?: return null
                if (!url.startsWith(urlPrefix)) return null
                return object : CefResourceRequestHandlerAdapter() {
                    override fun getResourceHandler(
                        browser: CefBrowser,
                        frame: CefFrame?,
                        request: CefRequest
                    ): CefResourceHandler? {
                        val path = request.url.removePrefix(urlPrefix)
                        return createResourceHandler(path)
                    }
                }
            }
        }
        browser.jbCefClient.addRequestHandler(requestHandler, browser.cefBrowser)
    }

    private fun createResourceHandler(path: String): CefResourceHandler? {
        val resourcePath = "/webview/$path"
        val stream = javaClass.getResourceAsStream(resourcePath) ?: return null
        val mimeType = when {
            path.endsWith(".html") -> "text/html"
            path.endsWith(".js") -> "application/javascript"
            path.endsWith(".css") -> "text/css"
            path.endsWith(".svg") -> "image/svg+xml"
            path.endsWith(".png") -> "image/png"
            path.endsWith(".json") -> "application/json"
            path.endsWith(".woff2") -> "font/woff2"
            path.endsWith(".woff") -> "font/woff"
            else -> "application/octet-stream"
        }
        return InputStreamResourceHandler(stream, mimeType)
    }

    override fun dispose() {
        Disposer.dispose(jsQuery)
        Disposer.dispose(browser)
    }

    companion object {
        private const val PROTOCOL = "http"
        private const val HOST = "copilot-webview"
        private const val RESOURCE_URL = "$PROTOCOL://$HOST/index.html"
        private const val DEV_URL = "http://localhost:5173"

        fun isSupported(): Boolean = JBCefApp.isSupported()
    }
}

/**
 * Serves an InputStream as a CefResourceHandler response.
 */
private class InputStreamResourceHandler(
    private val inputStream: InputStream,
    private val mimeType: String
) : CefResourceHandler {

    private val data: ByteArray by lazy { inputStream.use { it.readBytes() } }
    private var offset = 0

    override fun processRequest(request: CefRequest, callback: CefCallback): Boolean {
        callback.Continue()
        return true
    }

    override fun getResponseHeaders(response: CefResponse, responseLength: IntRef, redirectUrl: StringRef?) {
        response.mimeType = mimeType
        response.status = 200
        responseLength.set(data.size)
    }

    override fun readResponse(dataOut: ByteArray, bytesToRead: Int, bytesRead: IntRef, callback: CefCallback): Boolean {
        if (offset >= data.size) return false
        val available = minOf(bytesToRead, data.size - offset)
        System.arraycopy(data, offset, dataOut, 0, available)
        offset += available
        bytesRead.set(available)
        return true
    }

    override fun cancel() {}
}
