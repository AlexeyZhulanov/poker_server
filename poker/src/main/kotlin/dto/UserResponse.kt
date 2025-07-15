package com.example.dto

import kotlinx.serialization.Serializable

@Serializable
data class UserResponse(
    val id: String,
    val username: String,
    val email: String,
    val cashBalance: Double // Используем Double для простоты сериализации в JSON
)