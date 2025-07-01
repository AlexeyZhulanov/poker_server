package com.example.plugins

import com.example.data.entity.Users
import com.example.data.repository.UserRepository
import com.example.dto.AuthResponse
import com.example.dto.LoginRequest
import com.example.dto.RegisterRequest
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

fun Application.configureRouting() {
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
        }
    }
}
