package com.gallery.app

import android.Manifest
import android.app.Activity
import android.content.ContentUris
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Size
import android.webkit.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class MainActivity : Activity() {

    private lateinit var webView: WebView
    private val PERMISSION_REQUEST_CODE = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Pure dark status bar & navigation bar
        window.statusBarColor = 0xFF09090B.toInt()
        window.navigationBarColor = 0xFF09090B.toInt()

        webView = WebView(this)
        setContentView(webView)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            mediaPlaybackRequiresUserGesture = false
        }

        // Attach window.AndroidMediaStore bridge to HTML
        webView.addJavascriptInterface(MediaStoreBridge(), "AndroidMediaStore")

        // Intercept https://mediastore.local/<id> to stream real device photos & videos
        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                val url = request?.url ?: return null
                if (url.host == "mediastore.local") {
                    return try {
                        val id = url.lastPathSegment?.toLong() ?: return null
                        val mime = url.getQueryParameter("type") ?: "image/jpeg"
                        val isThumb = url.getQueryParameter("thumb") == "1"

                        val baseUri = if (mime.startsWith("video")) {
                            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                        } else {
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                        }
                        val contentUri = ContentUris.withAppendedId(baseUri, id)

                        // Fast thumbnail streaming for grid view on Android 10+
                        if (isThumb && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            val bmp = contentResolver.loadThumbnail(contentUri, Size(400, 400), null)
                            val bos = ByteArrayOutputStream()
                            bmp.compress(Bitmap.CompressFormat.JPEG, 80, bos)
                            val headers = mapOf("Access-Control-Allow-Origin" to "*")
                            return WebResourceResponse(
                                "image/jpeg",
                                "UTF-8",
                                200,
                                "OK",
                                headers,
                                ByteArrayInputStream(bos.toByteArray())
                            )
                        }

                        // Full resolution photo or video stream
                        val inputStream = contentResolver.openInputStream(contentUri)
                        val headers = mapOf("Access-Control-Allow-Origin" to "*")
                        WebResourceResponse(mime, "UTF-8", 200, "OK", headers, inputStream)
                    } catch (e: Exception) {
                        null
                    }
                }
                return super.shouldInterceptRequest(view, request)
            }
        }

        checkAndRequestPermissions()
    }

    private fun checkAndRequestPermissions() {
        val perms = if (Build.VERSION.SDK_INT >= 33) {
            arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO
            )
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        val missing = perms.filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), PERMISSION_REQUEST_CODE)
        }
        webView.loadUrl("file:///android_asset/index.html")
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE && grantResults.any { it == PackageManager.PERMISSION_GRANTED }) {
            webView.reload()
        }
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    inner class MediaStoreBridge {
        @JavascriptInterface
        fun getAllMediaJson(): String {
            val jsonArray = JSONArray()
            val projection = arrayOf(
                MediaStore.Files.FileColumns._ID,
                MediaStore.Files.FileColumns.DISPLAY_NAME,
                MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME,
                MediaStore.Files.FileColumns.MIME_TYPE,
                MediaStore.Files.FileColumns.SIZE,
                MediaStore.Files.FileColumns.DATE_ADDED,
                MediaStore.Files.FileColumns.WIDTH,
                MediaStore.Files.FileColumns.HEIGHT,
                MediaStore.Video.VideoColumns.DURATION,
                MediaStore.Files.FileColumns.RELATIVE_PATH
            )

            val selection = "${MediaStore.Files.FileColumns.MEDIA_TYPE}=? OR ${MediaStore.Files.FileColumns.MEDIA_TYPE}=?"
            val selectionArgs = arrayOf(
                MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
                MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString()
            )
            val sortOrder = "${MediaStore.Files.FileColumns.DATE_ADDED} DESC"
            val queryUri = MediaStore.Files.getContentUri("external")

            try {
                contentResolver.query(queryUri, projection, selection, selectionArgs, sortOrder)?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                    val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
                    val bucketCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME)
                    val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE)
                    val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
                    val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_ADDED)
                    val widthCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.WIDTH)
                    val heightCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.HEIGHT)
                    val durCol = cursor.getColumnIndexOrThrow(MediaStore.Video.VideoColumns.DURATION)
                    val pathCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.RELATIVE_PATH)

                    var count = 0
                    while (cursor.moveToNext() && count < 2000) {
                        val id = cursor.getLong(idCol)
                        val mime = cursor.getString(mimeCol) ?: "image/jpeg"
                        val relPath = if (pathCol != -1) cursor.getString(pathCol) ?: "DCIM/" else "DCIM/"

                        val obj = JSONObject().apply {
                            put("_id", id)
                            put("display_name", cursor.getString(nameCol) ?: "Media_$id")
                            put("bucket_display_name", cursor.getString(bucketCol) ?: "Internal Storage")
                            put("mime_type", mime)
                            put("size", cursor.getLong(sizeCol))
                            put("date_added", cursor.getLong(dateCol))
                            put("width", cursor.getInt(widthCol))
                            put("height", cursor.getInt(heightCol))
                            put("duration", cursor.getLong(durCol) / 1000.0)
                            put("relative_path", relPath)
                            put("uri", "https://mediastore.local/$id?type=$mime")
                            put("thumb_uri", "https://mediastore.local/$id?type=$mime&thumb=1")
                        }
                        jsonArray.put(obj)
                        count++
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            return jsonArray.toString()
        }
    }
}
inner class AppBridge {
    @JavascriptInterface
    fun getAllMediaJson(): String {
        // ... (your existing media fetch code) ...
        return jsonArray.toString()
    }

    // 🟢 Add this delete function right here!
    @JavascriptInterface
    fun deleteMedia(idString: String): Boolean {
        return try {
            val id = idString.toLong()
            val contentUri = ContentUris.withAppendedId(
                MediaStore.Files.getContentUri("external"), id
            )
            // Tells Android OS to wipe the file from storage and MediaStore
            contentResolver.delete(contentUri, null, null) > 0
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}
