package com.example.plugins

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.example.data.repository.UserRepository
import com.example.util.UserAttributeKey
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import java.util.UUID

fun Application.configureSecurity() {
    val config = environment.config
    val secret = config.property("jwt.secret").getString()
    val issuer = config.property("jwt.issuer").getString()
    val audience = config.property("jwt.audience").getString()
    val myRealm = config.property("jwt.realm").getString()

    val userRepository = UserRepository()

    install(Authentication) {
        jwt("auth-jwt") {
            realm = myRealm
            verifier(
                JWT.require(Algorithm.HMAC256(secret))
                    .withAudience(audience)
                    .withIssuer(issuer)
                    .build()
            )
            validate { credential ->
                val userIdString = credential.payload.getClaim("userId").asString()
                if (userIdString != null) {
                    val user = userRepository.findById(UUID.fromString(userIdString))
                    if (user != null) {
                        // 1. Кладём нашего пользователя в атрибуты вызова по ключу
                        this.attributes.put(UserAttributeKey, user)
                        // 2. Возвращаем стандартный JWTPrincipal, чтобы подтвердить валидность токена
                        JWTPrincipal(credential.payload)
                    } else {
                        null // Пользователь из токена не найден в БД
                    }
                } else {
                    null // В токене нет userId
                }
            }
        }
    }
}
