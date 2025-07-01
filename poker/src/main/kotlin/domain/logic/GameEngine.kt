package com.example.domain.logic

import com.example.domain.model.*
import com.example.dto.ws.OutgoingMessage
import com.example.services.GameRoomService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class GameEngine(
    private val roomId: String,
    private val players: List<Player>,
    private val gameRoomService: GameRoomService
) : CoroutineScope {
    private val job = Job()
    override val coroutineContext = job + Dispatchers.Default

    private var deck = CardDeck()
    private var gameState: GameState = GameState(roomId = roomId)
    private var roundIsOver: Boolean = false

    fun startNewHand() {
        // 1. Создаем новую колоду
        deck.newDeck()

        // 2. Определяем позиции (для простоты пока фиксируем)
        val dealerPos = 0
        val smallBlindPos = (dealerPos + 1) % players.size
        val bigBlindPos = (dealerPos + 2) % players.size
        val actionPos = (dealerPos + 3) % players.size

        // 3. Создаем начальное состояние игроков
        val initialPlayerStates = players.mapIndexed { index, player ->
            PlayerState(
                player = player,
                cards = deck.deal(2), // Раздаем 2 карты каждому
                currentBet = when (index) {
                    smallBlindPos -> 10L // Условный малый блайнд
                    bigBlindPos -> 20L // Условный большой блайнд
                    else -> 0L
                }
            )
        }

        // 4. Формируем начальное состояние игры
        gameState = GameState(
            roomId = roomId,
            stage = GameStage.PRE_FLOP,
            playerStates = initialPlayerStates,
            dealerPosition = dealerPos,
            activePlayerPosition = actionPos,
            pot = 30L, // Сумма блайндов
            lastRaiseAmount = 20L // Последняя ставка равна большому блайнду
        )

        // 5. Рассылаем всем обновленное состояние
        launch {
            broadcastGameState()
        }
    }

    //--- Обработка действий игрока ---

    fun processFold(userId: String) {
        updatePlayerState(userId) { it.copy(hasFolded = true) }
        advanceToNextPlayer()
    }

    fun processCheck(userId: String) {
        // TODO: Добавить валидацию (можно ли чекать?)
        advanceToNextPlayer()
    }

    fun processBet(userId: String, amount: Long) {
        val playerState = gameState.playerStates.find { it.player.userId == userId } ?: return
        // TODO: Добавить полную валидацию (стек, мин/макс ставка)
        if (amount < gameState.lastRaiseAmount) {
            println("Bet amount is too small.")
            return // Некорректная ставка
        }

        val totalBet = playerState.currentBet + amount
        updatePlayerState(userId) {
            it.copy(
                player = it.player.copy(stack = it.player.stack - amount), // Уменьшаем стек
                currentBet = it.currentBet + amount // Увеличиваем ставку в текущем раунде
            )
        }
        gameState = gameState.copy(
            pot = gameState.pot + amount,
            lastRaiseAmount = if (totalBet > gameState.lastRaiseAmount) totalBet - gameState.lastRaiseAmount else gameState.lastRaiseAmount
        )
        advanceToNextPlayer()
    }

    private fun updatePlayerState(userId: String, transform: (PlayerState) -> PlayerState) {
        gameState = gameState.copy(
            playerStates = gameState.playerStates.map {
                if (it.player.userId == userId) transform(it) else it
            }
        )
    }

    private fun advanceToNextPlayer() {
        if (roundIsOver) return

        val activePlayers = gameState.playerStates.filter { !it.hasFolded && !it.isAllIn }
        if (activePlayers.size <= 1) {
            // Если остался один активный игрок, раунд (и игра) заканчивается
            handleShowdown()
            return
        }

        val startPos = gameState.activePlayerPosition
        var nextPos = (startPos + 1) % players.size

        // Ищем следующего активного игрока
        while (gameState.playerStates[nextPos].hasFolded || gameState.playerStates[nextPos].isAllIn) {
            nextPos = (nextPos + 1) % players.size
        }

        // Проверяем, закончился ли круг торгов
        val allBetsEqual = activePlayers.map { it.currentBet }.distinct().size == 1
        val actionReturnedToLastAggressor = nextPos == activePlayers.find { it.currentBet >= gameState.lastRaiseAmount }?.let { gameState.playerStates.indexOf(it) }

        if (allBetsEqual && actionReturnedToLastAggressor) {
            roundIsOver = true
            advanceToNextStage()
        } else {
            gameState = gameState.copy(activePlayerPosition = nextPos)
            launch { broadcastGameState() }
        }
    }

    private fun advanceToNextStage() {
        // Сбрасываем флаг окончания раунда
        roundIsOver = false

        // Сбрасываем ставки игроков для нового раунда
        val newPlayerStates = gameState.playerStates.map { it.copy(currentBet = 0) }
        gameState = gameState.copy(playerStates = newPlayerStates, lastRaiseAmount = 0)

        when (gameState.stage) {
            GameStage.PRE_FLOP -> {
                gameState = gameState.copy(
                    stage = GameStage.FLOP,
                    communityCards = deck.deal(3)
                )
            }
            GameStage.FLOP -> {
                gameState = gameState.copy(
                    stage = GameStage.TURN,
                    communityCards = gameState.communityCards + deck.deal(1)
                )
            }
            GameStage.TURN -> {
                gameState = gameState.copy(
                    stage = GameStage.RIVER,
                    communityCards = gameState.communityCards + deck.deal(1)
                )
            }
            GameStage.RIVER -> {
                // После ривера идет вскрытие
                gameState = gameState.copy(stage = GameStage.SHOWDOWN)
                handleShowdown()
                return
            }
            else -> { /* SHOWDOWN */ }
        }

        // Находим первого игрока для нового раунда (слева от дилера)
        var firstToAct = (gameState.dealerPosition + 1) % players.size
        while (gameState.playerStates[firstToAct].hasFolded || gameState.playerStates[firstToAct].isAllIn) {
            firstToAct = (firstToAct + 1) % players.size
        }
        gameState = gameState.copy(activePlayerPosition = firstToAct)

        // Рассылаем обновленное состояние
        launch { broadcastGameState() }
    }

    private fun handleShowdown() {
        val contenders = gameState.playerStates.filter { !it.hasFolded }

        if (contenders.size == 1) {
            // Один оставшийся игрок забирает банк
            val winnerState = contenders.first()
            val updatedWinnerState = winnerState.copy(player = winnerState.player.copy(stack = winnerState.player.stack + gameState.pot))
            updatePlayerState(winnerState.player.userId) { updatedWinnerState }
        } else {
            // Несколько игроков на вскрытии, оцениваем руки
            val hands = contenders.map { playerState ->
                playerState.player.userId to HandEvaluator.evaluate(playerState.cards + gameState.communityCards)
            }
            val bestHandResult = hands.maxByOrNull { it.second }?.second

            val winners = hands.filter { it.second == bestHandResult }
            val prizePerWinner = gameState.pot / winners.size

            winners.forEach { (userId, _) ->
                val winnerState = gameState.playerStates.find { it.player.userId == userId }!!
                val updatedWinnerState = winnerState.copy(player = winnerState.player.copy(stack = winnerState.player.stack + prizePerWinner))
                updatePlayerState(userId) { updatedWinnerState }
            }
        }

        gameState = gameState.copy(stage = GameStage.SHOWDOWN)

        // Отправляем финальное состояние со вскрытыми картами
        launch { broadcastGameState() }

        // TODO: Запланировать начало следующей раздачи через несколько секунд
    }

    /**
     * Этот метод ОБЯЗАТЕЛЬНО нужно вызвать, когда игра заканчивается,
     * чтобы остановить все запущенные корутины и избежать утечек памяти.
     */
    fun destroy() {
        job.cancel()
    }

    private suspend fun broadcastGameState() {
        // TODO: Отправлять каждому игроку его персональное состояние (с его картами),
        // а остальным - публичное (без карт других игроков).
        val message = OutgoingMessage.GameStateUpdate(gameState)
        gameRoomService.broadcast(roomId, message)
    }

    // Здесь будут методы для обработки действий: processFold, processBet, processCheck...
}