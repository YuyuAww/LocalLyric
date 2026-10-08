/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.localprovider.xposed

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.core.content.ContextCompat
import com.kyant.taglib.TagLib
import io.github.libxposed.api.XposedInterface
import io.github.proify.lrckit.EnhanceLrcParser
import io.github.proify.lyricon.localprovider.util.TTMLParser
import io.github.proify.lyricon.localprovider.util.ensureWordSpacing
import io.github.proify.lyricon.localprovider.xposed.util.SafUriResolver
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.provider.ConnectionListener
import io.github.proify.lyricon.provider.LyriconFactory
import io.github.proify.lyricon.provider.LyriconProvider

object PowerAmp {
    private const val TAG = "PowerAmp"
    private const val ACTION_TRACK_CHANGED = "com.maxmpz.audioplayer.TRACK_CHANGED"

    private val lyricTagRegex by lazy { Regex("(?i)\\b(LYRICS|LYRICS\\d*|USLT)\\b") }

    private var xosed: XposedInterface? = null
    private var classLoader: ClassLoader? = null
    var appContext: Context? = null
    private var processName: String = ""
    private var isSetup = false

    private var provider: LyriconProvider? = null
    private var trackReceiver: BroadcastReceiver? = null
    private var currentSongId: String? = null

    fun setup(xosed: XposedInterface, classLoader: ClassLoader, packageName: String, processName: String) {
        if (isSetup) return
        isSetup = true
        this.xosed = xosed
        this.classLoader = classLoader
        this.processName = processName

        hookActivityLifecycle()
    }

    private fun hookActivityLifecycle() {
        val x = xosed ?: return
        val cl = classLoader ?: return

        val activityClass = Class.forName("android.app.Activity", false, cl)

        val onCreate = activityClass.getDeclaredMethod("onCreate", Bundle::class.java)
        x.hook(onCreate).intercept { chain ->
            val result = chain.proceed()
            val activity = chain.thisObject as? Activity
            appContext = activity?.applicationContext
            appContext?.let { ctx ->
                initLyriconProvider(ctx)
                setupBroadcastReceiver(ctx)
            }
            hookMediaSession()
            result
        }

        val onTerminate = activityClass.getDeclaredMethod("onTerminate")
        x.hook(onTerminate).intercept { chain ->
            val result = chain.proceed()
            release()
            result
        }
    }

    private fun initLyriconProvider(context: Context) {
        provider = LyriconFactory.createProvider(
            context = context,
            providerPackageName = Constants.PROVIDER_PACKAGE_NAME,
            playerPackageName = context.packageName,
            logo = null
        ).apply {
            // 启用自动同步，避免中心服务重启后歌词状态丢失
            autoSync = true
            // 监听连接状态，便于重连后同步及超时提示
            service.addConnectionListener(object : ConnectionListener {
                override fun onConnected(provider: LyriconProvider) {
                    xosed?.log(Log.INFO, TAG, "已连接 Lyricon 中心服务")
                }
                override fun onReconnected(provider: LyriconProvider) {
                    xosed?.log(Log.INFO, TAG, "已重新连接 Lyricon 中心服务")
                }
                override fun onDisconnected(provider: LyriconProvider) {
                    xosed?.log(Log.WARN, TAG, "与 Lyricon 中心服务连接断开")
                }
                override fun onConnectTimeout(provider: LyriconProvider) {
                    xosed?.log(Log.WARN, TAG, "连接 Lyricon 中心服务超时，请检查 Lyricon/LSPosed 状态")
                }
            })
            register()
            // 启用翻译和罗马音显示（符合 Lyricon 标准：展示需 Provider 主动开启）
            player.setDisplayTranslation(true)
            player.setDisplayRoma(true)
        }
        xosed?.log(Log.INFO, TAG, "Lyricon Provider registered")
    }

