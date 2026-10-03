package com.thealgothrim.dwellplay.youtube

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.thealgothrim.dwellplay.playback.PlayerHub

/**
 * The official YouTube player in a WebView. The app's controls sit beside it, never over it (YouTube
 * forbids overlays); when [request] turns null the player is stopped and destroyed.
 */
@Composable
fun YouTubeEmbed(request: PlayerHub.YouTubeRequest?, hub: PlayerHub, modifier: Modifier = Modifier) {
  val context = LocalContext.current
  val player = remember { EmbedPlayer(context, hub) }
  DisposableEffect(player) {
    hub.attachYouTube(player)
    onDispose {
      hub.detachYouTube(player)
      player.destroy()
    }
  }
  LaunchedEffect(player, request?.nonce, request == null) {
    val mountRequest = request?.let(hub::requestForMount)
    if (mountRequest == null) player.stop() else player.load(mountRequest)
  }
  AndroidView(factory = { player.view }, modifier = modifier)
}

@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
private class EmbedPlayer(private val context: Context, private val hub: PlayerHub) : PlayerHub.YouTubeHandle {
  private val main = Handler(Looper.getMainLooper())
  private var destroyed = false
  private var pageReady = false
  private val queued = ArrayList<String>()
  private val origin = "https://${context.packageName}"

  val view: WebView =
      WebView(context).apply {
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        setBackgroundColor(Color.BLACK)
        isFocusable = true
        isFocusableInTouchMode = true
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.setSupportMultipleWindows(false)
        // The page is our own asset; it never needs the phone's files or other apps' content.
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        webChromeClient =
            object : WebChromeClient() {
              // No grey play-icon poster while a video loads.
              override fun getDefaultVideoPoster(): Bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
            }
        webViewClient =
            object : WebViewClient() {
              override fun onPageFinished(view: WebView, url: String?) {
                if (destroyed) return
                pageReady = true
                queued.forEach { view.evaluateJavascript(it, null) }
                queued.clear()
              }

              // Links out of the player (the title, the YouTube logo) open in the YouTube app, never in here.
              override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (!request.isForMainFrame) return false
                // Only web links leave, and only to another app: no intent: or app-specific schemes.
                if (request.url.scheme in setOf("https", "http")) {
                  runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, request.url).addCategory(Intent.CATEGORY_BROWSABLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }
                return true
              }

              override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                Log.w(TAG, "WebView renderer gone (crashed=${detail.didCrash()})")
                reportToHub { hub.onYouTubeError(5) }
                return true
              }
            }
        addJavascriptInterface(Bridge(), "DwellBridge")
        val html = context.assets.open("youtube_player.html").bufferedReader().use { it.readText() }.replace("__ORIGIN__", origin)
        loadDataWithBaseURL("$origin/", html, "text/html", "utf-8", null)
      }

  fun load(r: PlayerHub.YouTubeRequest) =
      js("dwell.load(${str(r.videoId)}, ${str(r.playlistId)}, ${r.startSeconds}, ${r.autoplay}, ${r.captions}, ${r.playlistIndex})")

  override fun play() = js("dwell.play()")

  override fun pause() = js("dwell.pause()")

  override fun seekTo(seconds: Double) = js("dwell.seek($seconds)")

  override fun stop() = js("dwell.stop()")

  override fun next() = js("dwell.next()")

  override fun previous() = js("dwell.previous()")

  override fun captions(on: Boolean) = js("dwell.captions($on)")

  fun destroy() {
    destroyed = true
    queued.clear()
    runCatching {
      view.evaluateJavascript("dwell.stop()", null)
      view.stopLoading()
      view.loadUrl("about:blank")
      (view.parent as? ViewGroup)?.removeView(view)
      view.destroy()
    }
  }

  private fun js(code: String) {
    if (destroyed) return
    if (pageReady) view.evaluateJavascript(code, null) else queued += code
  }

  private fun reportToHub(callback: () -> Unit) {
    main.post {
      // A disposed WebView can still deliver stop/pause/progress events after another is mounted.
      if (!destroyed && hub.isAttachedYouTube(this)) callback()
    }
  }

  private fun str(s: String?): String = if (s == null) "null" else "\"" + s.replace("\\", "").replace("\"", "") + "\""

  /** Calls from the page arrive on a binder thread; the hub lives on the main thread. */
  private inner class Bridge {
    @JavascriptInterface fun onApiReady() = Unit

    @JavascriptInterface fun onReady() = Unit

    @JavascriptInterface fun onState(state: Int) = reportToHub { hub.onYouTubeState(state) }

    @JavascriptInterface fun onProgress(current: Double, duration: Double, videoId: String, playlistIndex: Int) =
        reportToHub { hub.onYouTubeProgress(current, duration, playlistIndex) }

    @JavascriptInterface fun onError(code: Int) = reportToHub { hub.onYouTubeError(code) }
  }

  companion object {
    private const val TAG = "YouTubeEmbed"
  }
}

/** Opens a YouTube video in the YouTube app (for owners who block embedding). */
fun openInYouTube(context: Context, videoId: String?, playlistId: String?) {
  val url = if (videoId != null) "https://www.youtube.com/watch?v=$videoId" else "https://www.youtube.com/playlist?list=$playlistId"
  runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
