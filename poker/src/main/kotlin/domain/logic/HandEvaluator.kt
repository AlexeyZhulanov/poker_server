package com.example.domain.logic

import com.example.domain.model.Card
import com.example.domain.model.EvaluatedHand
import com.example.domain.model.HandResult
import com.example.domain.model.PokerCombination
import com.example.domain.model.Rank

object HandEvaluator {
    fun evaluate(cards: List<Card>): HandResult {
        val combinations = cards.combinations(5)
        return combinations.maxOf { evaluateFiveCardHand(it) }
    }

    fun evaluateBestHandDetailed(cards: List<Card>): EvaluatedHand {
        val best = cards.combinations(5).maxByOrNull { evaluateFiveCardHand(it) } ?: listOf()
        return EvaluatedHand(best, evaluateFiveCardHand(best))
    }

    private fun evaluateFiveCardHand(cards: List<Card>): HandResult {
        return evaluateStraightFlush(cards)
            ?: evaluateFourOfAKind(cards)
            ?: evaluateFullHouse(cards)
            ?: evaluateFlush(cards)
            ?: evaluateStraight(cards)
            ?: evaluateThreeOfAKind(cards)
            ?: evaluateTwoPair(cards)
            ?: evaluateOnePair(cards)
            ?: evaluateHighCard(cards)
    }

    private fun evaluateStraightFlush(cards: List<Card>): HandResult? {
        val flushCards = cards.groupBy { it.suit }
            .values.firstOrNull { it.size >= 5 } ?: return null
        return evaluateStraight(flushCards)?.let {
            if(it.ranks[0] == Rank.ACE) HandResult(PokerCombination.ROYAL_FLUSH, it.ranks)
            else HandResult(PokerCombination.STRAIGHT_FLUSH, it.ranks)
        }
    }

    private fun evaluateFourOfAKind(cards: List<Card>): HandResult? {
        val groups = cards.groupBy { it.rank }
        val quad = groups.values.firstOrNull { it.size == 4 } ?: return null
        val kicker = cards.first { it.rank != quad[0].rank }
        return HandResult(
            PokerCombination.FOUR_OF_A_KIND,
            listOf(quad[0].rank, kicker.rank)
        )
    }

    private fun evaluateFullHouse(cards: List<Card>): HandResult? {
        val groups = cards.groupBy { it.rank }
        val trips = groups.values.filter { it.size >= 3 }.sortedByDescending { it[0].rank.value }
        if (trips.isEmpty()) return null
        val pair = groups.values
            .filter { it.size >= 2 && it[0].rank != trips[0][0].rank }
            .maxByOrNull { it[0].rank.value } ?: return null
        return HandResult(
            PokerCombination.FULL_HOUSE,
            listOf(trips[0][0].rank, pair[0].rank)
        )
    }

    private fun evaluateFlush(cards: List<Card>): HandResult? {
        val flushCards = cards.groupBy { it.suit }
            .values.firstOrNull { it.size >= 5 } ?: return null
        val topFive = flushCards.sortedByDescending { it.rank.value }.take(5)
        return HandResult(
            PokerCombination.FLUSH,
            topFive.map { it.rank }
        )
    }

    private fun evaluateStraight(cards: List<Card>): HandResult? {
        val ranks = cards.map { it.rank.value }.toMutableSet()

        // Добавим виртуальный ACE=1, если есть ACE (для A-2-3-4-5)
        if (cards.any { it.rank == Rank.ACE }) ranks.add(1)

        val sorted = ranks.distinct().sortedDescending()

        var count = 1
        for (i in 0..<sorted.size - 1) {
            if (sorted[i] == sorted[i + 1] + 1) {
                count++
                if (count == 5) {
                    val highCard = sorted[i - 3]  // начало стрита
                    val highRank = Rank.entries.first { it.value == highCard || (highCard == 1 && it == Rank.ACE) }
                    return HandResult(PokerCombination.STRAIGHT, listOf(highRank))
                }
            } else count = 1
        }
        return null
    }

    private fun evaluateThreeOfAKind(cards: List<Card>): HandResult? {
        val groups = cards.groupBy { it.rank }
        val trips = groups.values.firstOrNull { it.size == 3 } ?: return null
        val kickers = cards.map { it.rank }
            .filter { it != trips[0].rank }
            .sortedByDescending { it.value }
            .take(2)
        return HandResult(
            PokerCombination.THREE_OF_A_KIND,
            listOf(trips[0].rank) + kickers
        )
    }

    private fun evaluateTwoPair(cards: List<Card>): HandResult? {
        val groups = cards.groupBy { it.rank }
        val pairs = groups.values.filter { it.size == 2 }.sortedByDescending { it[0].rank.value }
        if (pairs.size < 2) return null
        val kicker = cards.filter { it.rank != pairs[0][0].rank && it.rank != pairs[1][0].rank }
            .maxByOrNull { it.rank.value } ?: return null
        return HandResult(
            PokerCombination.TWO_PAIR,
            listOf(pairs[0][0].rank, pairs[1][0].rank, kicker.rank)
        )
    }

    private fun evaluateOnePair(cards: List<Card>): HandResult? {
        val groups = cards.groupBy { it.rank }.values
        val pair = groups.firstOrNull { it.size == 2 } ?: return null
        val kickers = cards.map { it.rank }
            .filter { it != pair[0].rank }
            .sortedByDescending { it.value }
            .take(3)

        return HandResult(
            combination = PokerCombination.ONE_PAIR,
            ranks = listOf(pair[0].rank) + kickers
        )
    }

    private fun evaluateHighCard(cards: List<Card>): HandResult {
        val sorted = cards.sortedByDescending { it.rank.value }
        return HandResult(
            combination = PokerCombination.HIGH_CARD,
            ranks = sorted.map { it.rank }.distinct().take(5)
        )
    }

    // Расширение на List<Card>
    private fun <T> List<T>.combinations(k: Int): List<List<T>> {
        fun combine(start: Int, chosen: List<T>): List<List<T>> {
            if (chosen.size == k) return listOf(chosen)
            if (start == size) return emptyList()
            return combine(start + 1, chosen + this[start]) +
                    combine(start + 1, chosen)
        }
        return combine(0, emptyList())
    }
}
