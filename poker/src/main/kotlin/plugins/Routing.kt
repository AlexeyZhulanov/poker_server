package com.example.plugins

import com.example.data.repository.UserRepository
import com.example.domain.model.GameMode
import com.example.domain.model.Player
import com.example.dto.AuthResponse
import com.example.dto.LoginRequest
import com.example.dto.RegisterRequest
import com.example.dto.UserResponse
import com.example.dto.ws.OutgoingMessage
import com.example.services.GameRoomService
import com.example.services.TokenService
import com.example.util.UserAttributeKey
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.mindrot.jbcrypt.BCrypt

fun Application.configureRouting(gameRoomService: GameRoomService) {
    val userRepository = UserRepository()
    val tokenService = TokenService(environment.config)

    routing {
        // Группа эндпоинтов для аутентификации
        route("/auth") {

            post("/register") {
                val request = call.receive<RegisterRequest>()

                // 1. Проверяем, не занят ли username
                val existingUser = userRepository.findByUsername(request.username)
                if (existingUser != null) {
                    call.respond(HttpStatusCode.Conflict, "User with such username or email already exists.")
                    return@post
                }

                // 2. Хэшируем пароль
                val hashedPassword = BCrypt.hashpw(request.password, BCrypt.gensalt())

                // 3. Создаем пользователя
                val newUser = userRepository.create(request, hashedPassword)
                if (newUser == null) {
                    call.respond(HttpStatusCode.InternalServerError, "Failed to create user.")
                    return@post
                }

                // 4. Генерируем JWT-токены
                val (accessToken, refreshToken) = tokenService.generateTokens(newUser.id)

                call.respond(HttpStatusCode.Created, AuthResponse(accessToken, refreshToken))
            }

            post("/login") {
                val request = call.receive<LoginRequest>()

                // 1. Ищем пользователя в БД
                val user = userRepository.findByUsername(request.username)
                if (user == null) {
                    call.respond(HttpStatusCode.Unauthorized, "Invalid username or password")
                    return@post
                }

                // 2. Проверяем пароль
                val passwordMatches = BCrypt.checkpw(request.password, user.passwordHash)
                if (!passwordMatches) {
                    call.respond(HttpStatusCode.Unauthorized, "Invalid username or password")
                    return@post
                }

                // 3. Генерируем новую пару токенов
                val (accessToken, refreshToken) = tokenService.generateTokens(user.id)

                call.respond(HttpStatusCode.OK, AuthResponse(accessToken, refreshToken))
            }
        }

        authenticate("auth-jwt") {
            get("/me") {
                // Получаем пользователя напрямую из атрибутов
                val user = call.attributes[UserAttributeKey]
                val userResponse = UserResponse(
                    id = user.id.toString(),
                    username = user.username,
                    email = user.email,
                    cashBalance = user.cashBalance.toDouble()
                )
                call.respond(HttpStatusCode.OK, userResponse)
            }

            route("/rooms") {
                // Получить список комнат с пагинацией
                get {
                    val page = call.request.queryParameters["page"]?.toIntOrNull() ?: 1
                    val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 10

                    val rooms = gameRoomService.getPaginatedRooms(page, limit)
                    call.respond(HttpStatusCode.OK, rooms)
                }

                // Создать новую комнату
                post {
                    val user = call.attributes[UserAttributeKey]
                    val ownerAsPlayer = Player(userId = user.id.toString(), username = user.username, stack = 1000)
                    val newRoom = gameRoomService.createRoom("New Room", GameMode.CASH, ownerAsPlayer)
                    call.respond(HttpStatusCode.Created, newRoom)
                }

                // Присоединиться к комнате
                post("/{roomId}/join") {
                    // 1. Получаем ID комнаты из URL и ID пользователя из токена
                    val roomId = call.parameters["roomId"]
                    if (roomId == null) {
                        call.respond(HttpStatusCode.BadRequest, "Room ID is missing")
                        return@post
                    }
                    val user = call.attributes[UserAttributeKey]

                    // 3. Создаем объект Player и пытаемся присоединиться к комнате
                    val player = Player(userId = user.id.toString(), username = user.username, stack = 1000)
                    val updatedRoom = gameRoomService.joinRoom(roomId, player)

                    // 4. Отправляем ответ
                    if (updatedRoom == null) {
                        call.respond(HttpStatusCode.NotFound, "Room not found or is full")
                    } else {
                        val playerJoinedMessage = OutgoingMessage.PlayerJoined(player.username)
                        gameRoomService.broadcast(roomId, playerJoinedMessage)
                        call.respond(HttpStatusCode.OK, updatedRoom)
                    }
                }

                // Запустить игру в комнате
                post("/{roomId}/start") {
                    val roomId = call.parameters["roomId"] ?: return@post call.respond(HttpStatusCode.BadRequest)

                    val principal = call.principal<JWTPrincipal>() ?: return@post
                    val userId = principal.payload.getClaim("userId").asString()

                    val room = gameRoomService.getRoom(roomId)
                    if (room == null) {
                        call.respond(HttpStatusCode.NotFound, "Room not found")
                        return@post
                    }

                    if (room.ownerId != userId) {
                        call.respond(HttpStatusCode.Forbidden, "Only the room owner can start the game.")
                        return@post
                    }

                    val engine = gameRoomService.getEngine(roomId)
                    if (engine == null) {
                        call.respond(HttpStatusCode.InternalServerError, "Game engine not found")
                        return@post
                    } else {
                        if(engine.getCountPlayers() < 2) {
                            call.respond(HttpStatusCode.InternalServerError, "Players < 2")
                            return@post
                        }
                    }
                    engine.startGame()
                    call.respond(HttpStatusCode.OK, "Game started")
                }
            }
        }
    }
}
