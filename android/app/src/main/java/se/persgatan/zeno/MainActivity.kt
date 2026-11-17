package se.persgatan.zeno

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.ExifInterface
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Base64
import android.view.WindowManager
import android.webkit.GeolocationPermissions
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Zeno visar webbappen (GitHub Pages). Skalet lägger till:
 *  - kamera i appen (getUserMedia) och kameraappen som reserv för <input type="file">,
 *  - position (GPS) åt webbappen,
 *  - App.heading(): kompassriktningen dit kameran pekar, mot geografisk norr,
 *  - App.speak()/stopSpeaking(): uppläsning på svenska med Androids talsyntes,
 *  - App.keepScreenOn(): skärmen tänd medan en berättelse läses upp,
 *  - App.takePending(): foto som blev kvar om Android stängde appen medan kameraappen var öppen.
 *
 * Bryggan App.* svarar bara när sidan som visas är webbappen.
 */
class MainActivity : Activity(), SensorEventListener {

    companion object {
        const val START_URL = "https://persgatan51.github.io/zeno/"
        const val HOST = "persgatan51.github.io"
        const val BRIDGE_VERSION = "1"
        const val REQ_FILE = 42
        const val REQ_PERMS = 50
        const val REQ_CAM = 51
        const val REQ_LOC = 52
    }

    private lateinit var web: WebView
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var photoUri: Uri? = null
    private val worker = Executors.newSingleThreadExecutor()
    private val prefs by lazy { getSharedPreferences("zeno", MODE_PRIVATE) }
    @Volatile private var pageHost: String? = null

    // Behörigheter som webbsidan väntar på.
    private var pendingWebPerm: PermissionRequest? = null
    private var pendingGeo: Pair<String, GeolocationPermissions.Callback>? = null

    // Kompass
    private val sensors by lazy { getSystemService(SENSOR_SERVICE) as SensorManager }
    private val rot = FloatArray(9)
    @Volatile private var azimuth = -1.0
    @Volatile private var azimuthAt = 0L

