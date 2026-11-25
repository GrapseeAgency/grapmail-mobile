package com.grapsee.grapmail.ui.screens.mail

import android.annotation.SuppressLint
import android.view.DragEvent
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONArray
import org.json.JSONObject

/** An uploaded image waiting to be placed in the editor (see insertImage in the HTML). */
internal data class InlineImage(val contentId: String, val preview: String, val alt: String, val width: Int)

/** Text dropped on the compose screen, waiting to be typed at the editor's caret (see insertText in the HTML). */
internal data class DroppedText(val id: Long, val text: String)

private class DraftEditorState {
    var insertedIds = mutableSetOf<String>()
    var insertedTextIds = mutableSetOf<Long>()
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

/**
 * The formatting editor. Its text follows the system font size the same way
 * the reader's HTML body does ([mailTextZoom]: the composition's font scale,
 * capped at 200 %), so switching between the plain field and this editor
 * never changes the size of what the person is typing.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun ComposeFormattingEditor(
    text: String,
    html: String?,
    onChange: (String, String?) -> Unit,
    modifier: Modifier = Modifier,
    pendingImage: InlineImage? = null,
    onImageInserted: () -> Unit = {},
    onPickImage: (() -> Unit)? = null,
    droppedText: DroppedText? = null,
    onTextInserted: () -> Unit = {},
    onDragDrop: ((DragEvent) -> Boolean)? = null,
    onDragHover: (Boolean) -> Unit = {},
) {
    val state = remember { DraftEditorState() }
    val currentChange = rememberUpdatedState(onChange)
    val currentPick = rememberUpdatedState(onPickImage)
    val currentInserted = rememberUpdatedState(onImageInserted)
    val currentTextInserted = rememberUpdatedState(onTextInserted)
    val currentDrop = rememberUpdatedState(onDragDrop)
    val currentHover = rememberUpdatedState(onDragHover)
    val textZoom = mailTextZoom(LocalDensity.current.fontScale)
    AndroidView(
        modifier = modifier,
        factory = { context ->
            val view = WebView(context)
            view.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            view.settings.javaScriptEnabled = true
            view.settings.allowFileAccess = false
            view.settings.allowContentAccess = false
            view.settings.blockNetworkLoads = true
            view.settings.textZoom = textZoom
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

                @JavascriptInterface
                fun pickImage() {
                    view.post { currentPick.value?.invoke() }
                }
            }, "GrapMailDraft")
            // A WebView takes drag events itself, ahead of the Compose screen
            // around it, and the page refuses drops on purpose (no foreign HTML
            // gets in that way). So drops here are answered natively and handed
            // to the screen's own drop handler: files become attachments, text
            // is typed at the caret through insertText.
            view.setOnDragListener { _, event ->
                val drop = currentDrop.value ?: return@setOnDragListener false
                when (event.action) {
                    DragEvent.ACTION_DRAG_STARTED -> (event.clipDescription?.mimeTypeCount ?: 0) > 0
                    DragEvent.ACTION_DRAG_ENTERED -> { currentHover.value(true); true }
                    DragEvent.ACTION_DRAG_EXITED, DragEvent.ACTION_DRAG_ENDED -> { currentHover.value(false); true }
                    DragEvent.ACTION_DROP -> { currentHover.value(false); drop(event) }
                    else -> true
                }
            }
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
            if (view.settings.textZoom != textZoom) view.settings.textZoom = textZoom
            state.text = text
            state.html = html
            state.sync(view)
            val image = pendingImage
            if (image != null && state.ready && state.insertedIds.add(image.contentId)) {
                val arguments = JSONArray().put(image.contentId).put(image.preview).put(image.alt).put(image.width)
                view.evaluateJavascript("window.insertImage.apply(null, $arguments)", null)
                view.post { currentInserted.value() }
            }
            val dropped = droppedText
            if (dropped != null && state.ready && state.insertedTextIds.add(dropped.id)) {
                view.evaluateJavascript("window.insertText.apply(null, ${JSONArray().put(dropped.text)})", null)
                view.post { currentTextInserted.value() }
            }
        },
        onRelease = { view ->
            state.ready = false
            view.setOnDragListener(null)
            view.removeJavascriptInterface("GrapMailDraft")
            view.destroy()
        },
    )
}
