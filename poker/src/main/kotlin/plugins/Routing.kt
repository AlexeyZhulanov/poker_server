package com.example.plugins

import com.example.data.entity.Users
import com.example.data.repository.UserRepository
import com.example.dto.AuthResponse
import com.example.dto.RegisterRequest
import com.example.services.TokenService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
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
        }
    }
}
