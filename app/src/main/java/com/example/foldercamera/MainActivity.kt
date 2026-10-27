package com.example.foldercamera

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import android.widget.PopupMenu
import android.widget.Toast
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 첫 화면
 *  - 🏗 공유 현장: 초대 링크로 참여. 찍은 사진이 관리자 OneDrive로 자동 업로드
 *  - 📁 내 폴더: 이 폰에만 저장
 *  - 길게 누르면 목록에서 삭제 (찍은 사진은 그대로 남음)
 */
class MainActivity : AppCompatActivity() {

    private sealed class Entry {
        data class Shared(val site: Site) : Entry()
        data class Local(val name: String) : Entry()
    }

    private val prefs by lazy { getSharedPreferences("folders", MODE_PRIVATE) }
    private val entries = mutableListOf<Entry>()
    private val labels = mutableListOf<String>()
    private lateinit var adapter: FolderAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        title = "폴더 카메라"

        val list = findViewById<ListView>(R.id.folderList)
        list.emptyView = findViewById(R.id.emptyText)
        adapter = FolderAdapter(this, labels) { position, anchor -> showMenu(position, anchor) }
        list.adapter = adapter

        list.setOnItemClickListener { _, _, position, _ ->
            when (val e = entries[position]) {
                is Entry.Shared -> startActivity(
                    Intent(this, SiteActivity::class.java).putExtra(SiteActivity.EXTRA_CODE, e.site.code)
                )
                is Entry.Local -> openCamera(e.name)
            }
        }
        list.setOnItemLongClickListener { _, view, position, _ ->
            showMenu(position, view.findViewById<View>(R.id.menuButton) ?: view)
            true
        }

        findViewById<View>(R.id.addFolderButton).setOnClickListener { showCreateDialog() }
        findViewById<View>(R.id.joinButton).setOnClickListener { showJoinDialog(null) }

