package com.example.domain.model

import kotlinx.serialization.Serializable

@Serializable
enum class PlayerStatus {
    SPECTATING, // Зритель
    SITTING_OUT, // Сидит за столом, но пропускает раздачу
    IN_HAND     // Участвует в раздаче
}