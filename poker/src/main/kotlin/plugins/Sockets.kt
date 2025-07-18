package com.example.plugins

import com.example.dto.ws.IncomingMessage
import com.example.dto.ws.OutgoingMessage
import com.example.services.GameRoomService
import io.ktor.server.application.*
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.serialization.json.Json
import kotlin.time.Duration.Companion.seconds

fun Application.configureSockets(gameRoomService: GameRoomService) {
    install(WebSockets) {
        pingPeriod = 15.seconds
        timeout = 15.seconds
        maxFrameSize = Long.MAX_VALUE
        masking = false
    }
    routing {
        authenticate("auth-jwt") {
            webSocket("/play/{roomId}") {
                val principal = call.principal<JWTPrincipal>() ?: return@webSocket
                val userId = principal.payload.getClaim("userId").asString()
                val roomId = call.parameters["roomId"] ?: return@webSocket

                val player = gameRoomService.getRoom(roomId)?.players?.find { it.userId == userId }

                if (player == null) {
                    close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "You are not a member of this room."))
                    return@webSocket
                }

                try {
                    // Добавляем сессию в менеджер
                    gameRoomService.onJoin(roomId, userId, this)

                    // Оповещаем всех, что игрок присоединился
                    val playerJoinedMessage = OutgoingMessage.PlayerJoined(player.username)
                    gameRoomService.broadcast(roomId, playerJoinedMessage)

                    // Слушаем входящие сообщения от этого клиента
                    for (frame in incoming) {
                        frame as? Frame.Text ?: continue

                        val engine = gameRoomService.getEngine(roomId)
                        if (engine == null) { /* ... */ return@webSocket }

                        val currentGameState = engine.getCurrentGameState() // Нужно добавить этот метод в GameEngine
                        val activePlayerId = currentGameState.playerStates.getOrNull(currentGameState.activePlayerPosition)?.player?.userId

                        if (activePlayerId != userId) {
                            // Не ход этого игрока, ничего не делаем (или отправляем ошибку)
                            continue
                        }

                        val incomingMessage = Json.decodeFromString<IncomingMessage>(frame.readText())

                        when (incomingMessage) {
                            is IncomingMessage.Fold -> engine.processFold(userId)
                            is IncomingMessage.Bet -> engine.processBet(userId, incomingMessage.amount)
                            is IncomingMessage.Check -> engine.processCheck(userId)
                            is IncomingMessage.Call -> engine.processCall(userId)
                            is IncomingMessage.SelectRunCount -> engine.processRunItSelection(userId, incomingMessage.times)
                            is IncomingMessage.PerformSocialAction -> engine.processSocialAction(userId, incomingMessage.action)
                        }
                    }
                } catch (e: Exception) {
                    println(e.localizedMessage)
                } finally {
                    // При отключении (или ошибке) удаляем сессию и оповещаем остальных
                    gameRoomService.onLeave(roomId, userId)
                    val playerLeftMessage = OutgoingMessage.PlayerLeft(player.username)
                    gameRoomService.broadcast(roomId, playerLeftMessage)
                }
            }
        }
    }
}