        handleInvite(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleInvite(intent)
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    // 상단 오른쪽 "📤 앱 공유" 버튼
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, MENU_SHARE_APP, 0, "📤 앱 공유").setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == MENU_SHARE_APP) {
            AppShare.showShareApp(this)
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    // ───────── 목록 ─────────

    private fun localFolders(): MutableSet<String> =
        prefs.getStringSet(KEY_FOLDERS, emptySet())!!.toMutableSet()

    private fun reload() {
        entries.clear()
        SiteStore.all(this).sortedBy { it.name }.forEach { entries.add(Entry.Shared(it)) }
        localFolders().sortedWith(String.CASE_INSENSITIVE_ORDER).forEach { entries.add(Entry.Local(it)) }
        labels.clear()
        entries.forEach {
            labels.add(
                when (it) {
                    is Entry.Shared -> "🏗  ${it.site.name}   ☁ 공유"
                    is Entry.Local -> "📁  ${it.name}"
                }
            )
        }
        adapter.notifyDataSetChanged()
    }

    // ───────── 내 폴더 (폰에만 저장) ─────────

    private fun showCreateDialog() {
        val input = EditText(this).apply {
            hint = "예: 여행, 영수증, 현장사진"
            isSingleLine = true
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("새 폴더 (이 폰에만 저장)")
            .setView(padded(input))
            .setPositiveButton("만들기", null)
            .setNegativeButton("취소", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = input.text.toString().trim()
                val folders = localFolders()
                Names.problem(name)?.let { input.error = it; return@setOnClickListener }
                if (folders.any { it.equals(name, ignoreCase = true) }) {
                    input.error = "이미 있는 폴더예요"; return@setOnClickListener
                }
                folders.add(name)
                prefs.edit().putStringSet(KEY_FOLDERS, folders).apply()
                dialog.dismiss()
                openCamera(name)
            }
        }
        dialog.show()
    }

    /** ⋮ 메뉴 (또는 길게 누르기) */
    private fun showMenu(position: Int, anchor: View) {
        val entry = entries.getOrNull(position) ?: return
        val popup = PopupMenu(this, anchor)
        when (entry) {
            is Entry.Local -> {
                popup.menu.add(0, 1, 0, "🖼 앨범 보기")
                popup.menu.add(0, 2, 1, "🗑 폴더 삭제")
            }
            is Entry.Shared -> {
                popup.menu.add(0, 3, 0, "🚪 현장 나가기")
            }
        }
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> AlbumActivity.open(this, (entry as Entry.Local).name, null)
                2 -> confirmDelete((entry as Entry.Local).name)
                3 -> confirmLeave((entry as Entry.Shared).site)
            }
            true
        }
        popup.show()
    }

    private fun confirmDelete(name: String) {
        lifecycleScope.launch {
            val path = Photos.relativePath(null, name)
            val count = withContext(Dispatchers.IO) { Photos.list(this@MainActivity, path).size }
            val removeFromList = {
                prefs.edit().putStringSet(KEY_FOLDERS, localFolders().apply { remove(name) }).apply()
                reload()
            }
            if (count == 0) {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("'$name' 폴더 삭제")
                    .setMessage("빈 폴더예요. 삭제할까요?")
                    .setPositiveButton("삭제") { _, _ -> removeFromList() }
                    .setNegativeButton("취소", null)
                    .show()
                return@launch
            }
            AlertDialog.Builder(this@MainActivity)
                .setTitle("'$name' 폴더 삭제")
                .setMessage("이 폴더에 사진이 ${count}장 있어요.\n\n• 사진까지 삭제: 사진도 폰에서 지워져요 (되돌릴 수 없음)\n• 목록에서만 삭제: 사진은 갤러리에 남아요")
                .setPositiveButton("사진까지 삭제") { _, _ ->
                    lifecycleScope.launch {
                        val deleted = withContext(Dispatchers.IO) { Photos.deleteAll(this@MainActivity, path) }
                        removeFromList()
                        Toast.makeText(this@MainActivity, "사진 ${deleted}장과 폴더를 삭제했어요", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNeutralButton("목록에서만 삭제") { _, _ -> removeFromList() }
                .setNegativeButton("취소", null)
                .show()
        }
    }

    private fun openCamera(folder: String) {
        startActivity(
            Intent(this, CameraActivity::class.java).putExtra(CameraActivity.EXTRA_FOLDER, folder)
        )
    }

    // ───────── 공유 현장 참여 ─────────

    /** 초대 페이지의 "앱에서 바로 참여하기" 버튼으로 열렸을 때 */
    private fun handleInvite(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme == "foldercamera" && data.host == "join") {
            val code = data.getQueryParameter("code")
            val server = data.getQueryParameter("server")
            if (!code.isNullOrBlank() && server != null && server.startsWith("https://")) {
                showJoinDialog("$server/join/$code")
            }
        }
        intent?.data = null
    }

    private fun showJoinDialog(prefill: String?) {
        val inviteInput = EditText(this).apply {
            hint = "초대 링크 또는 초대 코드"
            setText(prefill ?: "")
            minLines = 2
        }
        val nameInput = EditText(this).apply {
            hint = "내 이름 (사진 파일 이름 앞에 붙어요)"
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PERSON_NAME
            setText(SiteStore.uploader(this@MainActivity))
        }
        val guide = TextView(this).apply {
            text = "관리자에게 받은 초대 메시지를 통째로 붙여넣어도 돼요."
            textSize = 13f
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(guide)
            addView(inviteInput)
            addView(nameInput)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("현장 참여")
            .setView(padded(column))
            .setPositiveButton("참여", null)
            .setNegativeButton("취소", null)
            .create()

        dialog.setOnShowListener {
            val ok = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            ok.setOnClickListener {
                val parsed = Api.parseInvite(
                    inviteInput.text.toString(),
                    SiteStore.lastServer(this) ?: AppShare.DEFAULT_SERVER
                )
                if (parsed == null) {
                    inviteInput.error = "초대 링크 전체(https://…/join/코드)를 붙여넣어 주세요"
                    return@setOnClickListener
                }
                val (server, code) = parsed
                ok.isEnabled = false
                lifecycleScope.launch {
                    try {
                        val siteName = withContext(Dispatchers.IO) { Api.join(server, code) }
                        SiteStore.add(this@MainActivity, Site(code, siteName, server))
                        SiteStore.setUploader(this@MainActivity, nameInput.text.toString())
                        dialog.dismiss()
                        reload()
                        startActivity(
                            Intent(this@MainActivity, SiteActivity::class.java)
                                .putExtra(SiteActivity.EXTRA_CODE, code)
                        )
                    } catch (e: ApiException) {
                        inviteInput.error = e.message
                    } catch (e: Exception) {
                        inviteInput.error = "서버에 연결할 수 없어요. 인터넷과 링크를 확인하세요"
                    } finally {
                        ok.isEnabled = true
                    }
                }
            }
        }
        dialog.show()
    }

    private fun confirmLeave(site: Site) {
        AlertDialog.Builder(this)
            .setTitle("'${site.name}' 현장 나가기")
            .setMessage("이 폰의 목록에서만 빠져요.\n이미 올라간 사진과 폰에 저장된 사진은 그대로 남아요.")
            .setPositiveButton("나가기") { _, _ ->
                SiteStore.remove(this, site.code)
                reload()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun padded(view: View): View {
        val pad = (20 * resources.displayMetrics.density).toInt()
        return FrameLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(view)
        }
    }

    companion object {
        private const val KEY_FOLDERS = "folder_names"
        private const val MENU_SHARE_APP = 100
    }
}
