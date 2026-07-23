/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.localprovider.util

import io.github.proify.lyricon.lyric.model.RichLyricLine
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.lyric.model.extensions.deepCopy

/**
 * 判断歌词行是否为中文/日文/韩文（CJK）
 * 通过检测单词文本中是否主要包含 CJK 统一表意文字、日文假名或韩文音节来判断
 */
private fun List<io.github.proify.lyricon.lyric.model.LyricWord>.isCJK(): Boolean {
    if (isEmpty()) return false
    val allText = joinToString("") { it.text.orEmpty() }
    if (allText.isBlank()) return false
    // 计算 CJK 字符占比，超过 30% 则判定为中文/日文/韩文
    val cjkChars = allText.count { it.isCJKCharacter() }
    return cjkChars.toFloat() / allText.length > 0.3f
}

/**
 * 判断单个字符是否属于中文/日文/韩文（CJK）
 */
private fun Char.isCJKCharacter(): Boolean {
    return when (this.code) {
        in 0x4E00..0x9FFF,    // CJK 统一表意文字
        in 0x3040..0x309F,    // 平假名
        in 0x30A0..0x30FF,    // 片假名
        in 0x3400..0x4DBF,    // CJK 扩展 A
        in 0xF900..0xFAFF,    // CJK 兼容表意文字
        in 0xFF65..0xFF9F,    // 半角片假名
        in 0x3000..0x303F,    // CJK 符号和标点
        in 0xAC00..0xD7AF,    // 韩文音节
        in 0x1100..0x11FF,    // 韩文字母
        in 0x3130..0x318F,    // 韩文兼容字母
        in 0xA960..0xA97F,    // 韩文字母扩展-A
        in 0xD7B0..0xD7FF -> true // 韩文字母扩展-B
        else -> false
    }
}

fun Song.ensureWordSpacing(): Song = deepCopy().apply {
    lyrics = lyrics?.map { line ->
        val wordList = line.words
        // 先判断 words 是否为空
        if (wordList.isNullOrEmpty()) line
        // 非空情况下判断是否是 CJK，非 CJK（如英文、俄文等）才添加单词间距
        else if (wordList.isCJK()) line
        else {
            val spacedWords = wordList.map { word ->
                word.copy(text = (word.text ?: "") + " ")
            }
            val spacedText = spacedWords.joinToString("") { it.text ?: "" }
            line.copy(text = spacedText, words = spacedWords)
        }
    }
}

fun List<RichLyricLine>.ensureWordSpacing(): List<RichLyricLine> =
    map { line ->
        val wordList = line.words
        // 先判断 words 是否为空
        if (wordList.isNullOrEmpty()) line
        // 非空情况下判断是否是 CJK，非 CJK（如英文、俄文等）才添加单词间距
        else if (wordList.isCJK()) line
        else {
            val spacedWords = wordList.map { word ->
                word.copy(text = (word.text ?: "") + " ")
            }
            val spacedText = spacedWords.joinToString("") { it.text ?: "" }
            line.copy(text = spacedText, words = spacedWords)
        }
    }