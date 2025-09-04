package com.example.plugins

import com.example.dto.ws.IncomingMessage
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
            webSocket("/lobby") {
                val principal = call.principal<JWTPrincipal>()!!
                val userId = principal.payload.getClaim("userId").asString()

                try {
                    // Добавляем пользователя в список "слушающих" лобби
                    gameRoomService.onLobbyJoin(userId, this)
                    // Сразу отправляем ему текущий список комнат
                    gameRoomService.sendLobbyUpdateToOneUser(userId)

                    // Держим соединение открытым, чтобы слушать, пока юзер не отключится
                    for (frame in incoming) { /* Ничего не делаем с входящими */ }
                } finally {
                    // Когда пользователь уходит с экрана лобби, он отключается
                    gameRoomService.onLobbyLeave(userId)
                }
            }
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

                    // Слушаем входящие сообщения от этого клиента
                    for (frame in incoming) {
                        frame as? Frame.Text ?: continue

                        val engine = gameRoomService.getEngine(roomId)

                        val currentGameState = engine?.getCurrentGameState() // Нужно добавить этот метод в GameEngine
                        val activePlayerId = currentGameState?.playerStates?.getOrNull(currentGameState.activePlayerPosition)?.player?.userId

                        val isActiveFlag = activePlayerId == userId

                        val incomingMessage = Json.decodeFromString<IncomingMessage>(frame.readText())

                        when (incomingMessage) {
                            is IncomingMessage.Fold -> if(isActiveFlag) engine.processFold(userId)
                            is IncomingMessage.Bet -> if(isActiveFlag) engine.processBet(userId, incomingMessage.amount)
                            is IncomingMessage.Check -> if(isActiveFlag) engine.processCheck(userId)
                            is IncomingMessage.Call -> if(isActiveFlag) engine.processCall(userId)
                            is IncomingMessage.SelectRunCount -> engine?.processUnderdogRunChoice(userId, incomingMessage.times)
                            is IncomingMessage.AgreeRunCount -> engine?.processFavoriteRunConfirmation(userId, incomingMessage.isAgree)
                            is IncomingMessage.PerformSocialAction -> engine?.processSocialAction(userId, incomingMessage.action)
                            is IncomingMessage.SetReady -> gameRoomService.setPlayerReady(roomId, userId, incomingMessage.isReady)
                            is IncomingMessage.SitAtTable -> gameRoomService.handleSitAtTable(roomId, userId)
                        }
                    }
                } catch (e: Exception) {
                    println(e.localizedMessage)
                } finally {
                    // При отключении (или ошибке) удаляем сессию и оповещаем остальных
                    //gameRoomService.onLeave(roomId, userId)
                    //val playerLeftMessage = OutgoingMessage.PlayerLeft(player.userId)
                    //gameRoomService.broadcast(roomId, playerLeftMessage)
                    // Больше не удаляем игрока из комнаты, а просто регистрируем обрыв связи
                    gameRoomService.onPlayerDisconnected(roomId, userId)
                }
            }
        }
    }
}
