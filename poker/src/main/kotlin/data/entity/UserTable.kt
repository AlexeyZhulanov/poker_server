package com.example.data.entity

import org.jetbrains.exposed.sql.Table

object Users : Table("users") { // "users" - имя таблицы в PostgreSQL
    val id = uuid("id").autoGenerate() // Используем UUID для ID
    val username = varchar("username", 255).uniqueIndex()
    val email = varchar("email", 255).uniqueIndex()
    val passwordHash = varchar("password_hash", 255)
    val cashBalance = decimal("cash_balance", 12, 2) // 12 знаков всего, 2 после запятой
    //todo ... другие поля ...

    override val primaryKey = PrimaryKey(id)
}