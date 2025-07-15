package com.example.dto

import com.example.domain.model.GameMode
import kotlinx.serialization.Serializable

@Serializable
data class CreateRoomRequest(
    val name: String,
    val gameMode: GameMode,
    val maxPlayers: Int,
    val initialStack: Long,
    // Поля для кэш-игры
    val smallBlind: Long?,
    val bigBlind: Long?,
    // Поля для турнира
    val levelDurationMinutes: Int?
    // todo Сюда же можно будет добавить структуру блайндов для турнира
)