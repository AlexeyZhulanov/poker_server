package com.example.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class Player(
    val userId: String,
    val username: String,
    val stack: Long,
    val status: PlayerStatus = PlayerStatus.SPECTATING,
    val isReady: Boolean = false,
    val missedTurns: Int = 0,
    val isConnected: Boolean = true
)