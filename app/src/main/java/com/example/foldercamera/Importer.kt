package com.example.foldercamera

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 폰에 이미 있는 사진(갤러리, 내 폴더)을 현장 클라우드 폴더로 올리기.
 * 사진을 이 앱의 현장 폴더(Pictures/현장/폴더)에 복사한 뒤 업로드 예약 → 인터넷이 끊겨도 나중에 올라감.
 */
object Importer {

    suspend fun importToSite(ctx: Context, sources: List<Uri>, site: Site, folder: String): Int =
        withContext(Dispatchers.IO) {
            val resolver = ctx.contentResolver
            val uploader = SiteStore.uploader(ctx)
            var count = 0
            sources.forEachIndexed { i, src ->
                try {
                    val type = resolver.getType(src) ?: "image/jpeg"
                    if (!type.startsWith("image/")) return@forEachIndexed
                    val original = Photos.displayName(ctx, src) ?: "IMG_${System.currentTimeMillis()}_$i"
                    val ext = when {
                        type.contains("png") -> ".png"
                        type.contains("heic") || type.contains("heif") -> ".heic"
                        type.contains("webp") -> ".webp"
                        else -> ".jpg"
                    }
                    val base = original.substringBeforeLast('.').replace(Regex("[\\\\/:*?\"<>|#%]"), "_").trim().take(60)
                    val prefix = if (uploader.isNotBlank() && !base.startsWith("${uploader}_")) "${uploader}_" else ""
                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, prefix + base + ext)
                        put(MediaStore.MediaColumns.MIME_TYPE, type)
                        put(MediaStore.MediaColumns.RELATIVE_PATH, Photos.relativePath(site, folder))
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    }
                    val dst = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return@forEachIndexed
                    try {
                        resolver.openInputStream(src)!!.use { input ->
                            resolver.openOutputStream(dst)!!.use { output -> input.copyTo(output) }
                        }
                    } catch (e: Exception) {
                        resolver.delete(dst, null, null)
                        return@forEachIndexed
                    }
                    resolver.update(dst, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                    UploadWorker.enqueue(ctx, dst, site, folder)
                    count++
                } catch (e: Exception) {
                    // 한 장 실패해도 나머지는 계속
                }
            }
            count
        }

    /** 고른 사진들을 올리고 결과 안내 */
    fun run(activity: AppCompatActivity, sources: List<Uri>, site: Site, folder: String, after: (() -> Unit)? = null) {
        if (sources.isEmpty()) { after?.invoke(); return }
        Toast.makeText(activity, "${sources.size}장 준비하는 중…", Toast.LENGTH_SHORT).show()
        activity.lifecycleScope.launch {
            val n = importToSite(activity, sources, site, folder)
            Toast.makeText(
                activity,
                if (n > 0) "☁ ${n}장을 '${folder.substringAfterLast('/')}' 폴더로 올리는 중이에요" else "올릴 수 있는 사진이 없어요",
                Toast.LENGTH_LONG
            ).show()
            after?.invoke()
        }
    }
}

/** 올릴 현장과 폴더 고르기 (하위 폴더로 들어가기 가능) */
object CloudFolderPicker {

    fun pick(activity: AppCompatActivity, onCancel: () -> Unit = {}, onPicked: (Site, String) -> Unit) {
        val sites = SiteStore.all(activity).sortedBy { it.name }
        when {
            sites.isEmpty() -> AlertDialog.Builder(activity)
                .setTitle("참여한 현장이 없어요")
                .setMessage("먼저 첫 화면의 [☁ 현장 참여]로 현장에 들어가 주세요.")
                .setPositiveButton("확인", null)
                .setOnDismissListener { onCancel() }
                .show()
            sites.size == 1 -> browse(activity, sites[0], "", onCancel, onPicked)
            else -> {
                var chosen = false
                AlertDialog.Builder(activity)
                    .setTitle("어느 현장에 올릴까요?")
                    .setItems(sites.map { "🏗  ${it.name}" }.toTypedArray()) { _, which ->
                        chosen = true
                        browse(activity, sites[which], "", onCancel, onPicked)
                    }
                    .setNegativeButton("취소", null)
                    .setOnDismissListener { if (!chosen) onCancel() }
                    .show()
            }
        }
    }

    private fun browse(activity: AppCompatActivity, site: Site, path: String, onCancel: () -> Unit, onPicked: (Site, String) -> Unit) {
        activity.lifecycleScope.launch {
            val result = try {
                withContext(Dispatchers.IO) { Api.folders(site.server, site.code, path) }
            } catch (e: Exception) {
                if (path.isEmpty()) FolderList(SiteStore.cachedFolders(activity, site.code), emptyMap())
                else {
                    Toast.makeText(activity, "인터넷 연결을 확인하세요", Toast.LENGTH_LONG).show()
                    onCancel(); return@launch
                }
            }
            val labels = mutableListOf<String>()
            val actions = mutableListOf<() -> Unit>()
            if (path.isNotEmpty()) {
                labels += "✅  '${path.substringAfterLast('/')}' 폴더에 올리기"
                actions += { onPicked(site, path) }
            }
            result.folders.forEach { name ->
                val full = if (path.isEmpty()) name else "$path/$name"
                val n = result.subs[name] ?: 0
                if (n > 0) {
                    labels += "📂  $name   ›"
                    actions += { browse(activity, site, full, onCancel, onPicked) }
                } else {
                    labels += "📁  $name"
                    actions += { onPicked(site, full) }
                }
            }
            if (labels.isEmpty()) {
                Toast.makeText(activity, "현장에 폴더가 없어요. 먼저 폴더를 만들어 주세요", Toast.LENGTH_LONG).show()
                onCancel(); return@launch
            }
            var chosen = false
            AlertDialog.Builder(activity)
                .setTitle(if (path.isEmpty()) "🏗 ${site.name} · 올릴 폴더" else "📂 $path")
                .setItems(labels.toTypedArray()) { _, which -> chosen = true; actions[which]() }
                .setNegativeButton("취소", null)
                .setOnDismissListener { if (!chosen) onCancel() }
                .show()
        }
    }
}
