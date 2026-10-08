/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

@file:Suppress("unused")

package io.github.proify.extensions.android

import android.annotation.SuppressLint
import android.util.Log
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Field
import java.util.concurrent.CopyOnWriteArraySet

object Flyme {
    const val FLAG_ALWAYS_SHOW_TICKER_HOOK = 0x01000000
    const val FLAG_ONLY_UPDATE_TICKER_HOOK = 0x02000000

    private val hookHandles = CopyOnWriteArraySet<XposedInterface.HookHandle>()

    private var cachedAlwaysShowField: Field? = null
    private var cachedOnlyUpdateField: Field? = null

    private val spoofMap = mapOf(
        "ro.product.model" to "meizu 16th Plus",
        "ro.product.brand" to "meizu",
        "ro.product.manufacturer" to "Meizu",
        "ro.product.device" to "m1892",
        "ro.build.display.id" to "Flyme",
        "ro.build.product" to "meizu_16thPlus_CN",
        "ro.meizu.product.model" to "m1892"
    )

    fun unlock() {
        hookHandles.forEach { it.unhook() }
        hookHandles.clear()
    }

    @SuppressLint("PrivateApi")
    fun mock(xosed: XposedInterface, loader: ClassLoader) {
        try {
            initFieldsCache(xosed)

            val buildClass = Class.forName("android.os.Build", false, loader)
            val buildFields = mapOf(
                "BRAND" to "meizu",
                "MANUFACTURER" to "Meizu",
                "DEVICE" to "m1892",
                "DISPLAY" to "Flyme",
                "PRODUCT" to "meizu_16thPlus_CN",
                "MODEL" to "meizu 16th Plus"
            )
            buildFields.forEach { (k, v) ->
                val field = buildClass.getDeclaredField(k)
                field.isAccessible = true
                field.set(null, v)
            }

            val spClass = Class.forName("android.os.SystemProperties", false, loader)
            val getOneArg = spClass.getDeclaredMethod("get", String::class.java)
            hookHandles += xosed.hook(getOneArg).intercept { chain ->
                val result = chain.proceed()
                val key = chain.args[0] as? String
                spoofMap[key] ?: result
            }

            val getTwoArg = spClass.getDeclaredMethod("get", String::class.java, String::class.java)
            hookHandles += xosed.hook(getTwoArg).intercept { chain ->
                val result = chain.proceed()
                val key = chain.args[0] as? String
                spoofMap[key] ?: result
            }

            val getField = Class::class.java.getDeclaredMethod("getField", String::class.java)
            hookHandles += xosed.hook(getField).intercept { chain ->
                val result = chain.proceed()
                val name = chain.args[0] as? String
                when (name) {
                    "FLAG_ALWAYS_SHOW_TICKER" -> cachedAlwaysShowField ?: result
                    "FLAG_ONLY_UPDATE_TICKER" -> cachedOnlyUpdateField ?: result
                    else -> result
                }
            }

            val getDeclaredField = Class::class.java.getDeclaredMethod("getDeclaredField", String::class.java)
            hookHandles += xosed.hook(getDeclaredField).intercept { chain ->
                val result = chain.proceed()
                val name = chain.args[0] as? String
                when (name) {
                    "FLAG_ALWAYS_SHOW_TICKER" -> cachedAlwaysShowField ?: result
                    "FLAG_ONLY_UPDATE_TICKER" -> cachedOnlyUpdateField ?: result
                    else -> result
                }
            }

        } catch (t: Throwable) {
            xosed.log(Log.ERROR, "Flyme", "Flyme Mock Error: ${t.message}", t)
        }
    }

    private fun initFieldsCache(xosed: XposedInterface) {
        try {
            cachedAlwaysShowField =
                Flyme::class.java.getDeclaredField("FLAG_ALWAYS_SHOW_TICKER_HOOK")
            cachedOnlyUpdateField =
                Flyme::class.java.getDeclaredField("FLAG_ONLY_UPDATE_TICKER_HOOK")
        } catch (e: Exception) {
            xosed.log(Log.ERROR, "Flyme", "Failed to cache fields: $e", e)
        }
    }
}