package com.example.data.predefined

import com.example.domain.model.BlindLevel

object BlindStructures {
    // Уровни для стандартного турнира (условно, 10-15 минут на уровень)
    val standard: List<BlindLevel> = listOf(
        BlindLevel(1, 25, 50),
        BlindLevel(2, 50, 100),
        BlindLevel(3, 75, 150),
        BlindLevel(4, 100, 200),
        BlindLevel(5, 150, 300, 25),
        BlindLevel(6, 200, 400, 50),
        BlindLevel(7, 300, 600, 75),
        BlindLevel(8, 400, 800, 100),
        BlindLevel(9, 600, 1200, 150),
        BlindLevel(10, 800, 1600, 200),
        BlindLevel(11, 1000, 2000, 300),
        BlindLevel(12, 1500, 3000, 400)
    )

    // Уровни для быстрого турнира (условно, 5-7 минут на уровень)
    val fast: List<BlindLevel> = listOf(
        BlindLevel(1, 25, 50),
        BlindLevel(2, 50, 100),
        BlindLevel(3, 100, 200),
        BlindLevel(4, 150, 300, 25),
        BlindLevel(5, 200, 400, 50),
        BlindLevel(6, 400, 800, 100),
        BlindLevel(7, 600, 1200, 150),
        BlindLevel(8, 800, 1600, 200),
        BlindLevel(9, 1200, 2400, 300),
        BlindLevel(10, 1500, 3000, 400)
    )

    // Уровни для турбо-турнира (условно, 2-3 минуты на уровень)
    val turbo: List<BlindLevel> = listOf(
        BlindLevel(1, 50, 100),
        BlindLevel(2, 100, 200),
        BlindLevel(3, 200, 400, 50),
        BlindLevel(4, 300, 600, 75),
        BlindLevel(5, 500, 1000, 100),
        BlindLevel(6, 800, 1600, 200),
        BlindLevel(7, 1200, 2400, 300),
        BlindLevel(8, 1500, 3000, 400)
    )
}