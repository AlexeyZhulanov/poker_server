package com.example.domain.logic

import com.example.domain.model.Card
import com.example.domain.model.Rank
import com.example.domain.model.Suit
import com.example.util.secureShuffle
import java.util.*

class CardDeck {
    private val cards: ArrayDeque<Card> = ArrayDeque()

    init {
        newDeck()
    }

    fun deal(n: Int): List<Card> {
        val list = mutableListOf<Card>()
        repeat(n) {
            list.add(cards.removeFirst())
        }
        return list
    }

    fun newDeck() {
        cards.clear()
        val list = buildFullDeck()
        list.secureShuffle()
        cards.addAll(list)
    }


    companion object {
        fun buildFullDeck(): MutableList<Card> {
            return Suit.entries.flatMap { suit ->
                Rank.entries.map { rank -> Card(rank, suit) }
            }.toMutableList()
        }
    }
}
