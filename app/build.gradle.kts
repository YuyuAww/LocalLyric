/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

import com.android.build.api.dsl.ApplicationExtension

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.ksp)
    kotlin("plugin.serialization") version "2.1.21"
}

configure<ApplicationExtension> {
    namespace = "io.github.proify.lyricon.localprovider"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "io.github.proify.lyricon.localprovider"
        minSdk = 27
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"

        ndk {
            abiFilters.add("arm64-v8a")
            // 如需保留 32 位支持，取消下一行注释
            //abiFilters.add("armeabi-v7a")
        }
    }

    // 语言资源过滤（仅保留中文和英文）
    androidResources {
        localeFilters.add("zh")
        localeFilters.add("en")
    }

    signingConfigs {
        create("release") {
            // 默认密钥文件路径（相对于项目根目录）
            val storeFilePath = System.getenv("RELEASE_STORE_FILE") ?: rootProject.file("release.jks").absolutePath
            val storePass = System.getenv("RELEASE_STORE_PASSWORD") ?: "android"
            val alias = System.getenv("RELEASE_KEY_ALIAS") ?: "androidkey"
            val keyPass = System.getenv("RELEASE_KEY_PASSWORD") ?: "android"

            // 密钥文件不存在时自动生成公版测试密钥
            val storeFile = file(storeFilePath)
            if (!storeFile.exists()) {
                storeFile.parentFile?.mkdirs()
                val dname = "CN=LocalLyric, OU=Proify, O=Proify, L=Unknown, ST=Unknown, C=CN"
                val cmd = listOf(
                    "keytool", "-genkeypair",
                    "-keystore", storeFile.absolutePath,
                    "-storetype", "PKCS12",
                    "-keyalg", "RSA",
                    "-keysize", "2048",
                    "-validity", "10000",
                    "-alias", alias,
                    "-storepass", storePass,
                    "-keypass", keyPass,
                    "-dname", dname
                )
                ProcessBuilder(cmd).redirectErrorStream(true).start().waitFor()
            }

            storeFile = storeFile
            storePassword = storePass
            keyAlias = alias
            keyPassword = keyPass
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("release")
        }
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    // 共享模块
    implementation(project(":share:lrckit"))
    implementation(project(":share:extensions-kt"))
    implementation(project(":share:extensions-android"))

    // 其他依赖
    implementation(libs.lyricon.provider)
    implementation(libs.yukihookapi.api)
    implementation(libs.kavaref.core)
    implementation(libs.kavaref.extension)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.taglib)      // 内嵌歌词需要
    compileOnly(libs.xposed.api)
    ksp(libs.yukihookapi.ksp.xposed)
}