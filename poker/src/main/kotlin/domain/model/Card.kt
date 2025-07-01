package com.example.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class Card(val rank: Rank, val suit: Suit): Comparable<Card> {
    override fun compareTo(other: Card): Int {
        return rank.compareTo(other.rank)
    }
}