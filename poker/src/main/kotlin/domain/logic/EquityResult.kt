package com.example.domain.logic

import com.example.domain.logic.HandEvaluator.evaluate
import com.example.domain.model.Card
import kotlin.math.roundToInt

data class EquityResult(val wins: List<Double>)

fun calculateEquity(
    players: List<List<Card>>,
    community: List<Card>,
    iterations: Int = 10_000
): EquityResult {
    val usedCards = players.flatten() + community
    val deck = CardDeck.buildFullDeck().filter { it !in usedCards }.toMutableList()

    val equityShares = DoubleArray(players.size) { 0.0 }
    val missing = 5 - community.size

    (1..iterations).forEach { _ ->
        deck.shuffle()
        val remainingCommunity = deck.take(missing)
        val fullBoard = community + remainingCommunity

        // Оцениваем руки всех игроков
        val hands = players.map { evaluate(it + fullBoard) }
        val maxRank = hands.maxOrNull() ?: return@forEach // Находим ранг лучшей руки (return работает как continue)

        // Находим ВСЕХ игроков с лучшей рукой
        val winnerIndices = hands.mapIndexedNotNull { index, rank ->
            if (rank == maxRank) index else null
        }

        if (winnerIndices.isNotEmpty()) {
            // Распределяем "1 очко" победы между всеми победителями
            val share = 1.0 / winnerIndices.size
            winnerIndices.forEach { winnerIndex ->
                equityShares[winnerIndex] += share
            }
        }
    }

    // Конвертируем долю в итоговый процент
    return EquityResult(
        wins = equityShares.map { (it * 100.0 / iterations * 100).roundToInt() / 100.0 }
    )
}

fun calculateLiveOuts(
    player: List<Card>,
    opponents: List<List<Card>>,
    community: List<Card>
): Pair<List<Card>, Boolean> {
    if (community.isEmpty()) return Pair(emptyList(), false)

    val usedCards = player + community + opponents.flatten()
    val deck = CardDeck.buildFullDeck().filter { it !in usedCards }
    val missing = 5 - community.size

    if (missing == 0) return Pair(emptyList(), false)

    val outs = mutableSetOf<Card>()
    var foundAnyIndirectOuts = false

    for (card in deck) {
        val boardWithOne = community + card
        val playerHand = evaluate(player + boardWithOne)
        val opponentHands = opponents.map { evaluate(it + boardWithOne) }
        val bestOpponent = opponentHands.maxByOrNull { it } ?: continue

        if (playerHand > bestOpponent) {
            outs.add(card)
            continue // Если это прямой аут, нет смысла проверять его как непрямой
        }

        // Ищем непрямые ауты, только если мы еще не нашли ни одного
        if (!foundAnyIndirectOuts && missing == 2) {
            val remainingDeck = deck.filter { it != card }
            for (second in remainingDeck) {
                val fullBoard = community + listOf(card, second)
                val playerHand2 = evaluate(player + fullBoard)
                val opponentHands2 = opponents.map { evaluate(it + fullBoard) }
                val bestOpponent2 = opponentHands2.maxByOrNull { it } ?: continue

                if (playerHand2 > bestOpponent2) {
                    foundAnyIndirectOuts = true // Нашли первую возможность, ставим флаг
                    break // и выходим из внутреннего цикла
                }
            }
        }
    }

    return if (outs.isNotEmpty()) {
        Pair(outs.toList().sorted(), false)
    } else {
        Pair(emptyList(), foundAnyIndirectOuts)
    }
}