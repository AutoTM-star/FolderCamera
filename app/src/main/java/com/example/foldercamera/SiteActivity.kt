package com.example.foldercamera

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ListView
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 공유 현장 화면: 현장 클라우드의 폴더 목록 (모든 참여자가 같은 목록을 봄)
 *  - path 가 비어 있으면 현장 맨 위, 있으면 그 폴더 안 (예: "03 공종별 사진")
 *  - 하위 폴더가 있는 폴더를 누르면 안으로 들어가고, 없으면 바로 카메라
 *  - 폴더 안에서는 위쪽 [📷 이 폴더에서 바로 찍기]
 */
class SiteActivity : AppCompatActivity() {

    private lateinit var site: Site
    private var path = ""                       // "" = 현장 맨 위
    private lateinit var statusText: TextView
    private val names = mutableListOf<String>()  // 폴더 이름
    private val subs = mutableMapOf<String, Int>() // 폴더별 하위 폴더 수
    private val labels = mutableListOf<String>()  // 화면에 보이는 글자
    private lateinit var adapter: FolderAdapter

    // 갤러리에서 고른 사진을 올릴 폴더
    private var pickTarget: String? = null
    private val pickPhotos =
        registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(50)) { uris ->
            val target = pickTarget ?: return@registerForActivityResult
            if (uris.isNotEmpty()) Importer.run(this, uris, site, target)
        }

    private val cacheKey get() = if (path.isEmpty()) site.code else "${site.code}/$path"
    private fun full(name: String) = if (path.isEmpty()) name else "$path/$name"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        site = intent.getStringExtra(EXTRA_CODE)?.let { SiteStore.find(this, it) } ?: run { finish(); return }
        path = intent.getStringExtra(EXTRA_PATH) ?: ""
        pickTarget = savedInstanceState?.getString(KEY_PICK)
        setContentView(R.layout.activity_site)
        title = if (path.isEmpty()) "🏗 ${site.name}" else "📂 ${path.substringAfterLast('/')}"

        statusText = findViewById(R.id.statusText)
        val list = findViewById<ListView>(R.id.folderList)
        val empty = findViewById<TextView>(R.id.emptyText)
        list.emptyView = empty
        adapter = FolderAdapter(this, labels) { position, anchor -> showMenu(position, anchor) }
        list.adapter = adapter
        list.setOnItemClickListener { _, _, position, _ ->
            val name = names.getOrNull(position) ?: return@setOnItemClickListener
            if ((subs[name] ?: 0) > 0) openFolder(full(name)) else openCamera(full(name))
        }
        list.setOnItemLongClickListener { _, view, position, _ ->
            showMenu(position, view.findViewById<View>(R.id.menuButton) ?: view)
            true
        }

        val shootHere = findViewById<MaterialButton>(R.id.shootHereButton)
        if (path.isNotEmpty()) {
            shootHere.visibility = View.VISIBLE
            shootHere.text = "📷 '${path.substringAfterLast('/')}' 폴더에서 바로 찍기"
            shootHere.setOnClickListener { openCamera(path) }
            empty.text = "하위 폴더가 없어요.\n위 버튼으로 이 폴더에서 찍거나\n아래 버튼으로 하위 폴더를 만드세요."
        }

        findViewById<View>(R.id.addFolderButton).setOnClickListener { showCreateDialog() }
        findViewById<View>(R.id.refreshButton).setOnClickListener { load() }

        showFolders(SiteStore.cachedFolders(this, cacheKey), emptyMap())
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_PICK, pickTarget)
    }

    private fun pickFromGallery(folderPath: String) {
        pickTarget = folderPath
        pickPhotos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    // 상단 오른쪽 "👥 초대" 버튼: 이 현장 초대 링크 보내기
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, MENU_INVITE, 0, "👥 초대").setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == MENU_INVITE) {
            AppShare.shareInvite(this, site)
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun showFolders(list: List<String>, counts: Map<String, Int>) {
        names.clear(); names.addAll(list)
        subs.clear(); subs.putAll(counts)
        labels.clear()
        list.forEach { name ->
            val n = counts[name] ?: 0
            labels.add(if (n > 0) "📂  $name   › 하위 $n" else "📁  $name")
        }
        adapter.notifyDataSetChanged()
    }

    private fun load() {
        statusText.text = "☁ 폴더 목록을 불러오는 중…"
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { Api.folders(site.server, site.code, path) }
                SiteStore.cacheFolders(this@SiteActivity, cacheKey, result.folders)
                showFolders(result.folders, result.subs)
                statusText.text = if (path.isEmpty())
                    "☁ 폴더를 누르면 카메라가 열려요 (📂 = 하위 폴더 있음)"
                else
                    "🏗 ${site.name} › $path"
            } catch (e: ApiException) {
                statusText.text = "⚠ ${e.message}"
            } catch (e: Exception) {
                statusText.text = "⚠ 인터넷 연결이 없어 저장된 목록을 보여줘요. 찍은 사진은 연결되면 올라가요"
            }
        }
    }

    private fun showCreateDialog() {
        val input = EditText(this).apply {
            hint = if (path.isEmpty()) "예: 12 추가사진" else "예: 철근, 타설"
            isSingleLine = true
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val container = FrameLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        val where = if (path.isEmpty()) "" else "'${path.substringAfterLast('/')}' 안에 "
        val dialog = AlertDialog.Builder(this)
            .setTitle("${where}새 폴더 (모든 참여자에게 보여요)")
            .setView(container)
            .setPositiveButton("만들기", null)
            .setNegativeButton("취소", null)
            .create()

        dialog.setOnShowListener {
            val ok = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            ok.setOnClickListener {
                val name = input.text.toString().trim().replace(Regex("\\s+"), " ")
                Names.problem(name)?.let { input.error = it; return@setOnClickListener }
                if (names.any { it.equals(name, ignoreCase = true) }) {
                    input.error = "이미 있는 폴더예요"; return@setOnClickListener
                }
                ok.isEnabled = false
                lifecycleScope.launch {
                    try {
                        withContext(Dispatchers.IO) { Api.createFolder(site.server, site.code, name, path) }
                        dialog.dismiss()
                        load()
                        openCamera(full(name))
                    } catch (e: ApiException) {
                        input.error = e.message
                    } catch (e: Exception) {
                        input.error = "인터넷 연결을 확인하세요"
                    } finally {
                        ok.isEnabled = true
                    }
                }
            }
        }
        dialog.show()
    }

    private fun showMenu(position: Int, anchor: View) {
        val name = names.getOrNull(position) ?: return
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, 1, 0, "📷 이 폴더에서 찍기")
        popup.menu.add(0, 5, 0, "🖼 갤러리 사진 올리기")
        popup.menu.add(0, 2, 1, "📂 열기 · 하위 폴더 만들기")
        popup.menu.add(0, 3, 2, "🖼 앨범 보기 (모두가 올린 사진)")
        popup.menu.add(0, 4, 3, "🗑 폴더 삭제")
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> openCamera(full(name))
                2 -> openFolder(full(name))
                3 -> AlbumActivity.open(this, full(name), site)
                4 -> confirmDelete(full(name))
                5 -> pickFromGallery(full(name))
            }
            true
        }
        popup.show()
    }

    private fun confirmDelete(folderPath: String) {
        val leaf = folderPath.substringAfterLast('/')
        AlertDialog.Builder(this)
            .setTitle("'$leaf' 폴더 삭제")
            .setMessage("모든 참여자의 목록에서 지워져요.\n사진이나 하위 폴더가 있는 폴더는 지울 수 없어요 (다른 사람 사진 보호).")
            .setPositiveButton("삭제") { _, _ ->
                lifecycleScope.launch {
                    try {
                        withContext(Dispatchers.IO) { Api.deleteFolder(site.server, site.code, folderPath) }
                        Toast.makeText(this@SiteActivity, "'$leaf' 폴더를 삭제했어요", Toast.LENGTH_SHORT).show()
                        load()
                    } catch (e: ApiException) {
                        AlertDialog.Builder(this@SiteActivity)
                            .setTitle("삭제할 수 없어요")
                            .setMessage(e.message)
                            .setPositiveButton("확인", null)
                            .show()
                    } catch (e: Exception) {
                        Toast.makeText(this@SiteActivity, "인터넷 연결을 확인하세요", Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun openFolder(folderPath: String) = open(this, site.code, folderPath)

    private fun openCamera(folderPath: String) {
        startActivity(
            Intent(this, CameraActivity::class.java)
                .putExtra(CameraActivity.EXTRA_FOLDER, folderPath)
                .putExtra(CameraActivity.EXTRA_SITE_CODE, site.code)
        )
    }

    companion object {
        const val EXTRA_CODE = "code"
        const val EXTRA_PATH = "path"
        private const val MENU_INVITE = 100
        private const val KEY_PICK = "pick_target"

        fun open(ctx: Context, code: String, path: String = "") {
            ctx.startActivity(
                Intent(ctx, SiteActivity::class.java)
                    .putExtra(EXTRA_CODE, code)
                    .putExtra(EXTRA_PATH, path)
            )
        }
    }
}
