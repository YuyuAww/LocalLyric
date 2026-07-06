/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.localprovider.util

import io.github.proify.lyricon.lyric.model.RichLyricLine
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.lyric.model.extensions.deepCopy

fun Song.ensureWordSpacing(): Song = deepCopy().apply {
    lyrics = lyrics?.map { line ->
        val wordList = line.words
        if (wordList.isNullOrEmpty()) line
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
        if (wordList.isNullOrEmpty()) line
        else {
            val spacedWords = wordList.map { word ->
                word.copy(text = (word.text ?: "") + " ")
            }
            val spacedText = spacedWords.joinToString("") { it.text ?: "" }
            line.copy(text = spacedText, words = spacedWords)
        }
    }