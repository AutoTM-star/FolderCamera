package com.example.foldercamera

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** 참여한 공유 현장 (초대 코드 + 중계 서버 주소) */
data class Site(val code: String, val name: String, val server: String)

object SiteStore {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("sites", Context.MODE_PRIVATE)

    fun all(ctx: Context): List<Site> {
        val arr = JSONArray(prefs(ctx).getString("list", "[]"))
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Site(o.getString("code"), o.getString("name"), o.getString("server"))
        }
    }

    private fun save(ctx: Context, sites: List<Site>) {
        val arr = JSONArray()
        sites.forEach {
            arr.put(JSONObject().put("code", it.code).put("name", it.name).put("server", it.server))
        }
        prefs(ctx).edit().putString("list", arr.toString()).apply()
    }

    fun find(ctx: Context, code: String): Site? = all(ctx).firstOrNull { it.code == code }

    fun add(ctx: Context, site: Site) = save(ctx, all(ctx).filter { it.code != site.code } + site)

    fun remove(ctx: Context, code: String) = save(ctx, all(ctx).filter { it.code != code })

    /** 마지막으로 쓴 서버 (코드만 입력했을 때 사용) */
    fun lastServer(ctx: Context): String? = all(ctx).lastOrNull()?.server

    fun cachedFolders(ctx: Context, code: String): List<String> {
        val arr = JSONArray(prefs(ctx).getString("folders_$code", "[]"))
        return (0 until arr.length()).map { arr.getString(it) }
    }

    fun cacheFolders(ctx: Context, code: String, folders: List<String>) {
        prefs(ctx).edit().putString("folders_$code", JSONArray(folders).toString()).apply()
    }

    /** 사진 파일 이름 앞에 붙는 촬영자 이름 */
    fun uploader(ctx: Context): String = prefs(ctx).getString("uploader", "") ?: ""

    fun setUploader(ctx: Context, name: String) =
        prefs(ctx).edit().putString("uploader", Names.clean(name)).apply()
}

object Names {
    private const val INVALID = "/\\:*?\"<>|#%"

    /** 폴더/현장 이름으로 쓸 수 없으면 오류 메시지, 괜찮으면 null */
    fun problem(name: String): String? = when {
        name.isBlank() -> "이름을 입력하세요"
        name.length > 80 -> "이름이 너무 길어요"
        name.startsWith(".") || name.endsWith(".") || name.any { it in INVALID } ->
            "/ \\ : * ? \" < > | # % 와 맨 앞·뒤의 . 은 쓸 수 없어요"
        else -> null
    }

    fun clean(name: String) = name.filter { it !in INVALID }.trim().take(30)
}
