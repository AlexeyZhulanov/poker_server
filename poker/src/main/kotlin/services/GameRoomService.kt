package com.example.services

import com.example.data.predefined.BlindStructures
import com.example.domain.logic.GameEngine
import com.example.domain.model.BlindStructureType
import com.example.domain.model.GameMode
import com.example.domain.model.GameRoom
import com.example.domain.model.Player
import com.example.dto.CreateRoomRequest
import com.example.dto.ws.OutgoingMessage
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class GameRoomService : CoroutineScope {
    // Создаем Job + Dispatcher. SupervisorJob нужен, чтобы ошибка
    // в одной корутине (например, при рассылке) не отменила все остальные.
    override val coroutineContext = SupervisorJob() + Dispatchers.IO

    // Используем ConcurrentHashMap для потокобезопасного хранения комнат
    private val rooms = ConcurrentHashMap<String, GameRoom>()
    private val members = ConcurrentHashMap<String, ConcurrentHashMap<String, WebSocketSession>>()
    private val engines = ConcurrentHashMap<String, GameEngine>()
    private val lobbySubscribers = ConcurrentHashMap<String, WebSocketSession>()

    fun createRoom(request: CreateRoomRequest, owner: Player): GameRoom {
        val roomId = UUID.randomUUID().toString()

        // Определяем структуру блайндов в зависимости от режима
        val blindStructure = if (request.gameMode == GameMode.TOURNAMENT) {
            // Выбираем структуру на основе запроса
            when (request.blindStructureType) {
                BlindStructureType.FAST -> BlindStructures.fast
                BlindStructureType.TURBO -> BlindStructures.turbo
                else -> BlindStructures.standard // STANDARD будет по умолчанию
            }
        } else null

        // Создаем комнату, используя данные из запроса
        val room = GameRoom(
            roomId = roomId,
            name = request.name,
            gameMode = request.gameMode,
            players = listOf(owner), // Владелец сразу становится первым игроком
            maxPlayers = request.maxPlayers,
            ownerId = owner.userId,
            blindStructure = blindStructure,
            blindStructureType = request.blindStructureType
        )
        rooms[roomId] = room

        // Создаем движок, передавая ему полную информацию о комнате
        engines[roomId] = GameEngine(roomId, this)

        launch { broadcastLobbyUpdate() }
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

        launch { broadcastLobbyUpdate() } // Оповещаем лобби об изменении состава комнаты
        return updatedRoom
    }

    fun onJoin(roomId: String, userId: String, session: WebSocketSession) {
        val roomMembers = members.computeIfAbsent(roomId) { ConcurrentHashMap() }
        roomMembers[userId] = session
    }

    fun onLeave(roomId: String, userId: String) {
        val roomMembers = members[roomId]
        roomMembers?.remove(userId)
        engines[roomId]?.handlePlayerDisconnect(userId)

        // Если в комнате не осталось активных сессий, можно ее удалить
        if (roomMembers.isNullOrEmpty()) {
            rooms.remove(roomId)
            engines.remove(roomId)?.destroy()
            members.remove(roomId)
        }
        launch { broadcastLobbyUpdate() } // Оповещаем лобби, что комната могла удалиться или состав изменился
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

    fun getPaginatedRooms(page: Int, limit: Int): List<GameRoom> {
        return rooms.values
            .toList()
            .drop((page - 1) * limit)
            .take(limit)
    }

    fun onLobbyJoin(userId: String, session: WebSocketSession) {
        lobbySubscribers[userId] = session
    }

    fun onLobbyLeave(userId: String) {
        lobbySubscribers.remove(userId)
    }

    // Отправка обновления лобби одному пользователю (когда он только зашел)
    suspend fun sendLobbyUpdateToOneUser(userId: String) {
        val session = lobbySubscribers[userId]
        if (session != null) {
            val message = OutgoingMessage.LobbyUpdate(getAllRooms())
            val jsonString = Json.encodeToString(OutgoingMessage.serializer(), message)
            session.send(Frame.Text(jsonString))
        }
    }

    // Рассылка обновления всем в лобби
    private suspend fun broadcastLobbyUpdate() {
        if (lobbySubscribers.isEmpty()) return // Нечего делать, если в лобби никого нет

        val message = OutgoingMessage.LobbyUpdate(getAllRooms())
        val jsonString = Json.encodeToString(OutgoingMessage.serializer(), message)

        lobbySubscribers.values.forEach { session ->
            session.send(Frame.Text(jsonString))
        }
    }
}