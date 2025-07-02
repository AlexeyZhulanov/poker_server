package com.example.domain.model

import java.math.BigDecimal
import java.util.UUID

// Модель для передачи данных о пользователе внутри приложения, passwordHash нельзя покидать сервер
data class User(
    val id: UUID,
    val username: String,
    val email: String,
    val passwordHash: String,
    val cashBalance: BigDecimal
)