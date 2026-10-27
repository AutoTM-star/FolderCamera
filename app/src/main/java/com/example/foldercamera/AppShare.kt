package com.example.foldercamera

import android.content.Intent
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** 앱 공유 / 현장 초대 보내기 */
object AppShare {

    /** 중계 서버 (설치 페이지, 코드만 입력했을 때 사용) */
    const val DEFAULT_SERVER = "https://darling-halva-62f5ee.netlify.app"

    fun installUrl(server: String) = "$server/app"

    /** 앱 공유 방법 고르기 */
    fun showShareApp(activity: AppCompatActivity) {
        val server = SiteStore.lastServer(activity) ?: DEFAULT_SERVER
        AlertDialog.Builder(activity)
            .setTitle("📤 폴더 카메라 공유")
            .setItems(
                arrayOf(
                    "💬 설치 링크 보내기 (카톡·문자)",
                    "📲 가까운 폰에 앱 파일 바로 보내기 (Quick Share)"
                )
            ) { _, which ->
                when (which) {
                    0 -> shareText(
                        activity,
                        "[폴더 카메라] 현장 사진 카메라 앱\n" +
                            "아래 링크를 눌러 설치하세요. (카톡에서 누르면 Chrome으로 열려요)\n" +
                            installUrl(server)
                    )
                    1 -> shareApkFile(activity)
                }
            }
            .setNegativeButton("닫기", null)
            .show()
    }

    /** 현장 초대 메시지 보내기 */
    fun shareInvite(activity: AppCompatActivity, site: Site) {
        shareText(
            activity,
            "[폴더 카메라] ${site.name} 현장 사진 공유\n" +
                "아래 링크를 눌러 앱을 설치하고 현장에 참여하세요. (카톡에서 누르면 Chrome으로 열려요)\n" +
                "${site.server}/join/${site.code}\n(초대 코드: ${site.code})"
        )
    }

    private fun shareText(activity: AppCompatActivity, text: String) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        activity.startActivity(Intent.createChooser(send, "보낼 곳 선택"))
    }

    /** 설치된 앱 파일(APK)을 그대로 보내기 — Quick Share/블루투스로 받으면 바로 설치 가능 */
    private fun shareApkFile(activity: AppCompatActivity) {
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
                activity.startActivity(Intent.createChooser(send, "Quick Share 또는 블루투스를 고르세요"))
            } catch (e: Exception) {
                Toast.makeText(activity, "앱 파일을 준비하지 못했어요", Toast.LENGTH_LONG).show()
            }
        }
    }
}
