package com.example.data.repository

import com.example.data.entity.Users
import com.example.dto.RegisterRequest
import com.example.data.DatabaseFactory.dbQuery
import com.example.domain.model.GameRoom
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import java.math.BigDecimal
import java.util.UUID

class UserRepository {

    suspend fun create(request: RegisterRequest, hashedPassword: String): ResultRow? {
        // dbQuery выполняет запрос в БД на отдельном потоке
        return dbQuery {
            Users.insert {
                it[username] = request.username
                it[email] = request.email
                it[passwordHash] = hashedPassword
                it[cashBalance] = BigDecimal(1000) // Начальный баланс для примера
            }.resultedValues?.firstOrNull()
        }
    }

    suspend fun findByUsername(username: String): ResultRow? {
        return dbQuery {
            Users.selectAll().where { Users.username eq username }.singleOrNull()
        }
    }

    suspend fun findById(id: UUID): ResultRow? {
        return dbQuery {
            Users.selectAll().where { Users.id eq id }.singleOrNull()
        }
    }

    suspend fun findByUsernameOrEmail(username: String, email: String): ResultRow? {
        return dbQuery {
            Users.selectAll().where {
                (Users.username eq username) or (Users.email eq email)
            }.singleOrNull()
        }
    }
}