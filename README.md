# LyricProvider（本地歌词插件）

自动读取音频内嵌歌词（LYRICS 标签）或扫描指定目录下的同名 .lrc 文件，并智能解析路径，适配多种播放器。

## 项目整体架构

本项目是一个 Android Xposed 模块，同时也是 Lyricon 歌词框架的插件模块。采用模块化架构，分为主应用模块（app）和多个共享模块（share）。

### 模块划分

```
LyricProvider/
├── build.gradle.kts              # 根构建脚本（含 APK 打包任务）
├── settings.gradle.kts           # 模块配置
├── .github/
│   └── workflows/
│       └── build-release.yml     # GitHub Actions CI 构建
├── app/                          # 主应用模块
│   ├── build.gradle.kts
│   └── src/main/
│       ├── kotlin/
│       │   └── io/github/proify/lyricon/localprovider/
│       │       ├── xposed/       # Xposed Hook 核心逻辑
│       │       ├── local/        # 外部 LRC 文件解析
│       │       ├── util/         # 工具类
│       │       └── model/        # 数据模型
│       ├── assets/
│       │   └── xposed_init       # Xposed 入口类声明
│       ├── resources/
│       │   └── META-INF/
│       │       └── yukihookapi_init  # YukiHookAPI 初始化
│       └── AndroidManifest.xml
├── share/
│   ├── lrckit/                   # 歌词解析共享模块
│   ├── extensions-kt/            # Kotlin 通用扩展
│   └── extensions-android/       # Android 平台扩展
└── README.md
```

### 技术栈

- **开发语言**: Kotlin
- **构建工具**: Gradle 9.x (Kotlin DSL)
- **核心框架**:
  - YukiHookAPI：Xposed Hook 框架
  - Lyricon Provider API：歌词提供者接口
  - TagLib：音频元数据读取库
- **最低 API**: 27 (Android 8.1)
- **编译 API**: 37
- **目标 API**: 36

## 主要模块职责

### 1. Xposed Hook 模块 (`xposed/`)

核心 Hook 逻辑，负责注入播放器进程并提供歌词服务。

#### 关键类

| 类名 | 职责 | 关键方法 |
|------|------|----------|
| **HookEntry** | Xposed 模块入口 | `onHook()`：加载 LocalProvider 和 PowerAmp Hooker<br>`onInit()`：配置调试日志 |
| **LocalProvider** | 通用播放器适配（支持 MediaSession） | `hookMediaSession()`：Hook MediaSession API<br>`handleMetadata()`：处理歌曲元数据变更<br>`tryLoadEmbeddedLyrics()`：读取内嵌歌词 |
| **PowerAmp** | PowerAmp 播放器专用适配 | `handleTrackChange()`：处理 PowerAmp 广播事件<br>`fetchEmbeddedLyrics()`：读取内嵌歌词<br>`hookMediaSession()`：同步播放状态 |
| **DownloadManager** | 歌词搜索任务管理器 | `search()`：启动歌词搜索流程<br>`cancel()`：取消当前任务 |
| **EmbeddedLyricsProvider** | 内嵌歌词解析 | `searchByAudioFile()`：从音频文件提取歌词 |
| **PathManager** | 歌词目录路径管理 | `getPaths()`：获取歌词目录列表<br>`addPath()`：添加新目录 |

#### 核心流程

1. **初始化阶段**
   - Xposed 注入目标应用进程
   - 注册 Lyricon Provider 服务
   - 创建默认歌词目录（Music、Download）
   - Hook MediaSession API 或注册广播接收器（PowerAmp）

2. **歌词搜索流程**
   ```
   歌曲切换事件 → 元数据解析 → 路径定位
                                     ↓
   优先级1: 内嵌歌词 ← TagLib读取音频标签
                                     ↓
   优先级2: 外部LRC ← 同名.lrc文件匹配
                                     ↓
   优先级3: 元数据匹配 ← 根据标题/歌手搜索
                                     ↓
   结果回调 → 更新歌词显示
   ```

### 2. 本地歌词解析模块 (`local/`)

#### ExternalLrcProvider

外部 .lrc 文件解析器，支持三种格式：
- **TTML**：标准 TTML 格式（带翻译支持）
- **ENHANCED_LRC**：增强 LRC（逐字时间轴）
- **STANDARD_LRC**：标准 LRC 格式

**核心方法**:
- `searchByAudioFile()`：根据音频文件路径查找同名 .lrc 文件
- `searchByMetadata()`：根据歌曲元数据智能匹配歌词文件
- `ensureCacheInitialized()`：初始化歌词文件缓存（扫描所有歌词目录）

**智能匹配算法**:
- 精确匹配：标题 + 歌手完全一致
- 标题匹配：仅标题匹配（相似度 ≥ 0.8）
- 模糊匹配：综合评分（标题权重 0.7 + 歌手权重 0.3 + 格式加成）

### 3. 工具模块 (`util/`)

#### TTMLParser

