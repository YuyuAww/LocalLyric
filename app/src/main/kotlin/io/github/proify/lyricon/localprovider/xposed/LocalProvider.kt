/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.localprovider.xposed

import android.content.Context
import android.media.MediaMetadata
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import com.highcapable.yukihookapi.hook.log.YLog
import com.kyant.taglib.TagLib
import io.github.proify.lrckit.EnhanceLrcParser
import io.github.proify.lyricon.localprovider.model.ProviderLyrics
import io.github.proify.lyricon.localprovider.model.LyricsResult
import io.github.proify.lyricon.localprovider.util.ensureWordSpacing
import io.github.proify.lyricon.localprovider.util.TTMLParser
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.provider.ConnectionListener
import io.github.proify.lyricon.provider.LyriconFactory
import io.github.proify.lyricon.provider.LyriconProvider
import java.io.File

object LocalProvider : YukiBaseHooker(), DownloadCallback {
    private const val TAG = "LocalProvider"
    private const val POWERAMP_PACKAGE = "com.maxmpz.audioplayer"

    private val lyricTagRegex by lazy { Regex("(?i)\\b(LYRICS|LYRICS\\d*|USLT)\\b") }

    private var provider: LyriconProvider? = null
    private var lastMediaSignature: String? = null
    private var lastDuration: Long = 0L

    override fun onHook() {
        YLog.debug(tag = TAG, msg = "========== LocalProvider 已注入，进程名=$processName ==========")

        onAppLifecycle {
            onCreate {
                initProvider()
                hookMediaSession()
            }
            onTerminate { release() }
        }
    }

    private fun initProvider() {
        val context = appContext ?: return

        val storageRoot = Environment.getExternalStorageDirectory()?.absolutePath ?: "/storage/emulated/0"
        val defaultDirs = listOf(
            "$storageRoot/Music",
            "$storageRoot/Download"
        )
        for (dir in defaultDirs) {
            val dirFile = File(dir)
            if (!dirFile.exists()) {
                dirFile.mkdirs()
            }
            if (!PathManager.getPaths(context).contains(dir)) {
                PathManager.addPath(context, dir)
            }
        }

        provider = LyriconFactory.createProvider(
            context = context,
            providerPackageName = Constants.PROVIDER_PACKAGE_NAME,
            playerPackageName = context.packageName,
            processName = processName
        ).apply {
            // 启用自动同步，避免中心服务重启后歌词状态丢失
            autoSync = true
            // 监听连接状态，便于重连后同步及超时提示
            service.addConnectionListener(object : ConnectionListener {
                override fun onConnected(provider: LyriconProvider) {
                    YLog.info(tag = TAG, msg = "已连接 Lyricon 中心服务")
                }
                override fun onReconnected(provider: LyriconProvider) {
                    YLog.info(tag = TAG, msg = "已重新连接 Lyricon 中心服务")
                }
                override fun onDisconnected(provider: LyriconProvider) {
                    YLog.warn(tag = TAG, msg = "与 Lyricon 中心服务连接断开")
                }
                override fun onConnectTimeout(provider: LyriconProvider) {
                    YLog.warn(tag = TAG, msg = "连接 Lyricon 中心服务超时，请检查 Lyricon/LSPosed 状态")
                }
            })
            register()
            // 启用翻译和罗马音显示（符合 Lyricon 标准：展示需 Provider 主动开启）
            player.setDisplayTranslation(true)
            player.setDisplayRoma(true)
        }
    }

    private fun hookMediaSession() {
        "android.media.session.MediaSession".toClass().resolve().apply {
            firstMethod {
                name = "setPlaybackState"
                parameters(PlaybackState::class.java)
            }.hook {
                after {
                    val state = args[0] as? PlaybackState ?: return@after
                    provider?.player?.setPlaybackState(state)
                }
            }

            firstMethod {
                name = "setMetadata"
                parameters("android.media.MediaMetadata")
            }.hook {
                after {
                    val metadata = args[0] as? MediaMetadata ?: return@after
                    handleMetadata(metadata)
                }
            }
        }
    }

