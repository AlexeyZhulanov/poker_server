package com.example.model

import com.example.model.HandEvaluator.evaluate

data class EquityResult(val wins: List<Double>, val ties: Double)

fun calculateEquity(
    players: List<List<Card>>,
    community: List<Card>,
    iterations: Int = 10_000
): EquityResult {
    val usedCards = players.flatten() + community
    val deck = CardDeck.buildFullDeck().filter { it !in usedCards }.toMutableList()
    //val rng = Random.Default

    val wins = IntArray(players.size) { 0 }
    var ties = 0

    val missing = 5 - community.size

    repeat(iterations) {
        //deck.shuffle(rng) // 1.6 sec
        deck.secureShuffle() // 2.2 sec
        val remainingCommunity = deck.take(missing)
        val fullBoard = community + remainingCommunity

        // Оцениваем руки всех игроков
        val hands = players.map { evaluate(it + fullBoard) }
        val maxHand = hands.maxWithOrNull { a, b -> a.compareTo(b) }

        // Считаем сколько игроков имеют максимальную руку
        val winners = hands.count { it == maxHand }

        if (winners > 1) {
            ties++
        } else {
            val winnerIndex = hands.indexOf(maxHand)
            wins[winnerIndex]++
        }
    }

    return EquityResult(
        wins = wins.map { it * 100.0 / iterations },
        ties = ties * 100.0 / iterations
    )
}

fun calculateLiveOuts(
    player: List<Card>,
    opponents: List<List<Card>>,
    community: List<Card>
): Pair<List<Card>, Boolean> {
    val usedCards = player + community + opponents.flatten()
    val deck = CardDeck.buildFullDeck().filter { it !in usedCards }
    val missing = 5 - community.size

    if (missing == 0) return Pair(emptyList(), false)

    val outs = mutableSetOf<Card>()
    var hasIndirectOuts = false

    for (card in deck) {

        var isDirectOut = false
        var isIndirectOut = false

        // Прямая проверка (по одной карте)
        val boardWithOne = community + card
        val playerHand = evaluate(player + boardWithOne)
        val opponentHands = opponents.map { evaluate(it + boardWithOne) }
        val bestOpponent = opponentHands.maxWithOrNull { a, b -> a.compareTo(b) } ?: continue

        if (playerHand > bestOpponent) {
            isDirectOut = true
        }

        // Если не прямая, смотрим комбинации из двух карт
        if (!isDirectOut && missing == 2) {
            val remainingDeck = deck.filter { it != card }
            for (second in remainingDeck) {
                val fullBoard = community + listOf(card, second)
                val playerHand2 = evaluate(player + fullBoard)
                val opponentHands2 = opponents.map { evaluate(it + fullBoard) }
                val bestOpponent2 = opponentHands2.maxWithOrNull { a, b -> a.compareTo(b) } ?: continue

                if (playerHand2 > bestOpponent2) {
                    isIndirectOut = true
                    break
                }
            }
        }

        when {
            isDirectOut -> outs.add(card)
            isIndirectOut -> hasIndirectOuts = true
        }
    }

    return if (outs.isNotEmpty()) {
        Pair(outs.toList().sorted(), false)
    } else {
        Pair(emptyList(), hasIndirectOuts)
    }
}