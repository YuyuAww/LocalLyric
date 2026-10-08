# 保留 Xposed 入口和 Hook 类
-keep class io.github.proify.lyricon.localprovider.xposed.HookEntry
-keep class io.github.proify.lyricon.localprovider.xposed.LocalProvider
-keep class io.github.proify.lyricon.localprovider.xposed.PowerAmp

# libxposed R8 支持
-dontwarn io.github.libxposed.annotation.**
-adaptresourcefilecontents META-INF/xposed/java_init.list
-keep,allowoptimization,allowobfuscation public class * extends io.github.libxposed.api.XposedModule {
    public <init>();
}

# 保留 TagLib（内嵌歌词需要）
-keep class com.kyant.taglib.** { *; }

# 保留 Kotlin 反射（可能需要）
-keep class kotlin.reflect.** { *; }
-keep class kotlin.Metadata { *; }

# 保留泛型和注解信息（防止反射失效）
-keepattributes Signature, InnerClasses, EnclosingMethod
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations, AnnotationDefault

# 保留 kotlinx.serialization 相关类
-keep class kotlinx.serialization.** { *; }
-keepclassmembers class * {
    @kotlinx.serialization.Serializable <fields>;
}

# 忽略警告（如反射相关）
-dontwarn java.lang.reflect.AnnotatedType
-dontwarn sun.misc.**

# 保留行号信息（便于调试）
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile