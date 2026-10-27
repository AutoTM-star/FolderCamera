package com.example.foldercamera

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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

/**
 * 앨범
 *  - 공유 현장: [☁ 모두가 올린 사진] (현장 클라우드) / [📱 이 폰에서 찍은 사진] 탭
 *  - 내 폴더: 이 폰에서 찍은 사진만
 */
class AlbumActivity : AppCompatActivity() {

    private lateinit var folder: String
    private var site: Site? = null
    private lateinit var grid: GridView
    private lateinit var countText: TextView
    private lateinit var emptyText: TextView
    private lateinit var cloudTab: TextView
    private lateinit var phoneTab: TextView

    private var showCloud = false
    private val phonePhotos = mutableListOf<Uri>()
    private val cloudPhotos = mutableListOf<RemotePhoto>()
    private var cloudError: String? = null

    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        folder = intent.getStringExtra(EXTRA_FOLDER) ?: run { finish(); return }
        site = intent.getStringExtra(EXTRA_SITE_CODE)?.let { SiteStore.find(this, it) }
        setContentView(R.layout.activity_album)
        title = "🖼 $folder"

        grid = findViewById(R.id.photoGrid)
        emptyText = findViewById(R.id.emptyText)
        grid.emptyView = emptyText
        countText = findViewById(R.id.countText)
        cloudTab = findViewById(R.id.cloudTab)
        phoneTab = findViewById(R.id.phoneTab)
        grid.adapter = adapter

        if (site != null) {
            showCloud = savedInstanceState?.getBoolean(KEY_CLOUD) ?: intent.getBooleanExtra(EXTRA_CLOUD, true)
            findViewById<View>(R.id.tabRow).visibility = View.VISIBLE
            cloudTab.setOnClickListener { selectTab(true) }
            phoneTab.setOnClickListener { selectTab(false) }
            findViewById<View>(R.id.refreshButton).setOnClickListener { load() }
        }

        grid.setOnItemClickListener { _, _, position, _ ->
            if (showCloud) {
                RemotePhotoActivity.open(this, site?.name ?: "", folder, cloudPhotos, position)
            } else {
                startActivity(
                    Intent(this, PhotoActivity::class.java)
                        .putExtra(EXTRA_FOLDER, folder)
                        .putExtra(EXTRA_SITE_CODE, site?.code)
                        .putExtra(PhotoActivity.EXTRA_INDEX, position)
                )
            }
        }
        updateTabs()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_CLOUD, showCloud)
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    private fun selectTab(cloud: Boolean) {
        showCloud = cloud
        updateTabs()
        adapter.notifyDataSetChanged()
        updateCount()
    }

    private fun updateTabs() {
        cloudTab.isSelected = showCloud
        phoneTab.isSelected = !showCloud
        cloudTab.alpha = if (showCloud) 1f else 0.6f
        phoneTab.alpha = if (showCloud) 0.6f else 1f
    }

    private fun load() {
        val where = site?.let { "🏗 ${it.name} › $folder" } ?: "📁 $folder"
        countText.text = "$where  ·  불러오는 중…"
        lifecycleScope.launch {
            val list = withContext(Dispatchers.IO) {
                Photos.list(this@AlbumActivity, Photos.relativePath(site, folder))
            }
            phonePhotos.clear()
            phonePhotos.addAll(list)
            adapter.notifyDataSetChanged()
            updateCount()

            val currentSite = site ?: return@launch
            try {
                val remote = withContext(Dispatchers.IO) { Api.photos(currentSite.server, currentSite.code, folder) }
                cloudPhotos.clear()
                cloudPhotos.addAll(remote)
                cloudError = null
            } catch (e: ApiException) {
                cloudError = e.message
            } catch (e: Exception) {
                cloudError = "인터넷 연결이 없어 클라우드 사진을 불러오지 못했어요"
            }
            adapter.notifyDataSetChanged()
            updateCount()
        }
    }

    private fun updateCount() {
        val where = site?.let { "🏗 ${it.name} › $folder" } ?: "📁 $folder"
        if (site != null) {
            cloudTab.text = "☁ 모두가 올린 사진 (${cloudPhotos.size})"
            phoneTab.text = "📱 이 폰에서 찍은 사진 (${phonePhotos.size})"
        }
        countText.text = when {
            showCloud && cloudError != null -> "⚠ $cloudError"
            showCloud -> "$where  ·  모든 참여자가 올린 사진 ${cloudPhotos.size}장"
            else -> "$where  ·  이 폰에서 찍은 사진 ${phonePhotos.size}장"
        }
        emptyText.text = if (showCloud) "아직 클라우드에 올라간 사진이 없어요." else "아직 이 폴더에 찍은 사진이 없어요."
    }

    private val adapter = object : BaseAdapter() {
        override fun getCount() = if (showCloud) cloudPhotos.size else phonePhotos.size
        override fun getItem(position: Int): Any = if (showCloud) cloudPhotos[position] else phonePhotos[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = (convertView as? SquareImageView) ?: SquareImageView(parent.context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(0xFF2A2A2A.toInt())
            }
            val key: String
            val loader: suspend () -> Bitmap?
            if (showCloud) {
                val p = cloudPhotos[position]
                val url = p.small ?: p.large
                key = "c:" + (url ?: p.name)
                loader = {
                    url?.let { runCatching { Api.download(it) }.getOrNull() }
                        ?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                }
            } else {
                val uri = phonePhotos[position]
                key = "p:$uri"
                loader = { runCatching { contentResolver.loadThumbnail(uri, Size(320, 320), null) }.getOrNull() }
            }
            view.tag = key
            val cached = cache.get(key)
            if (cached != null) {
                view.setImageBitmap(cached)
            } else {
                view.setImageDrawable(null)
                lifecycleScope.launch {
                    val bmp = withContext(Dispatchers.IO) { loader() }
                    if (bmp != null) {
                        cache.put(key, bmp)
                        if (view.tag == key) view.setImageBitmap(bmp)
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
        private const val KEY_CLOUD = "cloud"
        private const val EXTRA_CLOUD = "start_cloud"

        /** cloud=true: '모두가 올린 사진' 탭부터, false: '이 폰에서 찍은 사진' 탭부터 */
        fun open(ctx: Context, folder: String, site: Site?, cloud: Boolean = true) {
            ctx.startActivity(
                Intent(ctx, AlbumActivity::class.java)
                    .putExtra(EXTRA_FOLDER, folder)
                    .putExtra(EXTRA_SITE_CODE, site?.code)
                    .putExtra(EXTRA_CLOUD, cloud)
            )
        }
    }
}
