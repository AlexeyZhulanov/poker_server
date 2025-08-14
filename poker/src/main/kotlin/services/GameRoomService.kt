package com.example.services

import com.example.data.predefined.BlindStructures
import com.example.domain.logic.GameEngine
import com.example.domain.model.BlindStructureType
import com.example.domain.model.GameMode
import com.example.domain.model.GameRoom
import com.example.domain.model.Player
import com.example.domain.model.PlayerState
import com.example.domain.model.PlayerStatus
import com.example.dto.CreateRoomRequest
import com.example.dto.ws.OutgoingMessage
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
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
    private val reconnectionTimers = ConcurrentHashMap<String, Job>()
    private val playerLocations = ConcurrentHashMap<String, String>()

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
        if (room.players.any { it.userId == player.userId }) {
            return room // Игрок уже в комнате
        }
        // Игрок всегда входит как SPECTATING
        val playerAsSpectator = player.copy(status = PlayerStatus.SPECTATING)
        val updatedRoom = room.copy(players = room.players + playerAsSpectator)
        rooms[roomId] = updatedRoom
        launch { broadcastLobbyUpdate() } // Оповещаем лобби об изменении состава комнаты
        return updatedRoom
    }

    fun onJoin(roomId: String, userId: String, session: WebSocketSession) {
        val roomMembers = members.computeIfAbsent(roomId) { ConcurrentHashMap() }
        roomMembers[userId] = session
        playerLocations[userId] = roomId
        // Если для этого игрока был запущен таймер, отменяем его
        reconnectionTimers[userId]?.cancel()
        reconnectionTimers.remove(userId)
    }

    fun onPlayerDisconnected(roomId: String, userId: String) {
        members[roomId]?.remove(userId)
        // Запускаем таймер на 30 секунд. Если игрок не вернется, он будет удален из комнаты.
        val job = launch {
            println("Player $userId disconnected. Starting 30s reconnect timer...")
            delay(30_000L)
            println("Player $userId reconnect timer expired. Removing from room.")
            onLeave(roomId, userId) // Вызываем полный выход по истечении таймера
        }
        reconnectionTimers[userId] = job
    }

    fun incrementMissedTurns(roomId: String, userId: String) {
        val room = rooms[roomId] ?: return
        val updatedPlayers = room.players.map {
            if (it.userId == userId) it.copy(missedTurns = it.missedTurns + 1) else it
        }
        rooms[roomId] = room.copy(players = updatedPlayers)
    }

    fun resetMissedTurns(roomId: String, userId: String) {
        val room = rooms[roomId] ?: return
        val updatedPlayers = room.players.map {
            if (it.userId == userId) it.copy(missedTurns = 0) else it
        }
        rooms[roomId] = room.copy(players = updatedPlayers)
    }

    private fun onLeave(roomId: String, userId: String) {
        val roomMembers = members[roomId]
        roomMembers?.remove(userId)
        playerLocations.remove(userId)

        val room = rooms[roomId] ?: return
        val players = room.players.toMutableList()
        val updatedRoom = room.copy(players = players.filterNot { it.userId == userId })
        rooms[roomId] = updatedRoom

        engines[roomId]?.handlePlayerDisconnect(userId)

        // Если в комнате не осталось активных сессий, можно ее удалить
        if (roomMembers.isNullOrEmpty()) {
            rooms.remove(roomId)
            engines.remove(roomId)?.destroy()
            members.remove(roomId)
        }
        launch { broadcastLobbyUpdate() } // Оповещаем лобби, что комната могла удалиться или состав изменился
    }

    suspend fun updatePlayerStatus(roomId: String, userId: String, newStatus: PlayerStatus, newStack: Long) {
        val room = rooms[roomId] ?: return

        // Находим и обновляем нужного игрока в списке
        val updatedPlayers = room.players.map { player ->
            if (player.userId == userId) {
                player.copy(status = newStatus, stack = newStack, isReady = false) // Сбрасываем готовность
            } else {
                player
            }
        }

        // Обновляем комнату в нашем хранилище
        rooms[roomId] = room.copy(players = updatedPlayers)

        // Рассылаем всем в комнате сообщение об изменении статуса
        broadcast(roomId, OutgoingMessage.PlayerStatusUpdate(userId, newStatus, newStack))
    }

    fun updatePlayerStatesInRoom(roomId: String, finalPlayerStates: List<PlayerState>) {
        val room = rooms[roomId] ?: return

        // Создаем Map для быстрого доступа к финальным стекам и статусам
        val finalStatesMap = finalPlayerStates.associateBy { it.player.userId }

        // Обновляем "главный" список игроков в комнате
        val updatedPlayers = room.players.map { player ->
            finalStatesMap[player.userId]?.player ?: player
        }

        rooms[roomId] = room.copy(players = updatedPlayers)
    }

    suspend fun handleSitAtTable(roomId: String, userId: String, buyIn: Long) {
        val room = rooms[roomId] ?: return

        val playersAtTable = room.players.count { it.status != PlayerStatus.SPECTATING }
        if (playersAtTable >= room.maxPlayers) {
            // Мест нет, отправляем ошибку
            sendToPlayer(roomId, userId, OutgoingMessage.ErrorMessage("The table is full."))
            return
        }

        var targetPlayer: Player? = null

        // Обновляем список игроков, меняя статус и стек нужного игрока
        val updatedPlayers = room.players.map {
            if (it.userId == userId) {
                targetPlayer = it.copy(status = PlayerStatus.SITTING_OUT, stack = buyIn)
                targetPlayer
            } else {
                it
            }
        }

        // Если игрок был найден и обновлен
        if (targetPlayer != null) {
            val updatedRoom = room.copy(players = updatedPlayers)
            rooms[roomId] = updatedRoom

            // Рассылаем всем обновление статуса и стека этого игрока
            broadcast(
                roomId,
                OutgoingMessage.PlayerStatusUpdate(userId, PlayerStatus.SITTING_OUT, buyIn)
            )
            // Рассылаем обновление в лобби (изменилось количество активных игроков)
            broadcastLobbyUpdate()
        }
    }

    fun setAllPlayersUnready(roomId: String) {
        val room = rooms[roomId] ?: return
        val updatedPlayers = room.players.map { it.copy(
                isReady = false,
                status = if(it.status == PlayerStatus.IN_HAND) PlayerStatus.SITTING_OUT else it.status
            ) }
        val updatedRoom = room.copy(players = updatedPlayers)
        rooms[roomId] = updatedRoom
    }

    suspend fun setPlayerReady(roomId: String, userId: String, isReady: Boolean) {
        println("User $userId is Ready: $isReady")
        val room = rooms[roomId] ?: return
        val updatedPlayers = room.players.map { if (it.userId == userId) it.copy(
            isReady = isReady,
            status = if (it.status == PlayerStatus.SPECTATING) it.status else PlayerStatus.SITTING_OUT
        ) else it }
        val updatedRoom = room.copy(players = updatedPlayers)
        rooms[roomId] = updatedRoom

        // Оповещаем всех об изменении статуса
        broadcast(roomId, OutgoingMessage.PlayerReadyUpdate(userId, isReady))

        // 1. Фильтруем игроков, чтобы остались только те, кто за столом
        val playersAtTable = updatedRoom.players.filter { it.status != PlayerStatus.SPECTATING }

        // 2. Проверяем, что за столом достаточно игроков и ВСЕ ОНИ готовы
        if (playersAtTable.size >= 2 && playersAtTable.all { it.isReady }) {
            // Устанавливаем статус IN_HAND для всех, кто готов и сидит за столом
            val finalPlayers = updatedRoom.players.map {
                if(it.status != PlayerStatus.SPECTATING && it.isReady) it.copy(status = PlayerStatus.IN_HAND) else it
            }
            rooms[roomId] = updatedRoom.copy(players = finalPlayers)

            engines[roomId]?.startGame()
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

    fun getPaginatedRooms(page: Int, limit: Int): List<GameRoom> {
        return rooms.values
            .toList()
            .drop((page - 1) * limit)
            .take(limit)
    }

    fun onLobbyJoin(userId: String, session: WebSocketSession) {
        // Проверяем, был ли игрок в какой-то комнате
        val previousRoomId = playerLocations[userId]
        if (previousRoomId != null) {
            // Если был, то немедленно удаляем его из той комнаты
            println("Player $userId connected to lobby, removing from room $previousRoomId")
            reconnectionTimers[userId]?.cancel()
            reconnectionTimers.remove(userId)
            onLeave(previousRoomId, userId)
        }
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