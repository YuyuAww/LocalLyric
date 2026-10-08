/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.extensions.android

import android.util.Log
import io.github.libxposed.api.XposedInterface

/**
 * @author Lin
 */
object AndroidUtils {
    fun openBluetoothA2dpOn(xosed: XposedInterface, classLoader: ClassLoader?) {
        if (classLoader == null) return
        val audioManagerClass = Class.forName("android.media.AudioManager", false, classLoader)
        val isBluetoothA2dpOn = audioManagerClass.getDeclaredMethod("isBluetoothA2dpOn")
        xosed.hook(isBluetoothA2dpOn).intercept { _ -> true }

        val bluetoothAdapterClass = Class.forName("android.bluetooth.BluetoothAdapter", false, classLoader)
        val isEnabled = bluetoothAdapterClass.getDeclaredMethod("isEnabled")
        xosed.hook(isEnabled).intercept { _ -> true }
    }

//    fun getStringForStateInt(state: Int): String {
//        return when (state) {
//            PlaybackState.STATE_NONE -> "NONE"
//            PlaybackState.STATE_STOPPED -> "STOPPED"
//            PlaybackState.STATE_PAUSED -> "PAUSED"
//            PlaybackState.STATE_PLAYING -> "PLAYING"
//            PlaybackState.STATE_FAST_FORWARDING -> "FAST_FORWARDING"
//            PlaybackState.STATE_REWINDING -> "REWINDING"
//            PlaybackState.STATE_BUFFERING -> "BUFFERING"
//            PlaybackState.STATE_ERROR -> "ERROR"
//            PlaybackState.STATE_CONNECTING -> "CONNECTING"
//            PlaybackState.STATE_SKIPPING_TO_PREVIOUS -> "SKIPPING_TO_PREVIOUS"
//            PlaybackState.STATE_SKIPPING_TO_NEXT -> "SKIPPING_TO_NEXT"
//            PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM -> "SKIPPING_TO_QUEUE_ITEM"
//            else -> "UNKNOWN"
//        }
//    }
}