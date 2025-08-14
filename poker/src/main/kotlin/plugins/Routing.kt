package com.example.plugins

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.example.data.repository.UserRepository
import com.example.domain.model.Player
import com.example.dto.AuthResponse
import com.example.dto.CreateRoomRequest
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
import io.ktor.server.request.header
import io.ktor.server.request.receive
import io.ktor.server.request.receiveText
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.Json
import org.mindrot.jbcrypt.BCrypt
import java.util.UUID

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

            post("/refresh") {
                // 1. Получаем refresh-токен из заголовка Authorization
                val refreshToken = call.request.header("Authorization")?.removePrefix("Bearer ")
                if (refreshToken == null) {
                    call.respond(HttpStatusCode.BadRequest, "Refresh token is missing")
                    return@post
                }

                // 2. Создаем верификатор с теми же параметрами, что и при создании токена
                val secret = environment.config.property("jwt.secret").getString()
                val issuer = environment.config.property("jwt.issuer").getString()
                val audience = environment.config.property("jwt.audience").getString()

                val verifier = JWT.require(Algorithm.HMAC256(secret))
                    .withAudience(audience)
                    .withIssuer(issuer)
                    .build()

                try {
                    // 3. Проверяем токен. Если он невалиден (подпись, срок действия), будет выброшено исключение.
                    val decodedJWT = verifier.verify(refreshToken)

                    // 4. Если токен валиден, извлекаем userId
                    val userIdString = decodedJWT.getClaim("userId").asString()
                    val userId = UUID.fromString(userIdString)

                    // 5. Генерируем новую пару токенов
                    val (newAccessToken, newRefreshToken) = tokenService.generateTokens(userId)

                    call.respond(HttpStatusCode.OK, AuthResponse(newAccessToken, newRefreshToken))

                } catch (_: Exception) {
                    // 6. В случае ошибки (невалидный токен) отправляем 401 Unauthorized
                    call.respond(HttpStatusCode.Unauthorized, "Invalid refresh token")
                }
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
                    val rawJsonBody = call.receiveText()
                    println("Received raw JSON for /rooms: $rawJsonBody")

                    val request = Json.decodeFromString<CreateRoomRequest>(rawJsonBody) // Получаем DTO из тела запроса
                    val user = call.attributes[UserAttributeKey]

                    // Стек игрока берем из запроса
                    val ownerAsPlayer = Player(
                        userId = user.id.toString(),
                        username = user.username,
                        stack = request.initialStack
                    )

                    val newRoom = gameRoomService.createRoom(request, ownerAsPlayer)
                    call.respond(HttpStatusCode.Created, newRoom)
                }

                get("/{roomId}") {
                    val roomId = call.parameters["roomId"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                    val room = gameRoomService.getRoom(roomId)
                    if (room != null) {
                        call.respond(HttpStatusCode.OK, room)
                    } else {
                        call.respond(HttpStatusCode.NotFound)
                    }
                }

                get("/{roomId}/state") {
                    val roomId = call.parameters["roomId"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                    val user = call.attributes[UserAttributeKey]

                    val engine = gameRoomService.getEngine(roomId)
                    if (engine == null) {
                        call.respond(HttpStatusCode.NotFound, "Game not active in this room.")
                        return@get
                    }

                    // Получаем персонализированное состояние игры для запросившего пользователя
                    val personalizedState = engine.getPersonalizedGameStateFor(user.id.toString())
                    if(personalizedState == null) {
                        call.respond(HttpStatusCode.NotFound, "Game not active in this room.")
                        return@get
                    }
                    call.respond(HttpStatusCode.OK, personalizedState)
                }

                // Присоединиться к комнате
                post("/{roomId}/join") {
                    // 1. Получаем ID комнаты из URL и ID пользователя из токена
                    val roomId = call.parameters["roomId"]
                    if (roomId == null) {
                        call.respond(HttpStatusCode.BadRequest, "Room ID is missing")
                        println("400 room id is missing")
                        return@post
                    }
                    val user = call.attributes[UserAttributeKey]

                    // 3. Создаем объект Player и пытаемся присоединиться к комнате
                    val player = Player(userId = user.id.toString(), username = user.username, stack = 1000) // todo stack count
                    val updatedRoom = gameRoomService.joinRoom(roomId, player)
                    println("updated room: $updatedRoom")
                    // 4. Отправляем ответ
                    if (updatedRoom == null) {
                        call.respond(HttpStatusCode.NotFound, "Room not found or is full")
                    } else {
                        val playerJoinedMessage = OutgoingMessage.PlayerJoined(player)
                        gameRoomService.broadcast(roomId, playerJoinedMessage)
                        call.respond(HttpStatusCode.OK, updatedRoom)
                    }
                }
            }
        }
    }
}
