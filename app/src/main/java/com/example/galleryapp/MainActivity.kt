package com.example.galleryapp

import android.Manifest
import android.app.RecoverableSecurityException
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private val PERMISSION_REQUEST_CODE = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 🔮 Initialize the WebView with JavaScript superpowers enabled
        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = WebViewClient()
            addJavascriptInterface(WebAppInterface(this@MainActivity), "AndroidMediaStore")
        }
        setContentView(webView)

        checkAndRequestPermissions()
    }

    private fun checkAndRequestPermissions() {
        // 🎫 Decide which permissions to ask based on Android version (Android 13+ support)
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO
            )
        } else {
            arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE
            )
        }

        val needed = permissions.any {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (needed) {
            ActivityCompat.requestPermissions(this, permissions, PERMISSION_REQUEST_CODE)
        } else {
            loadGalleryHtml()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            loadGalleryHtml()
        }
    }

    private fun loadGalleryHtml() {
        // 📦 Load your index.html file directly from the assets folder automatically!
        webView.loadUrl("file:///android_asset/index.html")
    }

    // 🌉 The JavaScript Bridge Interface handling native magic
    inner class WebAppInterface(private val activity: AppCompatActivity) {

        @JavascriptInterface
        fun getAllMediaJson(): String {
            val jsonArray = JSONArray()
            val projection = arrayOf(
                MediaStore.Files.FileColumns._ID,
                MediaStore.Files.FileColumns.DISPLAY_NAME,
                MediaStore.Files.FileColumns.MIME_TYPE,
                MediaStore.Files.FileColumns.SIZE,
                MediaStore.Files.FileColumns.DATE_ADDED,
                MediaStore.Images.Media.WIDTH,
                MediaStore.Images.Media.HEIGHT,
                MediaStore.Video.Media.DURATION,
                MediaStore.Images.Media.BUCKET_DISPLAY_NAME
            )

            val uri = MediaStore.Files.getContentUri("external")
            val selection = "${MediaStore.Files.FileColumns.MIME_TYPE} LIKE 'image/%' OR ${MediaStore.Files.FileColumns.MIME_TYPE} LIKE 'video/%'"
            
            activity.contentResolver.query(uri, projection, selection, null, "${MediaStore.Files.FileColumns.DATE_ADDED} DESC")?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
                val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_ADDED)
                val widthCol = cursor.getColumnIndex(MediaStore.Images.Media.WIDTH)
                val heightCol = cursor.getColumnIndex(MediaStore.Images.Media.HEIGHT)
                val durationCol = cursor.getColumnIndex(MediaStore.Video.Media.DURATION)
                val bucketCol = cursor.getColumnIndex(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val name = cursor.getString(nameCol) ?: "Unknown"
                    val mime = cursor.getString(mimeCol) ?: "image/jpeg"
                    val size = cursor.getLong(sizeCol)
                    val dateAdded = cursor.getLong(dateCol)
                    val width = if (widthCol != -1) cursor.getInt(widthCol) else 0
                    val height = if (heightCol != -1) cursor.getInt(heightCol) else 0
                    val duration = if (durationCol != -1) cursor.getLong(durationCol) else 0L
                    val bucket = if (bucketCol != -1) cursor.getString(bucketCol) else "Internal Storage"

                    val contentUri = ContentUris.withAppendedId(
                        if (mime.startsWith("video")) MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                        else MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        id
                    )

                    val obj = JSONObject().apply {
                        put("_id", id)
                        put("display_name", name)
                        put("mime_type", mime)
                        put("size", size)
                        put("date_added", dateAdded)
                        put("width", width)
                        put("height", height)
                        put("duration", duration)
                        put("bucket_display_name", bucket)
                        put("uri", contentUri.toString())
                    }
                    jsonArray.put(obj)
                }
            }
            return jsonArray.toString()
        }

        @JavascriptInterface
        fun deleteMedia(id: Long, uriString: String): Boolean {
            try {
                // 🗑️ Programmatically banish the file from local storage!
                val contentUri = Uri.parse(uriString)
                val deletedRows = activity.contentResolver.delete(contentUri, null, null)
                return deletedRows > 0
            } catch (e: RecoverableSecurityException) {
                // 🛡️ Android 13+ security guard intervention: ask user consent safely if required!
                val intentSender = e.userAction.actionIntent.intentSender
                activity.startIntentSenderForResult(intentSender, 1002, null, 0, 0, 0, null)
                return false
            } catch (e: Exception) {
                e.printStackTrace()
                return false
            }
        }

        @JavascriptInterface
        fun shareMedia(id: Long, uriString: String) {
            try {
                // 📤 Summon the native share sheet to fling files across apps!
                val uri = Uri.parse(uriString)
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = activity.contentResolver.getType(uri) ?: "*/*"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                activity.startActivity(Intent.createChooser(intent, "Share Media"))
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
