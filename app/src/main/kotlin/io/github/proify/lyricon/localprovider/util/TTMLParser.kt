/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.localprovider.util

import android.util.Log
import android.util.Xml
import io.github.proify.lyricon.lyric.model.RichLyricLine
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader

object TTMLParser {
    private const val TAG = "TTMLParser"
    private val IGNORED_ROLES = setOf("x-bg") // 忽略背景歌词

    fun parse(ttml: String): List<RichLyricLine> {
        val lines = mutableListOf<RichLyricLine>()
        val parser = Xml.newPullParser()
        parser.setInput(StringReader(ttml))

        try {
            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG && getLocalName(parser) == "p") {
                    val begin = parseTime(parser.getAttributeValue(null, "begin"))
                    val end = parseTime(parser.getAttributeValue(null, "end"))
                    val (mainText, translationText, romaText) = extractTextFromTag(parser)
                    if (mainText.isNotEmpty()) {
                        // 翻译填入 translation 字段，罗马音填入 roma 字段，由展示端按开关控制显示
                        val translation = translationText?.takeIf { it.isNotBlank() }
                        val roma = romaText?.takeIf { it.isNotBlank() }
                        lines.add(
                            RichLyricLine(
                                begin = begin,
                                end = end,
                                text = mainText,
                                translation = translation,
                                roma = roma
                            )
                        )
                    }
                }
                eventType = parser.next()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return lines
    }

    private fun extractTextFromTag(parser: XmlPullParser): Triple<String, String?, String?> {
        val depth = parser.depth
        val mainText = StringBuilder()
        val transText = StringBuilder()
        val romaText = StringBuilder()
        var eventType = parser.next()
        while (eventType != XmlPullParser.END_TAG || parser.depth > depth) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    if (getLocalName(parser) == "span") {
                        val role = parser.getAttributeValue("http://www.w3.org/ns/ttml#metadata", "role")
                            ?: parser.getAttributeValue(null, "role") ?: ""
                        when {
                            IGNORED_ROLES.contains(role) -> {
                                skipTag(parser)
                            }
                            role == "x-translation" -> {
                                transText.append(extractSpanText(parser))
                            }
                            // 罗马音角色：兼容 x-romanization 与 x-roma 两种写法
                            role == "x-romanization" || role == "x-roma" -> {
                                romaText.append(extractSpanText(parser))
                            }
                            else -> {
                                mainText.append(extractSpanText(parser))
                            }
                        }
                    }
                }
                XmlPullParser.TEXT -> {
                    mainText.append(parser.text)
                }
            }
            eventType = parser.next()
        }
        return Triple(
            mainText.toString().trim(),
            transText.toString().trim().takeIf { it.isNotBlank() },
            romaText.toString().trim().takeIf { it.isNotBlank() }
        )
    }

    private fun extractSpanText(parser: XmlPullParser): String {
        val depth = parser.depth
        val text = StringBuilder()
        var eventType = parser.next()
        while (eventType != XmlPullParser.END_TAG || parser.depth > depth) {
            if (eventType == XmlPullParser.TEXT) {
                text.append(parser.text)
            }
            eventType = parser.next()
        }
        return text.toString()
    }

    private fun skipTag(parser: XmlPullParser) {
        var depth = parser.depth
        var eventType = parser.next()
        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> depth++
                XmlPullParser.END_TAG -> if (depth == parser.depth) break
            }
            eventType = parser.next()
        }
    }

    private fun getLocalName(parser: XmlPullParser): String {
        val name = parser.name ?: return ""
        return name.substringAfterLast(':')
    }

    private fun parseTime(timeStr: String?): Long {
        if (timeStr.isNullOrBlank()) return 0L
        return try {
            when {
                timeStr.contains(":") -> {
                    val normalized = timeStr.replace(',', '.')
                    val parts = normalized.split(":")
                    val seconds = when (parts.size) {
                        2 -> parts[0].toDouble() * 60 + parts[1].toDouble()
                        3 -> parts[0].toDouble() * 3600 + parts[1].toDouble() * 60 + parts[2].toDouble()
                        else -> 0.0
                    }
                    (seconds * 1000).toLong()
                }
                timeStr.endsWith("ms") -> {
                    timeStr.removeSuffix("ms").toLong()
                }
                timeStr.endsWith("s") -> {
                    (timeStr.removeSuffix("s").toDouble() * 1000).toLong()
                }
                else -> {
                    (timeStr.toDouble() * 1000).toLong()
                }
            }
        } catch (_: Exception) { 0L }
    }

    fun isTTML(content: String): Boolean {
        val trimmed = content.trim()
        return (trimmed.startsWith("<?xml") && trimmed.contains("<tt", ignoreCase = true)) ||
                trimmed.startsWith("<tt", ignoreCase = true)
    }
}