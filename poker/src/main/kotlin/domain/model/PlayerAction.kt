package com.example.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
sealed interface PlayerAction {
    val id: String // Уникальный ID для отслеживания анимации

    @Serializable
    @SerialName("action.fold")
    data class Fold(override val id: String = UUID.randomUUID().toString()) : PlayerAction

    @Serializable
    @SerialName("action.check")
    data class Check(override val id: String = UUID.randomUUID().toString()) : PlayerAction

    @Serializable
    @SerialName("action.call")
    data class Call(val amount: Long, override val id: String = UUID.randomUUID().toString()) : PlayerAction

    @Serializable
    @SerialName("action.bet")
    data class Bet(val amount: Long, override val id: String = UUID.randomUUID().toString()) : PlayerAction

    @Serializable
    @SerialName("action.raise")
    data class Raise(val amount: Long, override val id: String = UUID.randomUUID().toString()) : PlayerAction

    @Serializable
    @SerialName("action.allin")
    data class AllIn(override val id: String = UUID.randomUUID().toString()) : PlayerAction
}