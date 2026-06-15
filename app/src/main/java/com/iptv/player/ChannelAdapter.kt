package com.iptv.player

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load

class ChannelAdapter(
    private val onClick: (Channel) -> Unit
) : RecyclerView.Adapter<ChannelAdapter.VH>() {

    private var items: List<Channel> = emptyList()
    var videoId: String? = null
    var audioId: String? = null

    fun submit(list: List<Channel>) { items = list; notifyDataSetChanged() }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val row: LinearLayout = v.findViewById(R.id.row)
        val logo: ImageView = v.findViewById(R.id.logo)
        val name: TextView = v.findViewById(R.id.chName)
        val group: TextView = v.findViewById(R.id.chGroup)
        val tag: TextView = v.findViewById(R.id.tag)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_channel, parent, false)
        return VH(v)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val ch = items[position]
        h.name.text = ch.name
        h.group.text = ch.group
        if (ch.logo.isNotEmpty()) {
            h.logo.load(ch.logo) { crossfade(true) }
        } else {
            h.logo.setImageResource(android.R.drawable.ic_menu_slideshow)
        }

        val isV = ch.id == videoId
        val isA = ch.id == audioId
        h.row.isSelected = isV || isA

        when {
            isV && isA -> { h.tag.visibility = View.VISIBLE; h.tag.text = "VA"; h.tag.setBackgroundResource(R.drawable.bg_tag_va) }
            isV -> { h.tag.visibility = View.VISIBLE; h.tag.text = "V"; h.tag.setBackgroundResource(R.drawable.bg_tag_v) }
            isA -> { h.tag.visibility = View.VISIBLE; h.tag.text = "A"; h.tag.setBackgroundResource(R.drawable.bg_tag_a) }
            else -> h.tag.visibility = View.GONE
        }

        h.row.setOnClickListener { onClick(ch) }
    }
}
