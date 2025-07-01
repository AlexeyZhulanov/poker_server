package com.example.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class GameRoom(
    val roomId: String,
    val name: String,
    val gameMode: GameMode,
    val players: List<Player>,
    val maxPlayers: Int = 9,
    val ownerId: String
)