package com.example.foldercamera

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ListView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/**
 * 첫 화면: 폴더 목록.
 *  - 폴더를 누르면 카메라 화면으로 이동
 *  - "새 폴더 만들기"로 폴더 생성 (만들면 바로 카메라로 이동)
 *  - 폴더를 길게 누르면 목록에서 삭제 (찍은 사진은 그대로 남음)
 */
class MainActivity : AppCompatActivity() {

    private val prefs by lazy { getSharedPreferences("folders", MODE_PRIVATE) }
    private val folders = mutableListOf<String>()
    private lateinit var adapter: ArrayAdapter<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        title = "폴더 선택"

        val list = findViewById<ListView>(R.id.folderList)
        list.emptyView = findViewById(R.id.emptyText)
        adapter = ArrayAdapter(this, R.layout.item_folder, folders)
        list.adapter = adapter

        list.setOnItemClickListener { _, _, position, _ ->
            openCamera(folders[position])
        }
        list.setOnItemLongClickListener { _, _, position, _ ->
            confirmRemove(folders[position])
            true
        }

        findViewById<View>(R.id.addFolderButton).setOnClickListener { showCreateDialog() }

        loadFolders()
    }

    private fun loadFolders() {
        folders.clear()
        folders.addAll(
            prefs.getStringSet(KEY_FOLDERS, emptySet())!!
                .sortedWith(String.CASE_INSENSITIVE_ORDER)
        )
        adapter.notifyDataSetChanged()
    }

    private fun saveFolders() {
        prefs.edit().putStringSet(KEY_FOLDERS, folders.toSet()).apply()
    }

    private fun showCreateDialog() {
        val input = EditText(this).apply {
            hint = "예: 여행, 영수증, 현장사진"
            isSingleLine = true
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val container = FrameLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("새 폴더")
            .setView(container)
            .setPositiveButton("만들기", null)
            .setNegativeButton("취소", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = input.text.toString().trim()
                when {
                    name.isEmpty() ->
                        input.error = "폴더 이름을 입력하세요"
                    name.startsWith(".") || name.any { it in INVALID_CHARS } ->
                        input.error = "/ \\ : * ? \" < > | 와 맨 앞의 . 은 쓸 수 없어요"
                    folders.any { it.equals(name, ignoreCase = true) } ->
                        input.error = "이미 있는 폴더예요"
                    else -> {
                        folders.add(name)
                        saveFolders()
                        loadFolders()
                        dialog.dismiss()
                        openCamera(name)
                    }
                }
            }
        }
        dialog.show()
    }

    private fun confirmRemove(name: String) {
        AlertDialog.Builder(this)
            .setTitle("'$name' 목록에서 삭제")
            .setMessage("목록에서만 지워져요.\n이미 찍은 사진은 갤러리의 '$name' 폴더에 그대로 남아요.")
            .setPositiveButton("삭제") { _, _ ->
                folders.remove(name)
                saveFolders()
                loadFolders()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun openCamera(folder: String) {
        startActivity(
            Intent(this, CameraActivity::class.java)
                .putExtra(CameraActivity.EXTRA_FOLDER, folder)
        )
    }

    companion object {
        private const val KEY_FOLDERS = "folder_names"
        private const val INVALID_CHARS = "/\\:*?\"<>|"
    }
}
