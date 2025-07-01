package com.example.plugins

import com.example.data.entity.Users
import com.example.data.repository.UserRepository
import com.example.domain.model.GameMode
import com.example.domain.model.Player
import com.example.dto.AuthResponse
import com.example.dto.LoginRequest
import com.example.dto.RegisterRequest
import com.example.services.GameRoomService
import com.example.services.TokenService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.*
import io.ktor.server.routing.*
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

                // 1. Проверяем, не занят ли username или email
                val existingUser = userRepository.findByUsernameOrEmail(request.username, request.email)
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
                val userId = newUser[Users.id]
                val (accessToken, refreshToken) = tokenService.generateTokens(userId)

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
                val passwordMatches = BCrypt.checkpw(request.password, user[Users.passwordHash])
                if (!passwordMatches) {
                    call.respond(HttpStatusCode.Unauthorized, "Invalid username or password")
                    return@post
                }

                // 3. Генерируем новую пару токенов
                val userId = user[Users.id]
                val (accessToken, refreshToken) = tokenService.generateTokens(userId)

                call.respond(HttpStatusCode.OK, AuthResponse(accessToken, refreshToken))
            }
        }

        authenticate("auth-jwt") {
            get("/me") {
                // Если мы попали сюда, значит токен валиден.
                // Ktor уже извлек из него данные и положил в call.principal()
                val principal = call.principal<JWTPrincipal>()
                val userId = principal?.payload?.getClaim("userId")?.asString()

                // Здесь можно по userId сходить в БД и вернуть полную информацию
                // Но пока просто вернем ID из токена
                call.respondText("Hello, you are an authenticated user with ID: $userId")
            }

            route("/rooms") {
                // Получить список всех комнат
                get {
                    call.respond(gameRoomService.getAllRooms())
                }

                // Создать новую комнату
                post {
                    val principal = call.principal<JWTPrincipal>() ?: return@post
                    val userId = principal.payload.getClaim("userId").asString()
                    val user = userRepository.findById(UUID.fromString(userId))

                    if (user == null) {
                        call.respond(HttpStatusCode.NotFound, "User not found")
                        return@post
                    }

                    // Для простоты пока создаем комнату с фиксированными параметрами
                    val ownerAsPlayer = Player(userId = userId, username = user[Users.username], stack = 1000)
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
                    // todo дублирование 12 строк кода - убрать
                    val principal = call.principal<JWTPrincipal>() ?: return@post
                    val userId = principal.payload.getClaim("userId").asString()

                    // 2. Получаем данные пользователя из БД
                    val user = userRepository.findById(UUID.fromString(userId))
                    if (user == null) {
                        call.respond(HttpStatusCode.NotFound, "User not found")
                        return@post
                    }

                    // 3. Создаем объект Player и пытаемся присоединиться к комнате
                    val player = Player(userId = userId, username = user[Users.username], stack = 1000)
                    val updatedRoom = gameRoomService.joinRoom(roomId, player)

                    // 4. Отправляем ответ
                    if (updatedRoom == null) {
                        call.respond(HttpStatusCode.NotFound, "Room not found or is full")
                    } else {
                        // TODO: Оповестить других игроков в комнате о новом участнике через WebSocket
                        call.respond(HttpStatusCode.OK, updatedRoom)
                    }
                }
            }
        }
    }
}
