package com.example.data.repository

import com.example.data.entity.Users
import com.example.dto.RegisterRequest
import com.example.data.DatabaseFactory.dbQuery
import com.example.domain.model.User
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import java.math.BigDecimal
import java.util.UUID

class UserRepository {

    // Вспомогательная функция для конвертации
    private fun toUser(row: ResultRow): User = User(
        id = row[Users.id],
        username = row[Users.username],
        email = row[Users.email],
        passwordHash = row[Users.passwordHash],
        cashBalance = row[Users.cashBalance]
    )

    suspend fun findById(id: UUID): User? {
        return dbQuery {
            Users.selectAll().where { Users.id eq id }
                .map(::toUser)
                .singleOrNull()
        }
    }

    suspend fun findByUsername(username: String): User? {
        return dbQuery {
            Users.selectAll().where { Users.username eq username }
                .map(::toUser)
                .singleOrNull()
        }
    }

    suspend fun create(request: RegisterRequest, hashedPassword: String): User? {
        return dbQuery {
            Users.insert {
                it[username] = request.username
                it[email] = request.email
                it[passwordHash] = hashedPassword
                it[cashBalance] = BigDecimal(1000)
            }.resultedValues?.firstOrNull()?.let(::toUser)
        }
    }
}