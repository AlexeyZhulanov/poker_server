package com.example.util

import java.security.SecureRandom

/**
 * Перемешивает элементы MutableList, используя криптографически стойкий генератор случайных чисел.
 * Использует алгоритм Фишера-Йейтса.
 */
fun <T> MutableList<T>.secureShuffle() {
    val rnd = SecureRandom()
    for (i in this.size - 1 downTo 1) {
        val j = rnd.nextInt(i + 1)
        // Идиоматичный способ поменять элементы местами в Kotlin
        val tmp = this[i]
        this[i] = this[j]
        this[j] = tmp
    }
}