package com.example.foldercamera

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 찍은 사진 1장을 OneDrive로 올리는 작업.
 * 인터넷이 없으면 기다렸다가, 실패하면 시간을 두고 자동으로 다시 시도함 (폰을 껐다 켜도 유지).
 */
class UploadWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val uri = inputData.getString(KEY_URI)?.let(Uri::parse) ?: return@withContext Result.failure()
        val server = inputData.getString(KEY_SERVER) ?: return@withContext Result.failure()
        val code = inputData.getString(KEY_CODE) ?: return@withContext Result.failure()
        val folder = inputData.getString(KEY_FOLDER) ?: return@withContext Result.failure()
        val resolver = applicationContext.contentResolver

        // 사진이 그새 지워졌으면 할 일 없음
        val fileName = try {
            resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null }
        } catch (e: SecurityException) {
            null
        } ?: return@withContext Result.success()

        val bytes = try {
            resolver.openInputStream(uri)?.use { it.readBytes() }
        } catch (e: FileNotFoundException) {
            null
        } ?: return@withContext Result.success()

        if (bytes.isEmpty()) {
            return@withContext if (runAttemptCount < 5) Result.retry() else Result.failure()
        }

        try {
            val uploadUrl = Api.uploadUrl(server, code, folder, fileName)
            Api.putFile(uploadUrl, bytes)
            Result.success()
        } catch (e: ApiException) {
            // 잘못된 코드/이름 등은 다시 해도 안 됨 → 포기. 서버 문제는 나중에 재시도
            if (e.status in 400..499 && e.status != 408 && e.status != 429) Result.failure() else Result.retry()
        } catch (e: IOException) {
            Result.retry()
        }
    }

    companion object {
        private const val KEY_URI = "uri"
        private const val KEY_SERVER = "server"
        private const val KEY_CODE = "code"
        private const val KEY_FOLDER = "folder"

        fun tag(code: String) = "upload-$code"

        fun enqueue(ctx: Context, uri: Uri, site: Site, folder: String) {
            val request = OneTimeWorkRequestBuilder<UploadWorker>()
                .setInputData(
                    workDataOf(
                        KEY_URI to uri.toString(),
                        KEY_SERVER to site.server,
                        KEY_CODE to site.code,
                        KEY_FOLDER to folder
                    )
                )
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .addTag(tag(site.code))
                .build()
            WorkManager.getInstance(ctx).enqueue(request)
        }
    }
}
