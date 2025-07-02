package com.example.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class PlayerState(
    val player: Player,
    val cards: List<Card> = emptyList(), // Карты игрока (для отправки только ему)
    val currentBet: Long = 0, // Ставка на текущей улице
    val handContribution: Long = 0, // Общий вклад в банк за всю раздачу
    val hasFolded: Boolean = false,
    val isAllIn: Boolean = false
)