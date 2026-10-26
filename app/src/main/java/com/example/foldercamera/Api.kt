package com.example.foldercamera

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** 서버가 오류로 응답한 경우 (status = HTTP 상태 코드) */
class ApiException(val status: Int, message: String) : IOException(message)

/** 중계 서버와 통신 (모두 백그라운드 스레드에서 호출할 것) */
object Api {

    private fun request(method: String, url: String, body: JSONObject? = null): JSONObject {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            val json = runCatching { JSONObject(text) }.getOrElse { JSONObject() }
            if (status !in 200..299) {
                throw ApiException(status, json.optString("error").ifBlank { "서버 오류 ($status)" })
            }
            return json
        } finally {
            conn.disconnect()
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /** 초대 코드 확인 → 현장 이름 */
    fun join(server: String, code: String): String =
        request("POST", "$server/api/join", JSONObject().put("code", code)).getString("name")

    fun folders(server: String, code: String): List<String> {
        val arr = request("GET", "$server/api/folders?code=${enc(code)}").getJSONArray("folders")
        return (0 until arr.length()).map { arr.getString(it) }
    }

    fun createFolder(server: String, code: String, name: String) {
        request("POST", "$server/api/folders", JSONObject().put("code", code).put("name", name))
    }

    /** OneDrive 업로드 주소 받기 */
    fun uploadUrl(server: String, code: String, folder: String, fileName: String): String =
        request(
            "POST", "$server/api/upload",
            JSONObject().put("code", code).put("folder", folder).put("fileName", fileName)
        ).getString("uploadUrl")

    /** 사진을 OneDrive로 직접 전송 (업로드 주소에는 인증 헤더를 붙이지 않음) */
    fun putFile(uploadUrl: String, bytes: ByteArray) {
        val conn = URL(uploadUrl).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "PUT"
            conn.connectTimeout = 15_000
            conn.readTimeout = 120_000
            conn.doOutput = true
            conn.setFixedLengthStreamingMode(bytes.size)
            conn.setRequestProperty("Content-Range", "bytes 0-${bytes.size - 1}/${bytes.size}")
            conn.outputStream.use { it.write(bytes) }
            val status = conn.responseCode
            if (status !in 200..299) throw IOException("OneDrive 업로드 실패 ($status)")
        } finally {
            conn.disconnect()
        }
    }

    /**
     * 사용자가 붙여넣은 초대 링크/코드 해석 → (서버, 코드)
     * 예) https://xxx.netlify.app/join/AB12CD  또는  AB12CD (이미 참여한 서버가 있을 때)
     */
    fun parseInvite(input: String, fallbackServer: String?): Pair<String, String>? {
        val text = input.trim()
        Regex("""(https://[^\s/]+)/join/([A-Za-z0-9]{4,12})""").find(text)?.let {
            return it.groupValues[1] to it.groupValues[2].uppercase()
        }
        Regex("""(?:코드[:\s]*)?\b([A-Za-z0-9]{6})\b""").find(text)?.let {
            if (fallbackServer != null) return fallbackServer to it.groupValues[1].uppercase()
        }
        return null
    }
}
