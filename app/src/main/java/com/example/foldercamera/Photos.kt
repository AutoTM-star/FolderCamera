package com.example.foldercamera

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore

/** 폴더별 사진 찾기/지우기 (이 앱으로 찍은 사진은 권한 없이 볼 수 있음) */
object Photos {

    /** 폰 안의 저장 위치: Pictures/폴더/  또는  Pictures/현장/폴더/ */
    fun relativePath(site: Site?, folder: String): String =
        if (site != null) "${Environment.DIRECTORY_PICTURES}/${site.name}/$folder/"
        else "${Environment.DIRECTORY_PICTURES}/$folder/"

    /** 최신 사진이 먼저 */
    fun list(ctx: Context, relativePath: String): List<Uri> {
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val result = mutableListOf<Uri>()
        ctx.contentResolver.query(
            collection,
            arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.RELATIVE_PATH} = ?",
            arrayOf(relativePath),
            "${MediaStore.MediaColumns.DATE_ADDED} DESC, ${MediaStore.MediaColumns._ID} DESC"
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                result.add(ContentUris.withAppendedId(collection, cursor.getLong(0)))
            }
        }
        return result
    }

    fun delete(ctx: Context, uri: Uri): Boolean =
        runCatching { ctx.contentResolver.delete(uri, null, null) > 0 }.getOrDefault(false)

    /** 폴더 안 사진 모두 삭제 → 지운 장수 */
    fun deleteAll(ctx: Context, relativePath: String): Int =
        list(ctx, relativePath).count { delete(ctx, it) }

    fun displayName(ctx: Context, uri: Uri): String? =
        runCatching {
            ctx.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null }
        }.getOrNull()
}
