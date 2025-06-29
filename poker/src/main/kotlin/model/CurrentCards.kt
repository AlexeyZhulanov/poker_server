package com.example.model

enum class PokerCombination(val strength: Int) {
    HIGH_CARD(1),
    ONE_PAIR(2),
    TWO_PAIR(3),
    THREE_OF_A_KIND(4),
    STRAIGHT(5),
    FLUSH(6),
    FULL_HOUSE(7),
    FOUR_OF_A_KIND(8),
    STRAIGHT_FLUSH(9),
    ROYAL_FLUSH(10)
}

data class CurrentCards(val cards: List<Card>) {
    init {
        require(cards.size == 7) { "Должно быть ровно 7 карт" }
    }

    fun evaluateHand(): HandResult {
        return HandEvaluator.evaluate(cards)
    }

    fun evaluateHandDetailed(): EvaluatedHand {
        return HandEvaluator.evaluateBestHandDetailed(cards)
    }
}
