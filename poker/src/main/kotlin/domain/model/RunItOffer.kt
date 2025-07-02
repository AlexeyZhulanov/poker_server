package com.example.domain.model

data class RunItOffer(
    val contenders: Set<String>, // Набор ID всех участников
    val maxRuns: Int,
    val responses: MutableMap<String, Int> = mutableMapOf() // Храним выбор каждого: UserID -> chosen_runs
)