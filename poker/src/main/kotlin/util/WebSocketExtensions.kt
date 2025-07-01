package com.example.util

import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import kotlinx.serialization.json.Json

/**
 * Сериализует объект [data] в JSON-строку и отправляет его как текстовый фрейм.
 * Ключевое слово 'reified' позволяет автоматически определять тип T во время выполнения,
 * что необходимо для kotlinx.serialization.
 */
suspend inline fun <reified T> WebSocketSession.sendSerialized(data: T) {
    val jsonString = Json.encodeToString(data)
    send(Frame.Text(jsonString))
}