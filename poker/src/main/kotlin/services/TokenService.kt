package com.example.services

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.server.config.ApplicationConfig
import java.util.Date
import java.util.UUID

class TokenService(private val config: ApplicationConfig) {
    private val secret = config.property("jwt.secret").getString()
    private val issuer = config.property("jwt.issuer").getString()
    private val audience = config.property("jwt.audience").getString()
    private val accessTokenExpiresIn = config.property("jwt.accessTokenExpiresIn").getString().toLong()
    private val refreshTokenExpiresIn = config.property("jwt.refreshTokenExpiresIn").getString().toLong()

    fun generateTokens(userId: UUID): Pair<String, String> {
        val accessToken = JWT.create()
            .withAudience(audience)
            .withIssuer(issuer)
            .withClaim("userId", userId.toString())
            .withExpiresAt(Date(System.currentTimeMillis() + accessTokenExpiresIn))
            .sign(Algorithm.HMAC256(secret))

        val refreshToken = JWT.create()
            .withAudience(audience)
            .withIssuer(issuer)
            .withClaim("userId", userId.toString())
            .withExpiresAt(Date(System.currentTimeMillis() + refreshTokenExpiresIn))
            .sign(Algorithm.HMAC256(secret))

        return Pair(accessToken, refreshToken)
    }
}