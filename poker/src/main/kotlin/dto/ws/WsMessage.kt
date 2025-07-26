package com.example.dto.ws

import com.example.domain.model.Card
import com.example.domain.model.GameRoom
import com.example.domain.model.GameState
import com.example.domain.model.Player
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed interface OutsInfo {
    @Serializable
    @SerialName("outs.direct")
    data class DirectOuts(val cards: List<Card>) : OutsInfo
    @Serializable
    @SerialName("outs.runner_runner")
    data object RunnerRunner : OutsInfo
    @Serializable
    @SerialName("outs.drawing_dead")
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
    @SerialName("out.game_state")
    data class GameStateUpdate(val state: GameState?) : OutgoingMessage
    @Serializable
    @SerialName("out.player_joined")
    data class PlayerJoined(val player: Player) : OutgoingMessage
    @Serializable
    @SerialName("out.player_left")
    data class PlayerLeft(val userId: String) : OutgoingMessage
    @Serializable
    @SerialName("out.error_message")
    data class ErrorMessage(val message: String) : OutgoingMessage
    @Serializable
    @SerialName("out.blinds_up")
    data class BlindsUp(val smallBlind: Long, val bigBlind: Long, val ante: Long, val level: Int) : OutgoingMessage
    @Serializable
    @SerialName("out.tournament_winner")
    data class TournamentWinner(val winnerUsername: String) : OutgoingMessage
    @Serializable
    @SerialName("out.equity_update")
    data class AllInEquityUpdate(
        val equities: Map<String, Double>, // <UserID, Equity>
        val outs: Map<String, OutsInfo> = emptyMap() // <UserID, OutsInfo>
    ) : OutgoingMessage
    @Serializable
    @SerialName("out.run_multiple_result")
    data class RunItMultipleTimesResult(val results: List<BoardResult>) : OutgoingMessage
    @Serializable
    @SerialName("out.run_multiple_offer")
    data class OfferRunItMultipleTimes(val options: List<Int>) : OutgoingMessage
    @Serializable
    @SerialName("out.social_action_broadcast")
    data class SocialActionBroadcast(
        val fromPlayerId: String, // ID того, кто совершил действие
        val action: SocialAction // Само действие
    ) : OutgoingMessage
    @Serializable
    @SerialName("out.lobby_update")
    data class LobbyUpdate(val rooms: List<GameRoom>) : OutgoingMessage
    @Serializable
    @SerialName("out.player_ready_update")
    data class PlayerReadyUpdate(val userId: String, val isReady: Boolean) : OutgoingMessage
}

// Сообщения, которые клиент отправляет на сервер
@Serializable
sealed interface IncomingMessage {
    @Serializable
    @SerialName("in.fold")
    data class Fold(val temp: String = "") : IncomingMessage // temp - временное поле
    @Serializable
    @SerialName("in.bet")
    data class Bet(val amount: Long) : IncomingMessage
    @Serializable
    @SerialName("in.check")
    data class Check(val temp: String = "") : IncomingMessage
    @Serializable
    @SerialName("in.call")
    data class Call(val temp: String = "") : IncomingMessage
    @Serializable
    @SerialName("in.run_count")
    data class SelectRunCount(val times: Int) : IncomingMessage
    @Serializable
    @SerialName("in.social_action")
    data class PerformSocialAction(val action: SocialAction) : IncomingMessage
    @Serializable
    @SerialName("in.set_ready")
    data class SetReady(val isReady: Boolean) : IncomingMessage
}