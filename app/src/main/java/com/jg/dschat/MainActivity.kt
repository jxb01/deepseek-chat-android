package com.jg.dschat

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.zip.ZipFile
import kotlin.coroutines.resume

class MainActivity : ComponentActivity() {

    private lateinit var web: WebView
    private lateinit var status: TextView
    private var fileCallback: ValueCallback<Array<Uri>>? = null

    private val filePicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val cb = fileCallback
        fileCallback = null
        cb?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data))
    }

    private val zipPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importZip(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.allowContentAccess = true
            settings.mediaPlaybackRequiresUserGesture = false
            webViewClient = object : WebViewClientCompat() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                    loader.shouldInterceptRequest(request.url)
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    if (request.url.host == "appassets.androidplatform.net") return false
                    return try { startActivity(Intent(Intent.ACTION_VIEW, request.url)); true } catch (e: Exception) { false }
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onShowFileChooser(w: WebView?, callback: ValueCallback<Array<Uri>>?, params: FileChooserParams?): Boolean {
                    fileCallback?.onReceiveValue(null)
                    fileCallback = callback
                    return try {
                        filePicker.launch(Intent(Intent.ACTION_GET_CONTENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
                        })
                        true
                    } catch (e: Exception) { fileCallback = null; false }
                }
            }
            loadUrl("https://appassets.androidplatform.net/assets/www/index.html")
        }

        status = TextView(this).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.argb(200, 0, 0, 0))
            textSize = 12f
            setPadding(24, 12, 24, 12)
            visibility = TextView.GONE
        }

        val importBtn = Button(this).apply {
            text = "导入ZIP"
            textSize = 12f
            setOnClickListener { zipPicker.launch(arrayOf("*/*")) }
        }

        web.addJavascriptInterface(Bridge(), "DSHNative")

        val root = FrameLayout(this).apply {
            addView(web, FrameLayout.LayoutParams(-1, -1))
            addView(status, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
            addView(importBtn, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply {
                setMargins(0, 0, 24, 24)
            })
        }
        setContentView(root)
    }

    /** Parse the export zip natively and merge it into the web app's IndexedDB in batches. */
    private fun importZip(uri: Uri) {
        status.visibility = TextView.VISIBLE
        status.text = "读取 ZIP…"
        lifecycleScope.launch {
            try {
                val cache = File(cacheDir, "import.zip")
                withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)!!.use { input ->
                        cache.outputStream().use { input.copyTo(it) }
                    }
                }
                val batches = withContext(Dispatchers.IO) { buildBatches(cache) }
                var done = 0
                for ((index, batch) in batches.withIndex()) {
                    val json = batch.toString()
                    status.text = "导入中：${done} / ${totalSessions}（第 ${index + 1}/${batches.size} 批）"
                    val ok = pushBatch(json)
                    if (!ok) throw RuntimeException("第 ${index + 1} 批写入失败")
                    done += batch.length()
                }
                status.text = "完成：${done} 个对话"
                web.evaluateJavascript("__dshImportDone()", null)
            } catch (e: Exception) {
                status.text = "导入失败：" + (e.message ?: "")
            }
        }
    }

    private var totalSessions = 0

    private fun buildBatches(zip: File): List<JSONArray> {
        val out = mutableListOf<JSONArray>()
        var batch = JSONArray()
        ZipFile(zip).use { zf ->
            val entries = zf.entries().toList()
                .filter { it.name.startsWith("chat_sessions/") && it.name.endsWith(".json") }
            totalSessions = entries.size
            for (entry in entries) {
                val text = zf.getInputStream(entry).readBytes().toString(Charsets.UTF_8)
                val obj = try { JSONObject(text) } catch (e: Exception) { continue }
                val session = sanitize(obj) ?: continue
                batch.put(session)
                if (batch.length() >= 50) { out.add(batch); batch = JSONArray() }
            }
        }
        if (batch.length() > 0) out.add(batch)
        return out
    }

    private fun textOf(fragments: JSONArray?): String {
        if (fragments == null) return ""
        val sb = StringBuilder()
        for (i in 0 until fragments.length()) {
            val f = fragments.optJSONObject(i) ?: continue
            if (f.optString("type") in listOf("REQUEST", "RESPONSE")) sb.append(f.optString("content", ""))
        }
        return sb.toString()
    }

    private fun timeOf(iso: String?): Long {
        if (iso.isNullOrBlank()) return System.currentTimeMillis()
        return try {
            val normalized = if (iso.endsWith("Z")) iso else iso + "Z"
            val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
            fmt.timeZone = java.util.TimeZone.getTimeZone("UTC")
            fmt.parse(normalized.substringBefore('.').let { it + ".000Z" }).time
        } catch (e: Exception) { System.currentTimeMillis() }
    }

    private fun sanitize(obj: JSONObject): JSONObject? {
        val messages = obj.optJSONArray("messages") ?: return null
        val list = (0 until messages.length()).mapNotNull { messages.optJSONObject(it) }
            .sortedBy { it.optInt("id", 0) }
        val clean = JSONArray()
        var parent: String? = null
        var seq = 0
        for (m in list) {
            val role = m.optString("role")
            if (role != "USER" && role != "ASSISTANT") continue
            val body = textOf(m.optJSONArray("fragments"))
            if (body.isBlank()) continue
            seq += 1
            val id = "m$seq"
            val msg = JSONObject()
                .put("id", id)
                .put("role", if (role == "USER") "user" else "assistant")
                .put("createdAt", timeOf(m.optString("inserted_at")))
            if (role == "USER") {
                msg.put("content", body).put("files", JSONArray())
            } else {
                msg.put("model", JSONObject.NULL)
                    .put("prefix", false)
                    .put("status", "done")
                    .put("blocks", JSONArray().put(JSONObject().put("type", "text").put("text", body)))
            }
            if (parent != null) msg.put("parentId", parent)
            clean.put(msg)
            parent = id
        }
        if (clean.length() == 0) return null
        val id = obj.optString("id")
        if (id.isBlank()) return null
        return JSONObject()
            .put("id", id)
            .put("title", obj.optString("title", "未命名"))
            .put("createdAt", timeOf(obj.optString("inserted_at")))
            .put("updatedAt", timeOf(obj.optString("updated_at")))
            .put("messages", clean)
    }

    private val pending = java.util.concurrent.ConcurrentHashMap<String, CompletableDeferred<Boolean>>()

    inner class Bridge {
        @android.webkit.JavascriptInterface
        fun onBatchDone(id: String, ok: Boolean) {
            pending[id]?.complete(ok)
        }
    }

    private suspend fun pushBatch(json: String): Boolean {
        val id = java.util.UUID.randomUUID().toString()
        val deferred = CompletableDeferred<Boolean>()
        pending[id] = deferred
        val script = "__dshMergeSessions(" + JSONObject.quote(id) + "," + JSONObject.quote(json) + ")"
        withContext(Dispatchers.Main) { web.evaluateJavascript(script, null) }
        val ok = withTimeoutOrNull(120_000) { deferred.await() } ?: false
        pending.remove(id)
        return ok
    }
}
