package com.example.dto.ws

import com.example.domain.model.Card
import com.example.domain.model.GameState
import kotlinx.serialization.Serializable

@Serializable
sealed interface OutsInfo {
    @Serializable
    data class DirectOuts(val cards: List<Card>) : OutsInfo
    @Serializable
    data object RunnerRunner : OutsInfo
    @Serializable
    data object DrawingDead : OutsInfo
}

// Результат для одной доски
@Serializable
data class BoardResult(
    val board: List<Card>,
    val winnerUsernames: List<String>
)

// Сообщения, которые сервер отправляет клиенту
@Serializable
sealed interface OutgoingMessage {
    @Serializable
    data class GameStateUpdate(val state: GameState) : OutgoingMessage
    @Serializable
    data class PlayerJoined(val username: String) : OutgoingMessage
    @Serializable
    data class PlayerLeft(val username: String) : OutgoingMessage
    @Serializable
    data class ErrorMessage(val message: String) : OutgoingMessage
    @Serializable
    data class BlindsUp(val smallBlind: Long, val bigBlind: Long, val level: Int) : OutgoingMessage
    @Serializable
    data class TournamentWinner(val winnerUsername: String) : OutgoingMessage
    @Serializable
    data class AllInEquityUpdate(
        val equities: Map<String, Double>, // <UserID, Equity>
        val outs: Map<String, OutsInfo> = emptyMap() // <UserID, OutsInfo>
    ) : OutgoingMessage
    @Serializable
    data class RunItMultipleTimesResult(val results: List<BoardResult>) : OutgoingMessage
    @Serializable
    data class OfferRunItMultipleTimes(val options: List<Int>) : OutgoingMessage
}

// Сообщения, которые клиент отправляет на сервер
@Serializable
sealed interface IncomingMessage {
    @Serializable
    data class Fold(val temp: String = "") : IncomingMessage // temp - временное поле
    @Serializable
    data class Bet(val amount: Long) : IncomingMessage
    @Serializable
    data class Check(val temp: String = "") : IncomingMessage
    @Serializable
    data class SelectRunCount(val times: Int) : IncomingMessage
}