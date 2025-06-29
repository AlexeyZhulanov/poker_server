package com.example.model

data class HandResult(
    val combination: PokerCombination,
    val ranks: List<Rank> // Сортированы от сильнейшей к слабейшей, включая kickers
) : Comparable<HandResult> {
    override fun compareTo(other: HandResult): Int {
        if (combination != other.combination) {
            return combination.strength.compareTo(other.combination.strength)
        }

        // 2. Сравниваем ранги по порядку
        for (i in ranks.indices) {
            if (i >= other.ranks.size) return 1
            val cmp = ranks[i].value.compareTo(other.ranks[i].value)
            if (cmp != 0) return cmp
        }

        // 3. Совпали
        return 0
    }
}
