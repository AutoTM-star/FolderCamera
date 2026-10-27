package com.example.foldercamera

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.max

/** 현장 클라우드 사진 크게 보기: 넘기기, 폰에 저장, 보내기, 원본 보기 */
class RemotePhotoActivity : AppCompatActivity() {

    private lateinit var imageView: ImageView
    private lateinit var positionText: TextView
    private lateinit var nameText: TextView
    private var photos: List<RemotePhoto> = emptyList()
    private var siteName = ""
    private var folder = ""
    private var index = 0

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        photos = holder
        if (photos.isEmpty()) { finish(); return }
        siteName = intent.getStringExtra(EXTRA_SITE) ?: ""
        folder = intent.getStringExtra(EXTRA_FOLDER) ?: ""
        index = savedInstanceState?.getInt(EXTRA_INDEX) ?: intent.getIntExtra(EXTRA_INDEX, 0)
        setContentView(R.layout.activity_photo)

        imageView = findViewById(R.id.photo)
        positionText = findViewById(R.id.positionText)
        nameText = findViewById(R.id.nameText)
        findViewById<TextView>(R.id.backButton).text = "‹ 앨범"

        // 사진 보기 화면 버튼을 클라우드 사진용으로
        findViewById<TextView>(R.id.deleteButton).apply { text = "📥\n폰에 저장"; setOnClickListener { save() } }
        findViewById<TextView>(R.id.shareButton).apply { text = "📤\n보내기"; setOnClickListener { share() } }
        findViewById<TextView>(R.id.openButton).apply { text = "🔍\n원본 보기"; setOnClickListener { openOriginal() } }
        findViewById<View>(R.id.backButton).setOnClickListener { finish() }

        val swipe = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (e1 == null || abs(vx) < abs(vy)) return false
                if (e2.x < e1.x) show(index + 1) else show(index - 1)
                return true
            }
            override fun onDoubleTap(e: MotionEvent): Boolean { openOriginal(); return true }
        })
        imageView.setOnTouchListener { _, event -> swipe.onTouchEvent(event) }
        show(index.coerceIn(0, photos.size - 1))
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(EXTRA_INDEX, index)
    }

    private fun show(newIndex: Int) {
        if (newIndex !in photos.indices) return
        index = newIndex
        val p = photos[index]
        positionText.text = "${index + 1} / ${photos.size}"
        nameText.text = p.name
        imageView.setImageDrawable(null)
        lifecycleScope.launch {
            // 먼저 큰 미리보기, 이어서 원본(화면 크기로 줄여서)
            for (url in listOfNotNull(p.large ?: p.small, p.full).distinct()) {
                val bmp = withContext(Dispatchers.IO) {
                    runCatching { decode(Api.download(url)) }.getOrNull()
                }
                if (photos.getOrNull(index) != p) return@launch
                if (bmp != null) imageView.setImageBitmap(bmp)
            }
        }
    }

    private fun decode(bytes: ByteArray): Bitmap {
        val screen = max(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
        return ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
            val longest = max(info.size.width, info.size.height)
            decoder.setTargetSampleSize(max(1, longest / (screen * 3 / 2)))
        }
    }

    private suspend fun originalBytes(): ByteArray? {
        val p = photos.getOrNull(index) ?: return null
        val url = p.full ?: p.large ?: return null
        return withContext(Dispatchers.IO) { runCatching { Api.download(url) }.getOrNull() }
    }

    private fun save() {
        val p = photos.getOrNull(index) ?: return
        Toast.makeText(this, "저장하는 중…", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val bytes = originalBytes()
            if (bytes == null) {
                Toast.makeText(this@RemotePhotoActivity, "사진을 받지 못했어요. 인터넷을 확인하세요", Toast.LENGTH_LONG).show()
                return@launch
            }
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, p.name)
                        put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                        put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/폴더카메라 받은 사진")
                    }
                    val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!
                    contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }
                }.isSuccess
            }
            Toast.makeText(
                this@RemotePhotoActivity,
                if (ok) "갤러리 '폴더카메라 받은 사진' 앨범에 저장했어요" else "저장하지 못했어요",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun share() {
        val p = photos.getOrNull(index) ?: return
        lifecycleScope.launch {
            val bytes = originalBytes()
            if (bytes == null) {
                Toast.makeText(this@RemotePhotoActivity, "사진을 받지 못했어요. 인터넷을 확인하세요", Toast.LENGTH_LONG).show()
                return@launch
            }
            val file = withContext(Dispatchers.IO) {
                val dir = File(cacheDir, "share").apply { mkdirs() }
                File(dir, p.name.replace(Regex("[\\\\/:*?\"<>|]"), "_")).apply { writeBytes(bytes) }
            }
            val uri = FileProvider.getUriForFile(this@RemotePhotoActivity, "$packageName.files", file)
            val send = Intent(Intent.ACTION_SEND)
                .setType("image/jpeg")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(send, "사진 보내기"))
        }
    }

    private fun openOriginal() {
        val p = photos.getOrNull(index) ?: return
        val url = p.full ?: p.large ?: return
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "사진을 열 앱이 없어요", Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        private const val EXTRA_SITE = "site"
        private const val EXTRA_FOLDER = "folder"
        const val EXTRA_INDEX = "index"

        /** 목록이 커서 Intent 대신 메모리로 넘김 */
        private var holder: List<RemotePhoto> = emptyList()

        fun open(ctx: Context, siteName: String, folder: String, photos: List<RemotePhoto>, index: Int) {
            holder = photos.toList()
            ctx.startActivity(
                Intent(ctx, RemotePhotoActivity::class.java)
                    .putExtra(EXTRA_SITE, siteName)
                    .putExtra(EXTRA_FOLDER, folder)
                    .putExtra(EXTRA_INDEX, index)
            )
        }
    }
}
