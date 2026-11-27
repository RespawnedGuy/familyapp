package io.github.respawnedguy.familyapp

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import io.github.respawnedguy.familyapp.data.InstagramPages
import org.json.JSONArray
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Second way to Instagram's pages (ADR 0007, "Rückfall"): an invisible WebView without login loads
 * the same page as a phone browser would, with Chromium's own network stack. Instagram answered the
 * plain HttpURLConnection request on Nils' phone with something other than the page (27.11.2026).
 *
 * Same limits as the direct fetch (threat model TE-9, TE-10): only paths InstagramLink builds, only
 * www.instagram.com, navigation anywhere else or to the login page ends the attempt, no JavaScript
 * interface into the app, at most [MAX_CHARS] of HTML, cookies are cleared again afterwards.
 * Blocking: call it off the main thread (null on the main thread).
 */
class WebViewInstagramPages(private val context: Context) : InstagramPages {

    private val main = Handler(Looper.getMainLooper())

    override fun get(path: String): String? {
        if (!SAFE_PATH.matches(path) || Looper.myLooper() == Looper.getMainLooper()) return null
        val result = AtomicReference<String?>()
        val done = CountDownLatch(1)
        val view = AtomicReference<WebView?>()
        main.post {
            runCatching { view.set(open(path, result, done)) }.onFailure { done.countDown() }
        }
        done.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        main.post {
            view.getAndSet(null)?.apply { stopLoading(); destroy() }
            runCatching { CookieManager.getInstance().removeAllCookies(null) }
        }
        return result.get()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun open(path: String, result: AtomicReference<String?>, done: CountDownLatch): WebView {
        val finished = AtomicBoolean(false)
        fun finish(html: String?) {
            if (finished.compareAndSet(false, true)) {
                result.set(html?.takeIf { it.length <= MAX_CHARS })
                done.countDown()
            }
        }
        return WebView(context.applicationContext).apply {
            // Instagram's page may need its own scripts; the app exposes nothing to them.
            settings.javaScriptEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.setGeolocationEnabled(false)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val allowed = isInstagramPage(request.url)
                    if (!allowed && request.isForMainFrame) finish(null)
                    return !allowed
                }

                override fun onPageFinished(view: WebView, url: String) {
                    if (!isInstagramPage(Uri.parse(url))) return finish(null)
                    view.evaluateJavascript("document.documentElement.outerHTML") { raw ->
                        // The value comes back as a JSON string literal.
                        finish(runCatching { JSONArray("[$raw]").getString(0) }.getOrNull())
                    }
                }
            }
            loadUrl(INSTAGRAM + path)
        }
    }

    private fun isInstagramPage(uri: Uri): Boolean =
        uri.scheme == "https" && uri.host == HOST && uri.path?.startsWith("/accounts/") != true

    private companion object {
        const val HOST = "www.instagram.com"
        const val INSTAGRAM = "https://$HOST"
        const val TIMEOUT_MS = 20_000L

        /** The embed page is about 250 KB, the post page about 600 KB (26.11.2026). */
        const val MAX_CHARS = 3 * 1024 * 1024
        val SAFE_PATH = Regex("""^/p/[A-Za-z0-9_-]{1,64}/(?:embed/captioned/)?$""")
    }
}
