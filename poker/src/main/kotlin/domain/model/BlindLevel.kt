package com.example.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class BlindLevel(
    val level: Int,
    val smallBlind: Long,
    val bigBlind: Long,
    val ante: Long = 0L
)