package com.grapsee.grapmail.ui.screens.mail

import android.annotation.SuppressLint
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONArray
import org.json.JSONObject

private class DraftEditorState {
    var ready = false
    var renderedText: String? = null
    var renderedHtml: String? = null
    var text = ""
    var html: String? = null

    fun sync(view: WebView) {
        if (!ready || (renderedText == text && renderedHtml == html)) return
        renderedText = text
        renderedHtml = html
        val arguments = JSONArray().put(text).put(html.orEmpty())
        view.evaluateJavascript("window.setDraft.apply(null, $arguments)", null)
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun ComposeFormattingEditor(
    text: String,
    html: String?,
    onChange: (String, String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state = remember { DraftEditorState() }
    val currentChange = rememberUpdatedState(onChange)
    AndroidView(
        modifier = modifier,
        factory = { context ->
            val view = WebView(context)
            view.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            view.settings.javaScriptEnabled = true
            view.settings.allowFileAccess = false
            view.settings.allowContentAccess = false
            view.settings.blockNetworkLoads = true
            view.addJavascriptInterface(object {
                @JavascriptInterface
                fun changed(payload: String) {
                    val draft = runCatching { JSONObject(payload) }.getOrNull() ?: return
                    val plain = draft.optString("text")
                    val rich = if (draft.isNull("html")) null else draft.optString("html")
                    view.post {
                        if (!state.ready) return@post
                        state.renderedText = plain
                        state.renderedHtml = rich
                        currentChange.value(plain, rich)
                    }
                }
            }, "GrapMailDraft")
            view.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
                override fun onPageFinished(view: WebView, url: String) {
                    state.ready = true
                    state.sync(view)
                }
            }
            val document = context.assets.open("GrapMailComposeEditor.html").bufferedReader().use { it.readText() }
            view.loadDataWithBaseURL(null, document, "text/html", "utf-8", null)
            view
        },
        update = { view ->
            state.text = text
            state.html = html
            state.sync(view)
        },
        onRelease = { view ->
            state.ready = false
            view.removeJavascriptInterface("GrapMailDraft")
            view.destroy()
        },
    )
}
