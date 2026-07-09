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
 * 判断歌词行是否为英文
 * 通过检测单词文本中是否主要包含 ASCII 字母来判断
 */
private fun List<io.github.proify.lyricon.lyric.model.LyricWord>.isEnglish(): Boolean {
    if (isEmpty()) return false
    val allText = joinToString("") { it.text.orEmpty() }
    if (allText.isBlank()) return false
    // 计算英文字母占比，超过 60% 则判定为英文
    val englishChars = allText.count { it.isLetter() && it.code < 128 }
    return englishChars.toFloat() / allText.length > 0.6f
}

fun Song.ensureWordSpacing(): Song = deepCopy().apply {
    lyrics = lyrics?.map { line ->
        val wordList = line.words
        // 先判断 words 是否为空
        if (wordList.isNullOrEmpty()) line
        // 非空情况下判断是否是英文，只有英文才添加单词间距
        else if (!wordList.isEnglish()) line
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
        // 非空情况下判断是否是英文，只有英文才添加单词间距
        else if (!wordList.isEnglish()) line
        else {
            val spacedWords = wordList.map { word ->
                word.copy(text = (word.text ?: "") + " ")
            }
            val spacedText = spacedWords.joinToString("") { it.text ?: "" }
            line.copy(text = spacedText, words = spacedWords)
        }
    }