    private fun setupBroadcastReceiver(context: Context) {
        val filter = IntentFilter(ACTION_TRACK_CHANGED)
        trackReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == ACTION_TRACK_CHANGED) {
                    handleTrackChange(intent)
                }
            }
        }.also {
            ContextCompat.registerReceiver(context, it, filter, ContextCompat.RECEIVER_EXPORTED)
        }
        xosed?.log(Log.INFO, TAG, "Broadcast receiver registered")
    }

    private fun hookMediaSession() {
        val x = xosed ?: return
        val cl = classLoader ?: return

        val mediaSessionClass = Class.forName("android.media.session.MediaSession", false, cl)
        val setPlaybackState = mediaSessionClass.getDeclaredMethod("setPlaybackState", PlaybackState::class.java)
        x.hook(setPlaybackState).intercept { chain ->
            val result = chain.proceed()
            val state = chain.args[0] as? PlaybackState
            if (state != null) {
                provider?.player?.setPlaybackState(state)
            }
            result
        }
        xosed?.log(Log.INFO, TAG, "MediaSession hooked")
    }

    private fun handleTrackChange(intent: Intent) {
        val bundle = intent.extras ?: return

        val id = bundle.getLong("id", -1).toString()
        val title = bundle.getString("title") ?: return
        val artist = bundle.getString("artist")
        val album = bundle.getString("album")
        val duration = bundle.getLong("durMs")
        val path = bundle.getString("path") ?: return

        if (id == currentSongId) return
        currentSongId = id

        provider?.player?.setSong(Song(name = title, artist = artist))
        // 同步当前播放位置（符合 Lyricon 标准：setSong 后应同步进度）
        provider?.player?.setPosition(0)

        val uri = resolveAudioUri(path)
        if (uri != null) {
            val lyrics = fetchEmbeddedLyrics(uri)
            if (!lyrics.isNullOrEmpty()) {
                val song = Song(
                    id = id,
                    name = title,
                    artist = artist,
                    duration = duration,
                    lyrics = lyrics
                )
                provider?.player?.setSong(song)
                // 同步播放位置（符合 Lyricon 标准：setSong 后应同步进度）
                provider?.player?.setPosition(0)
                xosed?.log(Log.INFO, TAG, "Embedded lyrics loaded for: $title")
                return
            }
        }

    }

    private fun resolveAudioUri(path: String): Uri? {
        val formattedPath = formatSafPath(path) ?: return null
        return SafUriResolver.resolveToUri(appContext!!, formattedPath)
    }

    private fun formatSafPath(path: String): String? {
        val input = path.trimStart()
        if (input.isEmpty() || input.startsWith("/")) return null

        val separatorIndex = input.indexOf('/')
        if (separatorIndex == -1) return null

        val volumeId = input.take(separatorIndex)
        val relativePath = input.substring(separatorIndex + 1)

        return if (volumeId.isNotEmpty()) "$volumeId:$relativePath" else null
    }

    private fun fetchEmbeddedLyrics(uri: Uri): List<io.github.proify.lyricon.lyric.model.RichLyricLine>? {
        return try {
            appContext?.contentResolver?.openFileDescriptor(uri, "r")?.use { pfd ->
                TagLib.getMetadata(pfd.dup().detachFd())?.let { metadata ->
                    val entry = metadata.propertyMap.entries.firstOrNull { (key, _) -> lyricTagRegex.matches(key) }
                    if (entry == null) {
                        return@let null
                    }
                    val raw = entry.value.firstOrNull() ?: return@let null
                    xosed?.log(Log.INFO, TAG, "找到内嵌歌词，长度=${raw.length}")

                    val lines = if (TTMLParser.isTTML(raw)) {
                        TTMLParser.parse(raw)
                    } else {
                        val doc = EnhanceLrcParser.parse(raw)
                        doc.lines.filter { !it.text.isNullOrBlank() }
                    }
                    lines.ensureWordSpacing()
                }
            }
        } catch (e: Exception) {
            xosed?.log(Log.ERROR, TAG, "Failed to fetch lyrics", e)
            null
        }
    }

    private fun release() {
        trackReceiver?.let { appContext?.unregisterReceiver(it) }
        trackReceiver = null
        // 符合 Lyricon 标准：先断开连接，再释放监听器和远端资源
        provider?.unregister()
        provider?.destroy()
        provider = null
        xosed?.log(Log.INFO, TAG, "PowerAmp provider released")
    }
}