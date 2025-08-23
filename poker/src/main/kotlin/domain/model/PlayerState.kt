package com.example.domain.model

import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.serialization.Serializable

@Serializable
data class PlayerState(
    val player: Player,
    val cards: ImmutableList<Card> = persistentListOf(), // Карты игрока (для отправки только ему)
    val currentBet: Long = 0, // Ставка на текущей улице
    val handContribution: Long = 0, // Общий вклад в банк за всю раздачу
    val hasFolded: Boolean = false,
    val hasActedThisRound: Boolean = false,
    val isAllIn: Boolean = false,
    val lastAction: PlayerAction? = null
)