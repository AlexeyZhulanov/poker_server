package com.example.services

import com.example.domain.logic.GameEngine
import com.example.domain.model.GameMode
import com.example.domain.model.GameRoom
import com.example.domain.model.Player
import com.example.dto.ws.OutgoingMessage
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import kotlinx.serialization.json.Json
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class GameRoomService {
    // Используем ConcurrentHashMap для потокобезопасного хранения комнат
    private val rooms = ConcurrentHashMap<String, GameRoom>()
    private val members = ConcurrentHashMap<String, ConcurrentHashMap<String, WebSocketSession>>()
    private val engines = ConcurrentHashMap<String, GameEngine>()

    fun createRoom(name: String, mode: GameMode, owner: Player): GameRoom {
        val roomId = UUID.randomUUID().toString()
        val room = GameRoom(
            roomId = roomId,
            name = name,
            gameMode = mode,
            players = listOf(owner),
            ownerId = owner.userId
        )
        rooms[roomId] = room
        // Сразу создаем движок для новой комнаты
        engines[roomId] = GameEngine(room, this)
        return room
    }

    fun getRoom(roomId: String): GameRoom? {
        return rooms[roomId]
    }

    fun getAllRooms(): List<GameRoom> {
        return rooms.values.toList()
    }

    fun joinRoom(roomId: String, player: Player): GameRoom? {
        val room = rooms[roomId] ?: return null
        if (room.players.size >= room.maxPlayers) {
            return null // Комната заполнена
        }
        if (room.players.any { it.userId == player.userId }) {
            return room // Игрок уже в комнате
        }

        val updatedRoom = room.copy(players = room.players + player)
        rooms[roomId] = updatedRoom

        return updatedRoom
    }

    fun onJoin(roomId: String, userId: String, session: WebSocketSession) {
        val roomMembers = members.computeIfAbsent(roomId) { ConcurrentHashMap() }
        roomMembers[userId] = session
    }

    fun onLeave(roomId: String, userId: String) {
        val roomMembers = members[roomId]
        roomMembers?.remove(userId)

        // Если в комнате не осталось активных сессий, можно ее удалить
        if (roomMembers.isNullOrEmpty()) {
            rooms.remove(roomId)
            engines.remove(roomId)?.destroy()
            members.remove(roomId)
        }
    }

    suspend fun broadcast(roomId: String, message: OutgoingMessage) {
        members[roomId]?.values?.forEach { session ->
            val jsonString = Json.encodeToString(OutgoingMessage.serializer(), message)
            session.send(Frame.Text(jsonString))
        }
    }

    suspend fun sendToPlayer(roomId: String, userId: String, message: OutgoingMessage) {
        val session = members[roomId]?.get(userId)
        if (session != null) {
            val jsonString = Json.encodeToString(OutgoingMessage.serializer(), message)
            session.send(Frame.Text(jsonString))
        }
    }

    fun getEngine(roomId: String): GameEngine? = engines[roomId]

    fun getMembers(roomId: String) = members[roomId]
}