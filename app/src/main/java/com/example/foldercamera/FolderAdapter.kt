package com.example.foldercamera

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter

/** 폴더 목록 한 줄: 이름 + 오른쪽 ⋮ 메뉴 버튼 */
class FolderAdapter(
    ctx: Context,
    items: List<String>,
    private val onMenu: (position: Int, anchor: View) -> Unit
) : ArrayAdapter<String>(ctx, R.layout.item_folder, R.id.label, items) {

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = super.getView(position, convertView, parent)
        view.findViewById<View>(R.id.menuButton).setOnClickListener { onMenu(position, it) }
        return view
    }
}
