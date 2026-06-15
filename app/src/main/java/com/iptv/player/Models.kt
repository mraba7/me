package com.iptv.player

data class Channel(
    val id: String,
    val streamId: Int,
    val name: String,
    val group: String,
    val logo: String,
    var url: String
)

data class XtreamCreds(
    val server: String,
    val user: String,
    val pass: String,
    val fmt: String
)
