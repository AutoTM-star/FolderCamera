package com.example.foldercamera

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 공유 현장 화면: OneDrive에 있는 현장 폴더 목록 (모든 참여자가 같은 목록을 봄)
 *  - 폴더를 누르면 카메라 → 찍은 사진이 폰에 저장되고 OneDrive 같은 폴더로 자동 업로드
 */
class SiteActivity : AppCompatActivity() {

    private lateinit var site: Site
    private lateinit var statusText: TextView
    private val folders = mutableListOf<String>()
    private lateinit var adapter: ArrayAdapter<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        site = intent.getStringExtra(EXTRA_CODE)?.let { SiteStore.find(this, it) } ?: run { finish(); return }
        setContentView(R.layout.activity_site)
        title = "🏗 ${site.name}"

        statusText = findViewById(R.id.statusText)
        val list = findViewById<ListView>(R.id.folderList)
        list.emptyView = findViewById(R.id.emptyText)
        adapter = ArrayAdapter(this, R.layout.item_folder, folders)
        list.adapter = adapter
        list.setOnItemClickListener { _, _, position, _ -> openCamera(folders[position]) }

        findViewById<View>(R.id.addFolderButton).setOnClickListener { showCreateDialog() }
        findViewById<View>(R.id.refreshButton).setOnClickListener { load() }

        showFolders(SiteStore.cachedFolders(this, site.code))
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    private fun showFolders(list: List<String>) {
        folders.clear()
        folders.addAll(list)
        adapter.notifyDataSetChanged()
    }

    private fun load() {
        statusText.text = "☁ 폴더 목록을 불러오는 중…"
        lifecycleScope.launch {
            try {
                val list = withContext(Dispatchers.IO) { Api.folders(site.server, site.code) }
                SiteStore.cacheFolders(this@SiteActivity, site.code, list)
                showFolders(list)
                statusText.text = "☁ 여기서 찍은 사진은 OneDrive로 자동 업로드돼요"
            } catch (e: ApiException) {
                statusText.text = "⚠ ${e.message}"
            } catch (e: Exception) {
                statusText.text = "⚠ 인터넷 연결이 없어 저장된 목록을 보여줘요. 찍은 사진은 연결되면 올라가요"
            }
        }
    }

    private fun showCreateDialog() {
        val input = EditText(this).apply {
            hint = "예: 12 추가사진"
            isSingleLine = true
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val container = FrameLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("새 폴더 (모든 참여자에게 보여요)")
            .setView(container)
            .setPositiveButton("만들기", null)
            .setNegativeButton("취소", null)
            .create()

        dialog.setOnShowListener {
            val ok = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            ok.setOnClickListener {
                val name = input.text.toString().trim().replace(Regex("\\s+"), " ")
                Names.problem(name)?.let { input.error = it; return@setOnClickListener }
                if (folders.any { it.equals(name, ignoreCase = true) }) {
                    input.error = "이미 있는 폴더예요"; return@setOnClickListener
                }
                ok.isEnabled = false
                lifecycleScope.launch {
                    try {
                        withContext(Dispatchers.IO) { Api.createFolder(site.server, site.code, name) }
                        dialog.dismiss()
                        load()
                        openCamera(name)
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

    private fun openCamera(folder: String) {
        startActivity(
            Intent(this, CameraActivity::class.java)
                .putExtra(CameraActivity.EXTRA_FOLDER, folder)
                .putExtra(CameraActivity.EXTRA_SITE_CODE, site.code)
        )
    }

    companion object {
        const val EXTRA_CODE = "code"
    }
}
