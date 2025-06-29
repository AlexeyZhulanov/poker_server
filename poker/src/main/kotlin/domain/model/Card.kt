package com.example.domain.model

enum class Suit {
    HEARTS, DIAMONDS, CLUBS, SPADES
}

enum class Rank(val value: Int) {
    TWO(2), THREE(3), FOUR(4), FIVE(5),
    SIX(6), SEVEN(7), EIGHT(8), NINE(9),
    TEN(10), JACK(11), QUEEN(12), KING(13), ACE(14)
}

data class Card(val rank: Rank, val suit: Suit): Comparable<Card> {
    override fun compareTo(other: Card): Int {
        return rank.compareTo(other.rank)
    }
}