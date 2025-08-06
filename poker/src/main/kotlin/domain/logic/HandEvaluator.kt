package com.example.domain.logic

import com.example.domain.model.Card
import evaluator.Eval
import evaluator.HandMask


object HandEvaluator {
    /**
     * Конвертирует нашу карту в числовой индекс (0-51),
     * который используется в таблице масок MyKey.
     */
    private fun cardToInt(card: Card): Int {
        // Suit.ordinal (0-3), Rank.value (2-14)
        // Формула для стандартной колоды, где двойка = 0
        return card.suit.ordinal * 13 + (card.rank.value - 2)
    }

    /**
     * Главный метод оценки. Принимает 5, 6 или 7 наших карт.
     * Возвращает числовой ранг (чем больше, тем лучше).
     */
    fun evaluate(cards: List<Card>): Int {
        if (cards.isEmpty()) return 0

        // 1. Конвертируем наши карты в битовую маску
        var mask = 0L
        for (card in cards) {
            val cardIndex = cardToInt(card)
            mask = mask or HandMask.HandMasksTable[cardIndex]
        }

        // 2. Вызываем быстрый метод из библиотеки MyKey
        return Eval.rankHand(mask)
    }
}