    private fun handleMetadata(metadata: MediaMetadata) {
        // 如果是 PowerAmp，则跳过，由 PowerAmp Hooker 专门处理
        if (processName == POWERAMP_PACKAGE) {
            return
        }

        val id = metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)
        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
        val album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM)
        val duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)

        // 1. 常规路径解析
        var resolvedPath = resolveAudioPath(metadata)

        // 2. 如果失败，尝试 MediaStore 精确匹配
        if (resolvedPath == null && !title.isNullOrBlank()) {
            resolvedPath = getPathFromMediaStoreExact(title, artist)
        }

        // 3. 如果仍失败，尝试模糊匹配
        if (resolvedPath == null && !title.isNullOrBlank()) {
            resolvedPath = getPathFromMediaStoreFuzzy(title, artist)
        }

        // 4. 最后尝试仅根据标题匹配
        if (resolvedPath == null && !title.isNullOrBlank()) {
            resolvedPath = getPathFromMediaStoreByTitleOnly(title)
        }

        DownloadManager.setCurrentAudioPath(resolvedPath)

        val signature = calculateSignature(id, title, artist, album)
        if (signature == lastMediaSignature) return
        lastMediaSignature = signature
        lastDuration = duration

        // 先发送基础歌曲信息（含真实时长）
        provider?.player?.setSong(Song(name = title, artist = artist, duration = duration))
        // 同步当前播放位置（符合 Lyricon 标准：setSong 后应同步进度）
        provider?.player?.setPosition(0)

        // 如果成功解析到路径，优先尝试读取内嵌歌词
        if (resolvedPath != null) {
            val embeddedLyrics = tryLoadEmbeddedLyrics(resolvedPath, title, artist)
            if (embeddedLyrics != null && embeddedLyrics.isNotEmpty()) {
                val song = Song(
                    id = id,
                    name = title,
                    artist = artist,
                    duration = duration,
                    lyrics = embeddedLyrics
                )
                provider?.player?.setSong(song)
                // 同步播放位置（符合 Lyricon 标准：setSong 后应同步进度）
                provider?.player?.setPosition(0)
                YLog.info(tag = TAG, msg = "成功加载内嵌歌词: $title")
                return
            }
        }

        // 启动本地歌词搜索（外部 .lrc 文件）
        DownloadManager.cancel()
        DownloadManager.search(this@LocalProvider) {
            trackName = title
            artistName = artist
            albumName = album
            enableLocalSearch = true
            audioFilePath = resolvedPath
        }
    }

    private fun resolveAudioPath(metadata: MediaMetadata): String? {
        val mediaUriString = metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_URI)
        val filePath = metadata.getString("filePath")

        return when {
            !mediaUriString.isNullOrBlank() -> {
                val uri = Uri.parse(mediaUriString)
                when (uri.scheme) {
                    "file" -> uri.path
                    "content" -> getPathFromContentUri(uri)
                    else -> null
                }
            }
            !filePath.isNullOrBlank() -> filePath
            else -> null
        }
    }

    private fun getPathFromContentUri(uri: Uri): String? {
        val projection = arrayOf(MediaStore.Audio.Media.DATA)
        appContext?.contentResolver?.query(uri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val columnIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
                return cursor.getString(columnIndex)
            }
        }
        return null
    }

    private fun getPathFromMediaStoreExact(title: String?, artist: String?): String? {
        if (title.isNullOrBlank()) return null
        val projection = arrayOf(MediaStore.Audio.Media.DATA)
        val selection = StringBuilder()
        val selectionArgs = mutableListOf<String>()
        selection.append("${MediaStore.Audio.Media.TITLE} = ?")
        selectionArgs.add(title)
        if (!artist.isNullOrBlank()) {
            selection.append(" AND ${MediaStore.Audio.Media.ARTIST} = ?")
            selectionArgs.add(artist)
        }
        appContext?.contentResolver?.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection.toString(),
            selectionArgs.toTypedArray(),
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val columnIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
                return cursor.getString(columnIndex)
            }
        }
        return null
    }

    private fun getPathFromMediaStoreFuzzy(title: String?, artist: String?): String? {
        if (title.isNullOrBlank()) return null
        val projection = arrayOf(MediaStore.Audio.Media.DATA)

        // 提取主要部分：去除括号及内容、标点，合并空格
        val mainTitle = extractMainPart(title)
        val mainArtist = if (!artist.isNullOrBlank()) extractMainPart(artist) else null

        val selection = StringBuilder()
        val selectionArgs = mutableListOf<String>()

        selection.append("(")
        selection.append("${MediaStore.Audio.Media.TITLE} LIKE ?")
        selectionArgs.add("%$mainTitle%")

        if (mainArtist != null) {
            selection.append(" OR ${MediaStore.Audio.Media.ARTIST} LIKE ?")
            selectionArgs.add("%$mainArtist%")
        }
        selection.append(")")

        val fileNamePattern = mainTitle.replace(" ", "%")
        selection.append(" OR ${MediaStore.Audio.Media.DATA} LIKE ?")
        selectionArgs.add("%$fileNamePattern%")

        appContext?.contentResolver?.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection.toString(),
            selectionArgs.toTypedArray(),
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val columnIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
                return cursor.getString(columnIndex)
            }
        }
        return null
    }

    private fun extractMainPart(input: String): String {
        var result = input.replace(Regex("[\\(（][^）\\)]*[\\)）]"), "")
        result = result.replace(Regex("[\\p{Punct}]"), "")
        result = result.replace(Regex("\\s+"), " ")
        return result.trim()
    }

    private fun getPathFromMediaStoreByTitleOnly(title: String): String? {
        val projection = arrayOf(MediaStore.Audio.Media.DATA)
        val selection = "${MediaStore.Audio.Media.TITLE} LIKE ?"
        val selectionArgs = arrayOf("%$title%")

        appContext?.contentResolver?.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val columnIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
                return cursor.getString(columnIndex)
            }
        }
        return null
    }

    private fun tryLoadEmbeddedLyrics(filePath: String, title: String?, artist: String?): List<io.github.proify.lyricon.lyric.model.RichLyricLine>? {
        val audioFile = File(filePath)
        if (!audioFile.exists()) {
            return null
        }

        val uri = Uri.fromFile(audioFile)
        return try {
            appContext?.contentResolver?.openFileDescriptor(uri, "r")?.use { pfd ->
                TagLib.getMetadata(pfd.dup().detachFd())?.let { metadata ->
                    val entry = metadata.propertyMap.entries.firstOrNull { (key, _) ->
                        lyricTagRegex.matches(key)
                    }
                    if (entry == null) {
                        return@let null
                    }
                    val raw = entry.value.firstOrNull()
                    YLog.info(tag = TAG, msg = "找到内嵌歌词，长度=${raw?.length}")

                    val lines = if (TTMLParser.isTTML(raw ?: "")) {
                        val ttmlLines = TTMLParser.parse(raw!!)
                        ttmlLines.ensureWordSpacing()
                    } else {
                        val doc = EnhanceLrcParser.parse(raw)
                        val lrcLines = doc.lines.filter { !it.text.isNullOrBlank() }
                        lrcLines.ensureWordSpacing()
                    }
                    lines
                }
            }
        } catch (e: Exception) {
            YLog.error(tag = TAG, msg = "读取内嵌歌词失败", e = e)
            null
        }
    }

    private fun calculateSignature(vararg data: String?): String {
        return data.joinToString("") { it?.hashCode()?.toString() ?: "0" }.hashCode().toString()
    }

    override fun onDownloadFinished(response: List<ProviderLyrics>) {
        val newSong = response.firstOrNull()?.lyrics?.toSong(lastDuration)?.ensureWordSpacing()
        if (newSong != null && newSong.lyrics?.isNotEmpty() == true) {
            provider?.player?.setSong(newSong)
            // 同步播放位置（符合 Lyricon 标准：setSong 后应同步进度）
            provider?.player?.setPosition(0)
        }
    }

    override fun onDownloadFailed(e: Exception) {
        YLog.error(tag = TAG, msg = "歌词搜索失败: ${e.message}")
    }

    private fun LyricsResult.toSong(duration: Long) = Song().apply {
        name = trackName
        artist = artistName
        lyrics = rich
        this.duration = duration
    }

    private fun release() {
        // 符合 Lyricon 标准：先断开连接，再释放监听器和远端资源
        provider?.unregister()
        provider?.destroy()
        provider = null
        YLog.info(tag = TAG, msg = "LocalProvider released")
    }
}