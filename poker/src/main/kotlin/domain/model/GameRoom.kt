package com.example.domain.model

import kotlinx.collections.immutable.ImmutableList
import kotlinx.serialization.Serializable

@Serializable
data class GameRoom(
    val roomId: String,
    val name: String,
    val gameMode: GameMode,
    val players: ImmutableList<Player>,
    val maxPlayers: Int = 9,
    val ownerId: String,
    val buyIn: Long,
    val blindStructureType: BlindStructureType? = null,
    val blindStructure: ImmutableList<BlindLevel>? = null
)