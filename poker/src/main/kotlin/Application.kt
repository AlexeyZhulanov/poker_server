package com.example

import com.example.data.DatabaseFactory
import com.example.plugins.*
import com.example.services.GameRoomService
import io.ktor.server.application.*

fun main(args: Array<String>) {
    io.ktor.server.netty.EngineMain.main(args)
}

fun Application.module() {
    val gameRoomService = GameRoomService()
    DatabaseFactory.init(environment.config)
    configureSecurity()
    configureMonitoring()
    configureSerialization()
    configureSockets(gameRoomService)
    configureRouting(gameRoomService)
}
