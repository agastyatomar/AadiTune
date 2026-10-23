package com.zionhuang.innertube.utils

import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.pages.PlaylistPage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.security.MessageDigest

suspend fun Result<PlaylistPage>.completed() = runCatching {
    val page = getOrThrow()
    val songs = page.songs.toMutableList()
    var continuation = page.songsContinuation
    while (continuation != null) {
        val continuationPage = YouTube.playlistContinuation(continuation).getOrNull() ?: break
        songs += continuationPage.songs
        continuation = continuationPage.continuation
    }
    PlaylistPage(
        playlist = page.playlist,
        songs = songs,
        songsContinuation = null,
        continuation = page.continuation
    )
}

fun ByteArray.toHex(): String = joinToString(separator = "") { eachByte -> "%02x".format(eachByte) }

fun sha1(str: String): String = MessageDigest.getInstance("SHA-1").digest(str.toByteArray()).toHex()

/**
 * Build a cookie header string from app settings. Accepts BOTH formats users
 * actually paste:
 *  1. the classic header form "SID=..; SAPISID=..;"
 *  2. a Cookie-Editor extension JSON export (array of {domain,name,path,value})
 * JSON in, canonical header out - so login setup becomes copy/paste only.
 */
fun normalizeCookieString(raw: String): String {
    val trimmed = raw.trim()
    if (!trimmed.startsWith("[") && !trimmed.startsWith("{")) return trimmed

    val pairs = buildList {
        runCatching {
            val element = Json.parseToJsonElement(trimmed)
            when (element) {
                is JsonArray -> element.mapNotNull { entry ->
                    val obj = entry as? JsonObject ?: return@mapNotNull null
                    val name = (obj["name"] as? JsonPrimitive)?.content ?: return@mapNotNull null
                    val value = (obj["value"] as? JsonPrimitive)?.content ?: ""
                    name to value
                }

                is JsonObject -> {
                    val name = (element["name"] as? JsonPrimitive)?.content
                    val value = (element["value"] as? JsonPrimitive)?.content
                    if (name != null) listOf(name to (value ?: "")) else emptyList()
                }

                else -> emptyList()
            }
        }.getOrNull()?.let { addAll(it) }
    }
    if (pairs.isEmpty()) return trimmed
    return pairs.joinToString("; ") { (k, v) -> "$k=$v" }
}

fun parseCookieString(cookie: String): Map<String, String> =
    normalizeCookieString(cookie).split("; ")
        .filter { it.isNotEmpty() }
        .associate {
            val (key, value) = it.split("=")
            key to value
        }

fun String.parseTime(): Int? {
    try {
        val parts = split(":").map { it.toInt() }
        if (parts.size == 2) {
            return parts[0] * 60 + parts[1]
        }
        if (parts.size == 3) {
            return parts[0] * 3600 + parts[1] * 60 + parts[2]
        }
    } catch (e: Exception) {
        return null
    }
    return null
}
