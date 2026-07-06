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
        val startTime = System.currentTimeMillis()

        // 检查缓存
        lyricsCache[audioFilePath]?.let {
            val elapsed = System.currentTimeMillis() - startTime
            Log.d(TAG, "Cache hit for $audioFilePath, retrieved in ${elapsed}ms")
            return@withContext it
        }

        Log.d(TAG, "尝试读取内嵌歌词: $audioFilePath")
        val audioFile = File(audioFilePath)
        if (!audioFile.exists()) {
            Log.w(TAG, "音频文件不存在: $audioFilePath")
            return@withContext null
        }

        val uri = Uri.fromFile(audioFile)
        val raw = extractLyricFromTag(uri) ?: run {
            Log.d(TAG, "未找到内嵌歌词标签")
            return@withContext null
        }

        Log.d(TAG, "找到内嵌歌词，长度 ${raw.length} 字节")
        Log.d(TAG, "内容前200字符: ${raw.take(200)}")

        val parseStart = System.currentTimeMillis()
        val result = if (TTMLParser.isTTML(raw)) {
            Log.d(TAG, "检测到 TTML 格式")
            val richLines = TTMLParser.parse(raw)
            if (richLines.isNotEmpty()) {
                val parseEnd = System.currentTimeMillis()
                Log.d(TAG, "TTML 解析成功，行数 ${richLines.size}，耗时 ${parseEnd - parseStart}ms")
                val spaced = richLines.ensureWordSpacing()
                LyricsResult(
                    trackName = trackName,
                    artistName = artistName,
                    albumName = albumName,
                    rich = spaced
                )
            } else {
                Log.w(TAG, "TTML 解析后为空")
                null
            }
        } else {
            Log.d(TAG, "不是 TTML 格式，尝试 LRC 解析")
            val doc = EnhanceLrcParser.parse(raw)
            val richLines = doc.lines
            if (richLines.isEmpty()) {
                Log.w(TAG, "LRC 解析后为空")
                null
            } else {
                val parseEnd = System.currentTimeMillis()
                Log.d(TAG, "LRC 解析成功，行数 ${richLines.size}，耗时 ${parseEnd - parseStart}ms")
                val spaced = richLines.ensureWordSpacing()
                LyricsResult(
                    trackName = trackName,
                    artistName = artistName,
                    albumName = albumName,
                    rich = spaced
                )
            }
        }

        // 存入缓存
        if (result != null) {
            lyricsCache[audioFilePath] = result
            val totalElapsed = System.currentTimeMillis() - startTime
            Log.d(TAG, "歌词已缓存，总耗时 ${totalElapsed}ms")
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