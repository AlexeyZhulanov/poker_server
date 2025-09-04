package com.example.dto

import com.example.domain.model.BlindStructureType
import com.example.domain.model.GameMode
import kotlinx.serialization.Serializable

@Serializable
data class CreateRoomRequest(
    val name: String,
    val gameMode: GameMode,
    val maxPlayers: Int,
    val smallBlind: Long?,
    val bigBlind: Long?,
    val blindStructureType: BlindStructureType? = null
)