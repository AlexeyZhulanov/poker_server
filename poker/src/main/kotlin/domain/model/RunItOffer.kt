package com.example.domain.model

data class RunItOffer(
    val underdogId: String,
    val favoriteIds: Set<String>,
    var chosenRuns: Int = 1,
    val favoriteResponses: MutableMap<String, Boolean> = mutableMapOf()
)