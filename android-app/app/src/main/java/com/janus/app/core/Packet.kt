package com.janus.app.core

import com.google.gson.JsonObject

data class Packet(
    val type: String,
    val id: String,
    val timestamp: Long,
    val payload: JsonObject
)
