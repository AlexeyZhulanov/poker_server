package com.example.domain.model

data class Pot(
    val amount: Long,
    val eligiblePlayerIds: Set<String>
)