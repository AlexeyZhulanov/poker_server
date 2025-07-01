package com.example.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class GameState(
    val roomId: String,
    val stage: GameStage = GameStage.PRE_FLOP,
    val communityCards: List<Card> = emptyList(),
    val pot: Long = 0,
    val playerStates: List<PlayerState> = emptyList(),
    val dealerPosition: Int = 0,
    val activePlayerPosition: Int = 0,
    val lastRaiseAmount: Long = 0
)