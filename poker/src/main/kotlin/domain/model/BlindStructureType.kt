package com.example.domain.model

import kotlinx.serialization.Serializable

@Serializable
enum class BlindStructureType {
    STANDARD,
    FAST,
    TURBO
}