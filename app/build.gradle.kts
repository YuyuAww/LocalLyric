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
            storeFile = file(System.getenv("RELEASE_STORE_FILE") ?: rootProject.file("release.jks").absolutePath)
            storePassword = System.getenv("RELEASE_STORE_PASSWORD") ?: "android"
            keyAlias = System.getenv("RELEASE_KEY_ALIAS") ?: "androidkey"
            keyPassword = System.getenv("RELEASE_KEY_PASSWORD") ?: "android"
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

// 自动生成公版测试密钥（执行阶段，兼容配置缓存）
val generateReleaseKeystore by tasks.registering {
    val keystoreFile = file(System.getenv("RELEASE_STORE_FILE") ?: rootProject.file("release.jks").absolutePath)
    outputs.file(keystoreFile)
    doLast {
        if (keystoreFile.exists()) return@doLast
        keystoreFile.parentFile?.mkdirs()
        val storePass = System.getenv("RELEASE_STORE_PASSWORD") ?: "android"
        val alias = System.getenv("RELEASE_KEY_ALIAS") ?: "androidkey"
        val keyPass = System.getenv("RELEASE_KEY_PASSWORD") ?: "android"
        val dname = "CN=LocalLyric, OU=Proify, O=Proify, L=Unknown, ST=Unknown, C=CN"
        ProcessBuilder(
            "keytool", "-genkeypair",
            "-keystore", keystoreFile.absolutePath,
            "-storetype", "PKCS12",
            "-keyalg", "RSA",
            "-keysize", "2048",
            "-validity", "10000",
            "-alias", alias,
            "-storepass", storePass,
            "-keypass", keyPass,
            "-dname", dname
        ).redirectErrorStream(true).start().waitFor()
    }
}

tasks.matching { it.name.startsWith("assemble") || it.name.startsWith("validateSigning") }.configureEach {
    dependsOn(generateReleaseKeystore)
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