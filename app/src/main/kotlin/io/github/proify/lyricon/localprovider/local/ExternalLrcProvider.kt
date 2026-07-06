/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.localprovider.local

import io.github.proify.lrckit.EnhanceLrcParser
import io.github.proify.lyricon.localprovider.model.LyricsProvider
import io.github.proify.lyricon.localprovider.model.LyricsResult
import io.github.proify.lyricon.localprovider.util.ensureWordSpacing
import io.github.proify.lyricon.localprovider.util.TTMLParser
import io.github.proify.lyricon.localprovider.xposed.LocalProvider
import io.github.proify.lyricon.localprovider.xposed.PathManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class ExternalLrcProvider : LyricsProvider {
    override val id = "ExternalLrc"

    enum class LyricsFormat {
        TTML,           // 标准 TTML
        ENHANCED_LRC,   // 增强 LRC（含逐字时间轴）
        STANDARD_LRC    // 标准 LRC
    }

    private data class LyricCacheEntry(
        val filePath: String,
        val lastModified: Long,
        val result: LyricsResult,
        val title: String,
        val artist: String? = null,
        val format: LyricsFormat = LyricsFormat.STANDARD_LRC
    )

    private val cache = ConcurrentHashMap<String, LyricCacheEntry>()
    private val isCacheInitialized = AtomicBoolean(false)

    override suspend fun search(
        query: String?,
        trackName: String?,
        artistName: String?,
        albumName: String?,
        limit: Int
    ): List<LyricsResult> = emptyList()

    suspend fun searchByAudioFile(
        audioFilePath: String,
        trackName: String?,
        artistName: String?,
        albumName: String?
    ): LyricsResult? = withContext(Dispatchers.IO) {
        val audioFile = File(audioFilePath)
        if (!audioFile.exists()) return@withContext null

        val baseName = audioFile.nameWithoutExtension
        val dir = audioFile.parentFile ?: return@withContext null

        val lrcFile = File(dir, "$baseName.lrc")
        if (lrcFile.exists()) {
            parseLrcFileForced(lrcFile, trackName, artistName, albumName)?.let { return@withContext it }
        }
        return@withContext null
    }

    suspend fun searchByMetadata(
        trackName: String?,
        artistName: String?,
        albumName: String?
    ): LyricsResult? = withContext(Dispatchers.IO) {
        if (trackName.isNullOrBlank() || trackName.length < 3) return@withContext null

        ensureCacheInitialized()

        val cleanTitle = cleanString(trackName)
        val cleanArtist = cleanString(artistName ?: "")
        val exactKey = "$cleanTitle|$cleanArtist"
        val titleOnlyKey = "$cleanTitle|"

        // 精确匹配（标题+歌手）
        cache[exactKey]?.let {
            val file = File(it.filePath)
            if (file.exists() && file.lastModified() == it.lastModified) {
                return@withContext it.result
            } else {
                cache.remove(exactKey)
            }
        }

        // 标题匹配（无歌手），要求高相似度
        cache[titleOnlyKey]?.let { entry ->
            val file = File(entry.filePath)
            if (file.exists() && file.lastModified() == entry.lastModified) {
                val similarity = calculateTitleSimilarity(trackName, entry.title)
                if (similarity >= 0.8) {
                    return@withContext entry.result
                }
            } else {
                cache.remove(titleOnlyKey)
            }
        }

        // 遍历所有缓存，计算综合得分
        var bestMatch: LyricCacheEntry? = null
        var bestScore = 0.0

        for ((_, entry) in cache) {
            val titleSim = calculateTitleSimilarity(trackName, entry.title)
            val artistSim = if (artistName != null && entry.artist != null) {
                calculateArtistSimilarity(artistName, entry.artist)
            } else {
                0.0
            }
            val formatBonus = when (entry.format) {
                LyricsFormat.TTML -> 0.15
                LyricsFormat.ENHANCED_LRC -> 0.08
                LyricsFormat.STANDARD_LRC -> 0.0
            }
            val score = titleSim * 0.7 + artistSim * 0.3 + formatBonus
            if (score > bestScore && score >= 0.6) {
                bestScore = score
                bestMatch = entry
            }
        }

        bestMatch?.let {
            val file = File(it.filePath)
            if (file.exists() && file.lastModified() == it.lastModified) {
                return@withContext it.result
            } else {
                val keyToRemove = cache.entries.find { entry -> entry.value.filePath == it.filePath }?.key
                if (keyToRemove != null) cache.remove(keyToRemove)
            }
        }

        return@withContext null
    }

    private fun ensureCacheInitialized() {
        if (isCacheInitialized.get()) return
        CoroutineScope(Dispatchers.IO).launch {
            synchronized(this@ExternalLrcProvider) {
                if (isCacheInitialized.get()) return@launch
                val context = LocalProvider.appContext ?: return@launch
                val customPaths = PathManager.getPaths(context)

                for (customPath in customPaths) {
                    val customDir = File(customPath)
                    if (!customDir.isDirectory) continue

                    customDir.walkTopDown().maxDepth(3).forEach { file ->
                        if (file.isFile && file.extension.equals("lrc", ignoreCase = true)) {
                            val content = runCatching { file.readText() }.getOrNull() ?: return@forEach

                            val format = when {
                                TTMLParser.isTTML(content) -> LyricsFormat.TTML
                                isEnhancedLrc(content) -> LyricsFormat.ENHANCED_LRC
                                else -> LyricsFormat.STANDARD_LRC
                            }

                            val (result, title, artist) = if (format == LyricsFormat.TTML) {
                                val richLines = TTMLParser.parse(content)
                                if (richLines.isEmpty()) return@forEach
                                val spaced = richLines.ensureWordSpacing()
                                val fileName = file.nameWithoutExtension
                                Triple(
                                    LyricsResult(trackName = fileName, rich = spaced),
                                    fileName,
                                    null
                                )
                            } else {
                                val doc = EnhanceLrcParser.parse(content)
                                val fileTitle = doc.metadata["ti"]?.trim() ?: file.nameWithoutExtension
                                val fileArtist = doc.metadata["ar"]?.trim()
                                val rich = doc.lines.filter { line ->
                                    val text = line.text ?: ""
                                    !text.contains("作曲", ignoreCase = true) &&
                                            !text.contains("作词", ignoreCase = true) &&
                                            !text.contains("编曲", ignoreCase = true)
                                }
                                if (rich.isEmpty()) return@forEach
                                val spaced = rich.ensureWordSpacing()
                                Triple(
                                    LyricsResult(
                                        trackName = fileTitle,
                                        artistName = fileArtist,
                                        albumName = doc.metadata["al"],
                                        rich = spaced
                                    ),
                                    fileTitle,
                                    fileArtist
                                )
                            }

                            val cleanTitle = cleanString(title)
                            val cleanArtist = artist?.let { cleanString(it) } ?: ""
                            val exactKey = "$cleanTitle|$cleanArtist"
                            cache[exactKey] = LyricCacheEntry(
                                filePath = file.absolutePath,
                                lastModified = file.lastModified(),
                                result = result,
                                title = title,
                                artist = artist,
                                format = format
                            )
                            val titleOnlyKey = "$cleanTitle|"
                            if (!cache.containsKey(titleOnlyKey)) {
                                cache[titleOnlyKey] = cache[exactKey]!!
                            }
                        }
                    }
                }
                isCacheInitialized.set(true)
            }
        }
    }

    private fun isEnhancedLrc(content: String): Boolean {
        val wordTagRegex = Regex("""<(\d+)[:.](\d+)[:.]?(\d*)>""")
        return wordTagRegex.containsMatchIn(content)
    }

    private fun cleanString(s: String): String {
        return s.replace(Regex("[\\p{Punct}\\s]"), "").lowercase()
    }

    private fun calculateTitleSimilarity(expected: String, actual: String): Double {
        val cleanExpected = cleanString(expected)
        val cleanActual = cleanString(actual)

        if (cleanExpected == cleanActual) return 1.0
        if (cleanActual.contains(cleanExpected) || cleanExpected.contains(cleanActual)) {
            val lenDiff = Math.abs(cleanExpected.length - cleanActual.length).toDouble()
            val maxLen = Math.max(cleanExpected.length, cleanActual.length)
            return 0.9 - (lenDiff / maxLen) * 0.2
        }

        val expectedWords = cleanExpected.split(Regex("[^a-z0-9]+")).filter { it.isNotBlank() }.toSet()
        val actualWords = cleanActual.split(Regex("[^a-z0-9]+")).filter { it.isNotBlank() }.toSet()
        if (expectedWords.isEmpty() || actualWords.isEmpty()) return 0.0

        val intersection = expectedWords.intersect(actualWords).size
        val union = expectedWords.union(actualWords).size
        return intersection.toDouble() / union.toDouble()
    }

    private fun calculateArtistSimilarity(expected: String, actual: String): Double {
        val cleanExpected = cleanString(expected)
        val cleanActual = cleanString(actual)

        if (cleanExpected == cleanActual) return 1.0
        if (cleanActual.contains(cleanExpected) || cleanExpected.contains(cleanActual)) {
            return 0.8
        }

        val expectedWords = cleanExpected.split(Regex("[^a-z0-9]+")).filter { it.isNotBlank() }.toSet()
        val actualWords = cleanActual.split(Regex("[^a-z0-9]+")).filter { it.isNotBlank() }.toSet()
        if (expectedWords.isEmpty() || actualWords.isEmpty()) return 0.0

        val intersection = expectedWords.intersect(actualWords).size
        val union = expectedWords.union(actualWords).size
        return intersection.toDouble() / union.toDouble()
    }

    private suspend fun parseLrcFileForced(
        file: File,
        fallbackTitle: String?,
        fallbackArtist: String?,
        fallbackAlbum: String?
    ): LyricsResult? = withContext(Dispatchers.IO) {
        val content = runCatching { file.readText() }.getOrNull() ?: return@withContext null

        if (TTMLParser.isTTML(content)) {
            val rich = TTMLParser.parse(content)
            if (rich.isNotEmpty()) {
                return@withContext LyricsResult(
                    trackName = fallbackTitle ?: file.nameWithoutExtension,
                    artistName = fallbackArtist,
                    albumName = fallbackAlbum,
                    rich = rich.ensureWordSpacing()
                )
            }
            return@withContext null
        }

        val doc = EnhanceLrcParser.parse(content)
        val rich = doc.lines
        if (rich.isEmpty()) return@withContext null
        LyricsResult(
            trackName = fallbackTitle ?: doc.metadata["ti"],
            artistName = fallbackArtist ?: doc.metadata["ar"],
            albumName = fallbackAlbum,
            rich = rich.ensureWordSpacing()
        )
    }

    private suspend fun parseLrcFileWithMetadata(
        file: File,
        expectedTitle: String?,
        expectedArtist: String?,
        expectedAlbum: String?
    ): LyricsResult? = withContext(Dispatchers.IO) {
        val content = runCatching { file.readText() }.getOrNull() ?: return@withContext null

        if (TTMLParser.isTTML(content)) {
            val rich = TTMLParser.parse(content)
            if (rich.isNotEmpty()) {
                return@withContext LyricsResult(
                    trackName = expectedTitle ?: file.nameWithoutExtension,
                    artistName = expectedArtist,
                    albumName = expectedAlbum,
                    rich = rich.ensureWordSpacing()
                )
            }
            return@withContext null
        }

        val doc = EnhanceLrcParser.parse(content)
        val fileTitle = doc.metadata["ti"]?.trim()
        val fileArtist = doc.metadata["ar"]?.trim()
        if (expectedTitle != null && fileTitle != null && calculateTitleSimilarity(expectedTitle, fileTitle) < 0.7) return@withContext null
        if (expectedArtist != null && fileArtist != null && calculateArtistSimilarity(expectedArtist, fileArtist) < 0.5) return@withContext null
        val rich = doc.lines.ensureWordSpacing()
        LyricsResult(
            trackName = expectedTitle ?: fileTitle,
            artistName = expectedArtist ?: fileArtist,
            albumName = expectedAlbum,
            rich = rich
        )
    }
}