TTML 格式歌词解析器，支持：
- 时间戳解析（多种格式：`HH:MM:SS.mmm`、`MM:SS.mmm`、毫秒/秒标记）
- 翻译歌词提取（role="x-translation"）
- 背景歌词过滤（role="x-bg")

#### LyricWordSpacer

歌词单词间距处理工具：
- `ensureWordSpacing()`：为逐字歌词添加空格，优化英文显示效果

#### SafUriResolver

SAF（Storage Access Framework）URI 解析工具，用于 PowerAmp 的 SAF 路径解析。

### 4. 数据模型 (`model/`)

#### LyricsResult

歌词结果数据类：
- `trackName`：歌曲标题
- `artistName`：歌手名称
- `albumName`：专辑名称
- `rich`：富文本歌词行列表（RichLyricLine）
- `instrumental`：是否为纯音乐

#### LyricsProvider

歌词提供者接口，定义统一的搜索接口规范。

### 5. 共享模块 (`share/`)

#### lrckit — 歌词解析模块

**EnhanceLrcParser**

增强型 LRC 解析器，支持：
- 标准 LRC 格式：`[mm:ss.xx]歌词文本`
- 增强 LRC 格式：`<mm:ss.xx>逐字<mm:ss.xx>时间`
- 内联时间戳格式：`[00:00.715]世[00:00.924]界`
- 多角色区分：`v1:` / `v2:` / `bg:` 标记
- 元数据标签：`[ti:title]` / `[ar:artist]` / `[al:album]`

**关键函数**:
- `parse()`：解析歌词文本，返回 `EnhanceLrcDocument`
- `parseWords()`：提取逐字时间轴
- `toMs()`：时间戳转换为毫秒
- `finalize()`：计算行结束时间

**EnhanceLrcDocument**

解析结果数据类：
```kotlin
data class EnhanceLrcDocument(
    val metadata: Map<String, String>,
    val lines: List<RichLyricLine>
)
```

**多行辅助文本合并（mergeLines）**

当有多行歌词使用相同起始时间戳时，`mergeLines` 会按顺序分别存储：
- 第 1 行：主行（`text` + `words`）
- 第 2 行：辅助行/罗马音（`secondary` + `secondaryWords`）
- 第 3 行：翻译行（`translation` + `translationWords`）

#### extensions-kt — Kotlin 通用扩展

提供 Kotlin 通用扩展函数，包括：
- `ArrayExtensions`：数组操作扩展
- `ByteArrayExtensions`：字节数组扩展
- `Extensions`：通用工具扩展
- `JsonExtensions`：JSON 序列化扩展
- `LanguageKt`：语言相关工具
- `LyricKt`：歌词数据扩展
- `StringExtensions`：字符串操作扩展
- `TreeMapKt`：TreeMap 操作扩展

#### extensions-android — Android 平台扩展

提供 Android 平台专用扩展，依赖 `extensions-kt`，包括：
- `AndroidUtils`：Android 通用工具
- `DimensionExtensions`：尺寸单位转换
- `Flyme`：Flyme 系统适配
- `ObjectUtils`：对象工具
- `PlaybackStateKt`：播放状态扩展
- `ScreenStateMonitor`：屏幕状态监听

## 依赖关系

### 项目依赖

```kotlin
dependencies {
    // 共享模块
    implementation(project(":share:lrckit"))
    implementation(project(":share:extensions-kt"))
    implementation(project(":share:extensions-android"))

    // 核心框架
    implementation(libs.lyricon.provider)       // Lyricon 歌词框架
    implementation(libs.yukihookapi.api)        // Xposed Hook 框架
    ksp(libs.yukihookapi.ksp.xposed)           // KSP 处理器

    // 音频处理
    implementation(libs.taglib)                 // 音频元数据读取

    // 反射与序列化
    implementation(libs.kavaref.core)           // Kotlin 反射工具
    implementation(libs.kavaref.extension)
    implementation(libs.kotlinx.serialization.json)

    // AndroidX
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)

    // Xposed API（编译时）
    compileOnly(libs.xposed.api)
}
```

### 模块间依赖关系

```
app (主模块)
 ├─→ share:lrckit (歌词解析)
 ├─→ share:extensions-kt (Kotlin 扩展)
 ├─→ share:extensions-android (Android 扩展)
 │        └─→ share:extensions-kt (api 依赖)
 ├─→ lyricon.provider (歌词提供者接口)
 ├─→ yukihookapi (Hook 框架)
 └─→ taglib (音频标签读取)
```

### 仓库配置

```kotlin
repositories {
    mavenLocal()
    mavenCentral()
    maven { url = uri("https://jitpack.io") }       // JitPack 仓库
    google()                                        // Google 仓库
    maven { url = uri("https://api.xposed.info/") } // Xposed API
}
```

## 项目运行方式

### 构建与安装

1. **环境要求**
   - JDK 17+
   - Android SDK 37
   - Gradle 9.x

2. **构建命令**
   ```bash
   # Debug 版本
   ./gradlew assembleDebug

   # Release 版本（自动生成测试签名密钥）
   ./gradlew assembleRelease

   # 收集所有 APK 并打包为 ZIP
   ./gradlew copyApks
   ```

