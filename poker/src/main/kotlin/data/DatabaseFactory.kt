package com.example.data

import com.example.data.entity.Users
import io.ktor.server.config.ApplicationConfig
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.transactions.transaction

object DatabaseFactory {
    fun init(config: ApplicationConfig) {
        val driverClassName = config.property("db.driver").getString()
        val jdbcURL = config.property("db.url").getString()
        val user = config.property("db.user").getString()
        val password = config.property("db.password").getString()
        val database = Database.Companion.connect(jdbcURL, driverClassName, user, password)

        // Создаем транзакцию для выполнения DDL-запросов (создание таблиц)
        transaction(database) {
            // SchemaUtils.create() проверяет, существует ли таблица Users,
            // и создает ее только в том случае, если она отсутствует.
            SchemaUtils.create(Users)
        }
    }

    /**
     * Вспомогательная функция для выполнения запросов к БД в корутинах.
     * Она гарантирует, что блокирующие JDBC-вызовы будут выполняться
     * в специальном пуле потоков Dispatchers.IO, не блокируя основной поток Ktor.
     */
    suspend fun <T> dbQuery(block: suspend () -> T): T =
        newSuspendedTransaction(Dispatchers.IO) { block() }
}