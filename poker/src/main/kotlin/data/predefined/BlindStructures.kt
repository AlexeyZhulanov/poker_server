package com.example.data.predefined

import com.example.domain.model.BlindLevel

object BlindStructures {
    val standardTournament: List<BlindLevel> = listOf(
        BlindLevel(level = 1, smallBlind = 10, bigBlind = 20),
        BlindLevel(level = 2, smallBlind = 15, bigBlind = 30),
        BlindLevel(level = 3, smallBlind = 25, bigBlind = 50),
        BlindLevel(level = 4, smallBlind = 50, bigBlind = 100),
        BlindLevel(level = 5, smallBlind = 75, bigBlind = 150),
        BlindLevel(level = 6, smallBlind = 100, bigBlind = 200)
        // todo ... и так далее
    )
}