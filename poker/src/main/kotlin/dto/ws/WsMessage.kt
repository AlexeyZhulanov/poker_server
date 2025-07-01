package com.example.dto.ws

import com.example.domain.model.GameState
import kotlinx.serialization.Serializable

// Сообщения, которые сервер отправляет клиенту
@Serializable
sealed interface OutgoingMessage {
    @Serializable
    data class GameStateUpdate(val state: GameState) : OutgoingMessage
    @Serializable
    data class PlayerJoined(val username: String) : OutgoingMessage
    @Serializable
    data class PlayerLeft(val username: String) : OutgoingMessage
}

// Сообщения, которые клиент отправляет на сервер
@Serializable
sealed interface IncomingMessage {
    @Serializable
    data class Fold(val temp: String = "") : IncomingMessage // temp - временное поле
    @Serializable
    data class Bet(val amount: Long) : IncomingMessage
}