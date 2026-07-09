/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.localprovider.xposed

import android.net.Uri
import android.util.Log
import com.kyant.taglib.TagLib
import io.github.proify.lrckit.EnhanceLrcParser
import io.github.proify.lyricon.localprovider.model.LyricsResult
import io.github.proify.lyricon.localprovider.util.TTMLParser
import io.github.proify.lyricon.localprovider.util.ensureWordSpacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

object EmbeddedLyricsProvider {
    private const val TAG = "EmbeddedLyrics"
    private val lyricsCache = ConcurrentHashMap<String, LyricsResult>()
    private val lyricTagRegex by lazy { Regex("(?i)\\b(LYRICS|LYRICS\\d*|USLT)\\b") }

    suspend fun searchByAudioFile(
        audioFilePath: String,
        trackName: String?,
        artistName: String?,
        albumName: String?
    ): LyricsResult? = withContext(Dispatchers.IO) {
        lyricsCache[audioFilePath]?.let {
            return@withContext it
        }

        val audioFile = File(audioFilePath)
        if (!audioFile.exists()) {
            return@withContext null
        }

        val uri = Uri.fromFile(audioFile)
        val raw = extractLyricFromTag(uri) ?: run {
            return@withContext null
        }

        val result = if (TTMLParser.isTTML(raw)) {
            val richLines = TTMLParser.parse(raw)
            if (richLines.isNotEmpty()) {
                val spaced = richLines.ensureWordSpacing()
                LyricsResult(
                    trackName = trackName,
                    artistName = artistName,
                    albumName = albumName,
                    rich = spaced
                )
            } else {
                null
            }
        } else {
            val doc = EnhanceLrcParser.parse(raw)
            val richLines = doc.lines
            if (richLines.isEmpty()) {
                null
            } else {
                val spaced = richLines.ensureWordSpacing()
                LyricsResult(
                    trackName = trackName,
                    artistName = artistName,
                    albumName = albumName,
                    rich = spaced
                )
            }
        }

        if (result != null) {
            lyricsCache[audioFilePath] = result
        }
        result
    }

    private fun extractLyricFromTag(uri: Uri): String? {
        val context = LocalProvider.appContext ?: return null
        return try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                TagLib.getMetadata(pfd.detachFd())?.let { metadata ->
                    metadata.propertyMap.entries.firstOrNull { (key, _) ->
                        lyricTagRegex.matches(key)
                    }?.value?.firstOrNull()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "读取标签失败: ${e.message}", e)
            null
        }
    }
}