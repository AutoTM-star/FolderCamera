package com.example.foldercamera

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.util.LruCache
import android.util.Size
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.GridView
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatImageView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 앨범: 폴더 안 사진을 바둑판으로 보기. 누르면 크게 보기 */
class AlbumActivity : AppCompatActivity() {

    private lateinit var folder: String
    private var site: Site? = null
    private lateinit var grid: GridView
    private lateinit var countText: TextView
    private val photos = mutableListOf<Uri>()
    private val cache = object : LruCache<Uri, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: Uri, value: Bitmap) = value.byteCount
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        folder = intent.getStringExtra(EXTRA_FOLDER) ?: run { finish(); return }
        site = intent.getStringExtra(EXTRA_SITE_CODE)?.let { SiteStore.find(this, it) }
        setContentView(R.layout.activity_album)
        title = "🖼 $folder"

        grid = findViewById(R.id.photoGrid)
        grid.emptyView = findViewById(R.id.emptyText)
        countText = findViewById(R.id.countText)
        grid.adapter = adapter
        grid.setOnItemClickListener { _, _, position, _ ->
            startActivity(
                Intent(this, PhotoActivity::class.java)
                    .putExtra(EXTRA_FOLDER, folder)
                    .putExtra(EXTRA_SITE_CODE, site?.code)
                    .putExtra(PhotoActivity.EXTRA_INDEX, position)
            )
        }
        countText.text = site?.let { "🏗 ${it.name} › $folder" } ?: "📁 $folder"
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    private fun load() {
        lifecycleScope.launch {
            val list = withContext(Dispatchers.IO) {
                Photos.list(this@AlbumActivity, Photos.relativePath(site, folder))
            }
            photos.clear()
            photos.addAll(list)
            adapter.notifyDataSetChanged()
            val where = site?.let { "🏗 ${it.name} › $folder" } ?: "📁 $folder"
            countText.text = "$where  ·  ${photos.size}장"
        }
    }

    private val adapter = object : BaseAdapter() {
        override fun getCount() = photos.size
        override fun getItem(position: Int) = photos[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = (convertView as? SquareImageView) ?: SquareImageView(parent.context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(0xFF2A2A2A.toInt())
            }
            val uri = photos[position]
            view.tag = uri
            val cached = cache.get(uri)
            if (cached != null) {
                view.setImageBitmap(cached)
            } else {
                view.setImageDrawable(null)
                lifecycleScope.launch {
                    val bmp = withContext(Dispatchers.IO) {
                        runCatching { contentResolver.loadThumbnail(uri, Size(320, 320), null) }.getOrNull()
                    }
                    if (bmp != null) {
                        cache.put(uri, bmp)
                        if (view.tag == uri) view.setImageBitmap(bmp)
                    }
                }
            }
            return view
        }
    }

    /** 가로=세로 정사각형 칸 */
    private class SquareImageView(ctx: Context) : AppCompatImageView(ctx) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            super.onMeasure(widthMeasureSpec, widthMeasureSpec)
        }
    }

    companion object {
        const val EXTRA_FOLDER = "folder"
        const val EXTRA_SITE_CODE = "site_code"

        fun open(ctx: Context, folder: String, site: Site?) {
            ctx.startActivity(
                Intent(ctx, AlbumActivity::class.java)
                    .putExtra(EXTRA_FOLDER, folder)
                    .putExtra(EXTRA_SITE_CODE, site?.code)
            )
        }
    }
}