3. **签名配置（可选）**

   构建时会**自动生成公版测试密钥**（`release.jks`），无需手动配置。如需自定义签名，设置环境变量：
   ```bash
   export RELEASE_STORE_FILE="/path/to/your.jks"
   export RELEASE_STORE_PASSWORD="your_password"
   export RELEASE_KEY_ALIAS="your_alias"
   export RELEASE_KEY_PASSWORD="your_key_password"
   ```

   默认测试密钥信息：
   | 配置项 | 默认值 |
   |--------|--------|
   | 密钥文件 | `release.jks`（项目根目录） |
   | 密钥库密码 | `android` |
   | 别名 | `androidkey` |
   | 密钥密码 | `android` |

4. **APK 打包任务**

   根构建脚本提供了便捷的 APK 打包任务：

   | 任务名 | 说明 |
   |--------|------|
   | `cleanAllApks` | 清理 APK 导出目录 |
   | `copyApks` | 收集所有模块 APK 到 `build/all-apks/` |
   | `zipReleaseApks` | 将 Release APK 打包为 ZIP（`build/distributions/`） |

   `assembleRelease` 任务执行后会自动触发 `copyApks`。

### Xposed 模块使用

1. **激活模块**
   - 在 Xposed Installer / LSPosed 中激活本模块
   - 设置作用域：选择目标音乐播放器或设置为"系统框架"（全局）

2. **默认作用域**
   - 通用播放器：支持 MediaSession API 的播放器（如 Musicolet、Omnia Music Player）
   - PowerAmp：专用作用域 `com.maxmpz.audioplayer`

3. **歌词目录**
   默认扫描目录：
   - `/storage/emulated/0/Music`
   - `/storage/emulated/0/Download`

   可通过 SharedPreferences（`lyric_paths`）手动添加自定义目录。

### Lyricon 插件集成

本模块同时支持作为 Lyricon 歌词框架的插件使用：

**Manifest 元数据**:
```xml
<meta-data
    android:name="lyricon_module"
    android:value="true" />
<meta-data
    android:name="lyricon_module_author"
    android:value="QFDY" />
```

### 持续集成 (GitHub Actions)

项目配置了 GitHub Actions 自动构建工作流（`.github/workflows/build-release.yml`）：

- **触发条件**：push 到 main、pull request、手动触发
- **构建流程**：检出代码 → 配置 JDK 17 → 构建 Release APK → 上传 Artifact
- **产物保留**：30 天

### 歌词文件格式支持

| 格式 | 示例 | 特性 |
|------|------|------|
| **标准 LRC** | `[00:12.50]Hello World` | 基础时间轴 |
| **增强 LRC** | `[00:12.50]<00:12.50>Hello<00:12.80>World` | 逐字时间轴 |
| **内联时间戳** | `[00:12.50]Hel[00:12.60]lo` | 紧凑格式 |
| **TTML** | `<p begin="00:12.500s">Hello</p>` | XML 结构、翻译支持 |

### 内嵌歌词支持

支持的音频标签：
- `LYRICS`：通用歌词标签
- `LYRICS<数字>`：多歌词标签（如 LYRICS1、LYRICS2）
- `USLT`：Unsynchronized Lyrics Tag（ID3 标准）

支持的歌词编码：UTF-8（推荐）、GBK（需自动检测）

## 关键设计特性

### 1. 智能路径解析

多级路径解析策略：
1. MediaMetadata 直接路径提取
2. MediaStore 精确匹配（标题 + 歌手）
3. MediaStore 模糊匹配（包含关系）
4. 仅标题匹配兜底

### 2. 歌词优先级

搜索优先级：
1. **内嵌歌词**：直接从音频文件提取
2. **同名 LRC**：音频文件同目录同名 .lrc 文件
3. **元数据匹配**：根据歌曲信息从歌词库匹配

### 3. 性能优化

- **缓存机制**：歌词文件缓存、内嵌歌词缓存
- **协程异步**：所有 I/O 操作使用协程
- **懒加载**：歌词库按需初始化
- **任务取消**：歌曲切换时自动取消未完成任务
- **日志精简**：仅保留关键日志（info/error/warn），减少调试日志输出

### 4. 特殊播放器支持

#### PowerAmp 适配

- 监听专用广播：`com.maxmpz.audioplayer.TRACK_CHANGED`
- SAF 路径解析：适配 PowerAmp 的特殊文件路径格式
- 独立 Provider 注册：避免与通用 Hooker 冲突

### 5. 配置缓存兼容

所有构建脚本遵循 Gradle 配置缓存要求：
- 外部进程（如 keytool）在执行阶段通过 Task 调用
- 任务输入输出使用 Provider/Lazy 属性
- 执行阶段不访问 `Task.project`

## 许可证

```
Copyright 2026 Proify, Tomakino
Licensed under the Apache License, Version 2.0
http://www.apache.org/licenses/LICENSE-2.0
```
