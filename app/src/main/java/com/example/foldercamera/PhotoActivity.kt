package com.example.foldercamera

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Bundle
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max

/** 사진 크게 보기: 좌우로 넘기기, 삭제, 공유, 갤러리에서 열기(확대해서 초점 확인) */
class PhotoActivity : AppCompatActivity() {

    private lateinit var folder: String
    private var site: Site? = null
    private lateinit var imageView: ImageView
    private lateinit var positionText: TextView
    private lateinit var nameText: TextView
    private val photos = mutableListOf<Uri>()
    private var index = 0

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        folder = intent.getStringExtra(AlbumActivity.EXTRA_FOLDER) ?: run { finish(); return }
        site = intent.getStringExtra(AlbumActivity.EXTRA_SITE_CODE)?.let { SiteStore.find(this, it) }
        index = savedInstanceState?.getInt(EXTRA_INDEX) ?: intent.getIntExtra(EXTRA_INDEX, 0)
        setContentView(R.layout.activity_photo)

        imageView = findViewById(R.id.photo)
        positionText = findViewById(R.id.positionText)
        nameText = findViewById(R.id.nameText)

        findViewById<View>(R.id.backButton).setOnClickListener { finish() }
        findViewById<View>(R.id.deleteButton).setOnClickListener { confirmDelete() }
        findViewById<View>(R.id.shareButton).setOnClickListener { share() }
        findViewById<View>(R.id.openButton).setOnClickListener { openInGallery() }

        val swipe = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (e1 == null || abs(vx) < abs(vy)) return false
                if (e2.x < e1.x) show(index + 1) else show(index - 1)
                return true
            }
            override fun onDoubleTap(e: MotionEvent): Boolean {
                openInGallery(); return true
            }
        })
        imageView.setOnTouchListener { _, event -> swipe.onTouchEvent(event) }

        lifecycleScope.launch {
            val list = withContext(Dispatchers.IO) {
                Photos.list(this@PhotoActivity, Photos.relativePath(site, folder))
            }
            photos.addAll(list)
            if (photos.isEmpty()) finish() else show(index.coerceIn(0, photos.size - 1))
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(EXTRA_INDEX, index)
    }

    private fun show(newIndex: Int) {
        if (newIndex !in photos.indices) return
        index = newIndex
        val uri = photos[index]
        positionText.text = "${index + 1} / ${photos.size}"
        nameText.text = ""
        lifecycleScope.launch {
            val (bmp, name) = withContext(Dispatchers.IO) {
                decode(uri) to Photos.displayName(this@PhotoActivity, uri)
            }
            if (photos.getOrNull(index) != uri) return@launch
            nameText.text = name ?: ""
            if (bmp != null) imageView.setImageBitmap(bmp)
            else imageView.setImageDrawable(null)
        }
    }

    /** 화면 크기에 맞게 줄여서 읽기 (사진 방향 자동 반영) */
    private fun decode(uri: Uri): Bitmap? = runCatching {
        val screen = max(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, uri)) { decoder, info, _ ->
            val longest = max(info.size.width, info.size.height)
            decoder.setTargetSampleSize(max(1, longest / (screen * 3 / 2)))
        }
    }.getOrNull()

    private fun confirmDelete() {
        val uri = photos.getOrNull(index) ?: return
        val message = if (site != null)
            "이 폰에서 사진을 지워요.\nOneDrive에 이미 올라간 사진은 지워지지 않아요."
        else
            "이 사진을 삭제할까요? 되돌릴 수 없어요."
        AlertDialog.Builder(this)
            .setTitle("사진 삭제")
            .setMessage(message)
            .setPositiveButton("삭제") { _, _ ->
                lifecycleScope.launch {
                    val ok = withContext(Dispatchers.IO) { Photos.delete(this@PhotoActivity, uri) }
                    if (!ok) {
                        Toast.makeText(this@PhotoActivity, "삭제하지 못했어요", Toast.LENGTH_SHORT).show()
                        return@launch
                    }
                    photos.removeAt(index)
                    if (photos.isEmpty()) finish() else show(index.coerceAtMost(photos.size - 1))
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun share() {
        val uri = photos.getOrNull(index) ?: return
        val send = Intent(Intent.ACTION_SEND)
            .setType("image/jpeg")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(send, "사진 보내기"))
    }

    private fun openInGallery() {
        val uri = photos.getOrNull(index) ?: return
        try {
            startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "image/*")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            )
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "사진을 열 앱이 없어요", Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        const val EXTRA_INDEX = "index"
    }
}