    // Uppläsning
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var ttsFailed = false
    private var ttsSwedish = false
    private val ttsQueue = mutableListOf<() -> Unit>()
    @Volatile private var lastUtterance: String? = null
    @Volatile private var speakId: String? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        setContentView(web)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.mediaPlaybackRequiresUserGesture = false
        web.settings.setGeolocationEnabled(true)

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                if (uri.host == HOST) return false
                openExternal(uri)
                return true
            }
            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                pageHost = url?.let { Uri.parse(it).host }
            }
            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                pageHost = url?.let { Uri.parse(it).host }
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            // Kameran i appen (getUserMedia).
            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread {
                    val wantsVideo = PermissionRequest.RESOURCE_VIDEO_CAPTURE in request.resources
                    if (request.origin.host != HOST || !wantsVideo) { request.deny(); return@runOnUiThread }
                    if (granted(Manifest.permission.CAMERA)) {
                        request.grant(arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE))
                    } else {
                        pendingWebPerm?.deny()
                        pendingWebPerm = request
                        requestPermissions(arrayOf(Manifest.permission.CAMERA), REQ_CAM)
                    }
                }
            }

            override fun onPermissionRequestCanceled(request: PermissionRequest) {
                if (pendingWebPerm == request) pendingWebPerm = null
            }

            // Position åt webbappen.
            override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
                if (Uri.parse(origin).host != HOST) { callback.invoke(origin, false, false); return }
                if (granted(Manifest.permission.ACCESS_FINE_LOCATION) || granted(Manifest.permission.ACCESS_COARSE_LOCATION)) {
                    callback.invoke(origin, true, false)
                } else {
                    pendingGeo = origin to callback
                    requestPermissions(
                        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), REQ_LOC
                    )
                }
            }

            // Reserv: kameraappen eller bildväljaren för <input type="file">.
            override fun onShowFileChooser(
                view: WebView,
                callback: ValueCallback<Array<Uri>>,
                params: FileChooserParams
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = callback
                val intent = if (params.isCaptureEnabled) {
                    val dir = File(cacheDir, "photos").apply { mkdirs() }
                    dir.listFiles()?.forEach { it.delete() }
                    val file = File(dir, "zeno-${System.currentTimeMillis()}.jpg")
                    val uri = FileProvider.getUriForFile(this@MainActivity, "$packageName.files", file)
                    photoUri = uri
                    Intent(MediaStore.ACTION_IMAGE_CAPTURE)
                        .putExtra(MediaStore.EXTRA_OUTPUT, uri)
                        .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } else {
                    photoUri = null
                    params.createIntent()
                }
                try {
                    startActivityForResult(intent, REQ_FILE)
                } catch (_: Exception) {
                    fileCallback?.onReceiveValue(null)
                    fileCallback = null
                }
                return true
            }
        }

        // Be om kamera och position direkt vid första start, så att det inte avbryter senare.
        val missing = arrayOf(
            Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION
        ).filter { !granted(it) }
        if (missing.isNotEmpty() && savedInstanceState == null) requestPermissions(missing.toTypedArray(), REQ_PERMS)

        tts = TextToSpeech(this) { status -> runOnUiThread { onTtsInit(status) } }

        savedInstanceState?.getString("photoUri")?.let { photoUri = Uri.parse(it) }
        web.addJavascriptInterface(Bridge(), "App")
        // ?v= gör att sidan alltid hämtas färsk vid start (GitHub Pages cachar annars i upp till 10 min).
        if (savedInstanceState != null) web.restoreState(savedInstanceState)
        else web.loadUrl(START_URL + "?v=" + System.currentTimeMillis())
    }

    private fun granted(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        pendingWebPerm?.let { req ->
            if (granted(Manifest.permission.CAMERA)) req.grant(arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE)) else req.deny()
            pendingWebPerm = null
        }
        pendingGeo?.let { (origin, cb) ->
            val ok = granted(Manifest.permission.ACCESS_FINE_LOCATION) || granted(Manifest.permission.ACCESS_COARSE_LOCATION)
            cb.invoke(origin, ok, false)
            pendingGeo = null
        }
    }

    /* ---------- Kompass: dit kamerans baksida pekar ---------- */
    override fun onResume() {
        super.onResume()
        sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.let {
            sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    override fun onPause() {
        super.onPause()
        sensors.unregisterListener(this)
    }

    override fun onSensorChanged(e: SensorEvent) {
        if (e.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
        SensorManager.getRotationMatrixFromVector(rot, e.values)
        // rot omvandlar telefonens axlar till världens (x = öster, y = norr, z = upp).
        // Kameran pekar längs telefonens -z. Ligger telefonen platt används telefonens överkant (y) i stället.
        var east = -rot[2]
        var north = -rot[5]
        if (hypot(east, north) < 0.35f) { east = rot[1]; north = rot[4] }
        var az = Math.toDegrees(atan2(east.toDouble(), north.toDouble()))
        if (az < 0) az += 360.0
        azimuth = az
        azimuthAt = SystemClock.elapsedRealtime()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    /* ---------- Uppläsning ---------- */
    private fun onTtsInit(status: Int) {
        val t = tts
        if (status != TextToSpeech.SUCCESS || t == null) {
            ttsFailed = true
            ttsQueue.clear()
            return
        }
        val r = t.setLanguage(Locale("sv", "SE"))
        ttsSwedish = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                if (utteranceId != null && utteranceId == lastUtterance) {
                    speakId?.let { reply(it, true, "done") }
                    speakId = null
                }
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                speakId?.let { reply(it, false, "error") }
                speakId = null
            }
            override fun onStop(utteranceId: String?, interrupted: Boolean) {
                speakId?.let { reply(it, false, "stopped") }
                speakId = null
            }
        })
        ttsReady = true
        ttsQueue.forEach { it() }
        ttsQueue.clear()
    }

    private fun doSpeak(text: String, id: String) {
        val t = tts
        if (ttsFailed || t == null) { reply(id, false, "notts"); return }
        if (!ttsSwedish) { reply(id, false, "nosv"); return }
        speakId?.let { reply(it, false, "stopped") }
        t.stop()
        // Talsyntesen har en största längd per stycke: dela vid meningsslut.
        val max = minOf(TextToSpeech.getMaxSpeechInputLength(), 3000)
        val chunks = mutableListOf<String>()
        val sb = StringBuilder()
        for (sentence in text.split(Regex("(?<=[.!?])\\s+"))) {
            if (sb.length + sentence.length + 1 > max && sb.isNotEmpty()) { chunks.add(sb.toString()); sb.clear() }
            sb.append(sentence.take(max)).append(' ')
        }
        if (sb.isNotBlank()) chunks.add(sb.toString())
        if (chunks.isEmpty()) { reply(id, true, "done"); return }
        speakId = id
        lastUtterance = "$id#${chunks.size - 1}"
        chunks.forEachIndexed { i, c ->
            t.speak(c, if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, "$id#$i")
        }
    }

    /* ---------- Kameraappen (reserv) ---------- */
    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_FILE) return
        val cb = fileCallback
        if (cb == null) {
            // Appen har startats om sedan kameran öppnades: spara fotot åt webbappen.
            val uri = if (resultCode == RESULT_OK) photoUri ?: data?.data else null
            photoUri = null
            if (uri != null) {
                prefs.edit().putString("pendingUri", uri.toString()).apply()
                web.evaluateJavascript("window.__checkPending && window.__checkPending()", null)
            }
            return
        }
        fileCallback = null
        val result: Array<Uri>? = when {
            resultCode != RESULT_OK -> null
            photoUri != null -> arrayOf(photoUri!!)
            else -> WebChromeClient.FileChooserParams.parseResult(resultCode, data)
        }
        cb.onReceiveValue(result)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
        photoUri?.let { outState.putString("photoUri", it.toString()) }
    }

    /** Läser in fotot nedskalat (max 1600 px) och vänt rätt enligt EXIF, som JPEG i base64. */
    private fun photoB64(uri: Uri): String {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1600) sample *= 2
        val bmp = contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: throw Exception("Kunde inte läsa fotot")
        val deg = try {
            contentResolver.openInputStream(uri)?.use {
                when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            } ?: 0f
        } catch (_: Exception) { 0f }
        val out = if (deg != 0f) Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(deg) }, true) else bmp
        val bos = ByteArrayOutputStream()
        out.compress(Bitmap.CompressFormat.JPEG, 88, bos)
        return Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Låt webbappen stänga öppna vyer först; annars vanlig bakåt.
        web.evaluateJavascript("(window.__back && window.__back()) ? 'handled' : 'no'") { r ->
            if (r?.contains("handled") != true) {
                if (web.canGoBack()) web.goBack() else finishAfterTransition()
            }
        }
    }

    override fun onDestroy() {
        tts?.shutdown()
        super.onDestroy()
    }

    private fun openExternal(uri: Uri) {
        try { startActivity(Intent(Intent.ACTION_VIEW, uri)) } catch (_: Exception) {}
    }

    private fun reply(id: String, ok: Boolean, data: String) = runOnUiThread {
        web.evaluateJavascript(
            "window.__cb && window.__cb(${JSONObject.quote(id)}, $ok, ${JSONObject.quote(data)})", null
        )
    }

    inner class Bridge {
        private fun trusted() = pageHost == HOST

        @JavascriptInterface fun version(): String = if (trusted()) BRIDGE_VERSION else "0"

        /**
         * Kompassriktning dit kameran pekar, 0–360 grader mot geografisk norr. -1 om kompass saknas.
         * lat/lon används för att rätta för missvisningen; skicka 999 om positionen är okänd.
         */
        @JavascriptInterface fun heading(lat: Double, lon: Double): Double {
            if (!trusted()) return -1.0
            if (azimuth < 0 || SystemClock.elapsedRealtime() - azimuthAt > 3000) return -1.0
            if (lat > 90 || lat < -90) return azimuth
            val decl = GeomagneticField(lat.toFloat(), lon.toFloat(), 0f, System.currentTimeMillis()).declination
            return (azimuth + decl + 360.0) % 360.0
        }

        /** Läser upp texten på svenska. Svarar "done", "stopped", "nosv" (svensk röst saknas) eller "notts". */
        @JavascriptInterface fun speak(text: String, id: String) {
            if (!trusted()) return
            runOnUiThread {
                val go = { doSpeak(text, id) }
                when {
                    ttsReady -> go()
                    ttsFailed -> reply(id, false, "notts")
                    else -> ttsQueue.add(go)
                }
            }
        }

        @JavascriptInterface fun stopSpeaking() {
            if (!trusted()) return
            runOnUiThread { tts?.stop() }
        }

        @JavascriptInterface fun keepScreenOn(on: Boolean) {
            if (!trusted()) return
            runOnUiThread {
                if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }

        /** Foto som blev kvar efter att Android stängt appen. Svarar med base64-JPEG eller tom text. */
        @JavascriptInterface fun takePending(id: String) {
            if (!trusted()) return
            val uriStr = prefs.getString("pendingUri", null)
            prefs.edit().remove("pendingUri").apply()
            if (uriStr == null) { reply(id, true, ""); return }
            worker.execute {
                try { reply(id, true, photoB64(Uri.parse(uriStr))) }
                catch (e: Exception) { reply(id, false, "Kunde inte läsa fotot: ${e.message}") }
            }
        }

        // Bara http(s)- och geo-länkar öppnas utanför appen.
        @JavascriptInterface fun openExternal(url: String) {
            if (!trusted()) return
            val uri = Uri.parse(url)
            if (uri.scheme !in setOf("https", "http", "geo")) return
            runOnUiThread { this@MainActivity.openExternal(uri) }
        }
    }
}
