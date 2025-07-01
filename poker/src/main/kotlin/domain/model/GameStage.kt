package com.example.domain.model

import kotlinx.serialization.Serializable

@Serializable
enum class GameStage {
    PRE_FLOP,
    FLOP,
    TURN,
    RIVER,
    SHOWDOWN
}