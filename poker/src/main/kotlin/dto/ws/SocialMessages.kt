package com.example.dto.ws

import kotlinx.serialization.Serializable


@Serializable
sealed interface SocialAction {
    // 1. Показать стикер
    @Serializable
    data class ShowSticker(val stickerId: String) : SocialAction

    // 2. Нарисовать линию
    @Serializable
    data class DrawLine(val points: List<Point>, val color: String) : SocialAction

    // 3. Кинуть предмет в игрока
    @Serializable
    data class ThrowItem(val itemId: String, val targetUserId: String) : SocialAction
}

@Serializable
data class Point(val x: Float, val y: Float)