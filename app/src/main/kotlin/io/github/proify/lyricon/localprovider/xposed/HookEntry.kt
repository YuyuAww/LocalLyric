/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.localprovider.xposed

import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

class HookEntry : XposedModule() {
    private var processName: String = ""

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        processName = param.processName
        log(Log.DEBUG, "LocalProvider", "========== 模块已加载，进程名=$processName ==========")
    }

    override fun onPackageReady(param: PackageReadyParam) {
        val packageName = param.packageName
        if (packageName == "com.maxmpz.audioplayer") {
            PowerAmp.setup(this, param.classLoader, packageName, processName)
        } else {
            LocalProvider.setup(this, param.classLoader, packageName, processName)
        }
    }
}