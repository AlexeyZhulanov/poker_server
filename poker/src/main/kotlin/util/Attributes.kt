package com.example.util

import com.example.domain.model.User
import io.ktor.util.AttributeKey

// Создаем типизированный ключ для хранения объекта User в атрибутах запроса
val UserAttributeKey = AttributeKey<User>("UserAttributeKey")
