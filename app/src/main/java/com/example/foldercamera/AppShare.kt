package com.example.foldercamera

import android.content.Intent
import android.graphics.Typeface
import android.view.Gravity
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 앱 공유 + 현장 초대를 한 번에 보내기
 *  1) 함께 보낼 현장 고르기 (참여한 현장이 있을 때)
 *  2) 보내는 방법 고르기: 링크(카톡·문자) / 앱 파일(Quick Share)
 */
object AppShare {

    /** 중계 서버 (설치 페이지, 코드만 입력했을 때 사용) */
    const val DEFAULT_SERVER = "https://darling-halva-62f5ee.netlify.app"

    /** 첫 화면 "📤 앱 공유": 함께 보낼 현장부터 고르기 */
    fun showShareApp(activity: AppCompatActivity) {
        val sites = SiteStore.all(activity).sortedBy { it.name }
        if (sites.isEmpty()) {
            showMethods(activity, null)
            return
        }
        val labels = sites.map { "🏗 ${it.name} 현장 초대 + 앱 설치" } + "📷 앱만 보내기 (현장 초대 없이)"
        AlertDialog.Builder(activity)
            .setTitle("어떤 현장에 초대할까요?")
            .setItems(labels.toTypedArray()) { _, which ->
                showMethods(activity, sites.getOrNull(which))
            }
            .setNegativeButton("닫기", null)
            .show()
    }

    /** 현장 화면 "👥 초대": 이 현장으로 바로 */
    fun shareInvite(activity: AppCompatActivity, site: Site) = showMethods(activity, site)

    private fun showMethods(activity: AppCompatActivity, site: Site?) {
        val title = site?.let { "📤 ${it.name} 초대 보내기" } ?: "📤 폴더 카메라 공유"
        AlertDialog.Builder(activity)
            .setTitle(title)
            .setItems(
                arrayOf(
                    "💬 링크 보내기 (카톡·문자) — 앱 설치 + 현장 참여",
                    "📲 옆 사람 폰에 앱 파일 바로 보내기 (Quick Share)"
                )
            ) { _, which ->
                when (which) {
                    0 -> shareText(activity, linkMessage(activity, site))
                    1 -> shareApkFile(activity, site)
                }
            }
            .setNegativeButton("닫기", null)
            .show()
    }

    /** 초대 페이지 하나로 "앱 설치 → 현장 참여"까지 안내됨 */
    private fun linkMessage(activity: AppCompatActivity, site: Site?): String {
        if (site == null) {
            val server = SiteStore.lastServer(activity) ?: DEFAULT_SERVER
            return "[폴더 카메라] 현장 사진 카메라 앱\n" +
                "아래 링크를 눌러 설치하세요. (카톡에서 누르면 Chrome으로 열려요)\n" +
                "$server/app"
        }
        return "[폴더 카메라] ${site.name} 현장 사진 공유\n" +
            "아래 링크 하나로 ① 앱 설치 ② 현장 참여까지 할 수 있어요. (카톡에서 누르면 Chrome으로 열려요)\n" +
            "${site.server}/join/${site.code}\n" +
            "(이미 앱이 있으면: 앱 → 현장 참여 → 코드 ${site.code})"
    }

    private fun shareText(activity: AppCompatActivity, text: String) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        activity.startActivity(Intent.createChooser(send, "보낼 곳 선택"))
    }

    /** 설치된 앱 파일(APK)을 그대로 보내기. 현장이 있으면 받은 사람이 입력할 초대 코드를 크게 보여줌 */
    private fun shareApkFile(activity: AppCompatActivity, site: Site?) {
        activity.lifecycleScope.launch {
            try {
                val file = withContext(Dispatchers.IO) {
                    val dir = File(activity.cacheDir, "share").apply { mkdirs() }
                    File(activity.applicationInfo.sourceDir).copyTo(File(dir, "FolderCamera.apk"), overwrite = true)
                }
                val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.files", file)
                val send = Intent(Intent.ACTION_SEND)
                    .setType("application/vnd.android.package-archive")
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                if (site != null) send.putExtra(Intent.EXTRA_TEXT, linkMessage(activity, site))

                val launch = { activity.startActivity(Intent.createChooser(send, "Quick Share 또는 블루투스를 고르세요")) }
                if (site == null) launch() else showCodeThen(activity, site, launch)
            } catch (e: Exception) {
                Toast.makeText(activity, "앱 파일을 준비하지 못했어요", Toast.LENGTH_LONG).show()
            }
        }
    }

    /** 앱 파일만으로는 현장 정보가 안 가므로, 받은 사람에게 불러줄 코드를 먼저 보여줌 */
    private fun showCodeThen(activity: AppCompatActivity, site: Site, next: () -> Unit) {
        val pad = (20 * activity.resources.displayMetrics.density).toInt()
        val codeView = TextView(activity).apply {
            text = site.code
            textSize = 36f
            letterSpacing = 0.2f
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
            setPadding(pad, pad, pad, pad / 2)
        }
        AlertDialog.Builder(activity)
            .setTitle("${site.name} 초대 코드")
            .setMessage("받은 사람은 앱을 설치한 뒤\n[☁ 현장 참여]에 이 코드를 입력하면 돼요.")
            .setView(codeView)
            .setPositiveButton("앱 파일 보내기") { _, _ -> next() }
            .setNegativeButton("취소", null)
            .show()
    }
}
