package com.example.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class GameRoom(
    val roomId: String,
    val name: String,
    val gameMode: GameMode,
    var players: List<Player>,
    val maxPlayers: Int = 9,
    val ownerId: String,
    val blindStructure: List<BlindLevel>? = null,
    val levelDurationMinutes: Int? = null,
)