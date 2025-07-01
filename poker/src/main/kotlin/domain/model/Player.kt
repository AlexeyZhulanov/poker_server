package com.example.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class Player(
    val userId: String,
    val username: String,
    val stack: Long // Стек фишек игрока за этим столом
)