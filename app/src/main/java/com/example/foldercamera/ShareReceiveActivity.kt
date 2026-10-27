package com.example.foldercamera

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.IntentCompat

/**
 * 갤러리 등에서 [공유 → 폴더 카메라] 로 사진을 보내면 열리는 화면.
 * 현장·폴더를 고르면 그 폴더로 올림.
 */
class ShareReceiveActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return   // 화면 회전 등으로 다시 만들어진 경우 중복 실행 방지

        val uris: List<Uri> = when (intent?.action) {
            Intent.ACTION_SEND ->
                listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE ->
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java) ?: emptyList()
            else -> emptyList()
        }
        if (uris.isEmpty()) { finish(); return }

        CloudFolderPicker.pick(this, onCancel = { finish() }) { site, folder ->
            Importer.run(this, uris, site, folder) { finish() }
        }
    }
}
