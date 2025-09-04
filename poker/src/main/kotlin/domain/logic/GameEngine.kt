package com.example.domain.logic

import com.example.domain.model.*
import com.example.dto.ws.*
import com.example.services.GameRoomService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class GameEngine(
    private val roomId: String,
    private val gameRoomService: GameRoomService
) : CoroutineScope {
    private val job = Job()
    override val coroutineContext = job + Dispatchers.Default

    private var deck = CardDeck()
    private var gameState: GameState = GameState(roomId = roomId)
    private var roundIsOver: Boolean = false
    private var currentDealerPosition = -1
    private var currentLevelIndex = 0
    private var blindIncreaseJob: Job? = null // Job для таймера повышения блайндов
    private var playersInGame: List<Player> = emptyList()
    private var runItState = RunItState.NONE
    private var runItOffer: RunItOffer? = null
    private var turnTimerJob: Job? = null
    private var isGameStarted: Boolean = false
    private var lastBigBlindAmount: Long = 0L
    private var runItTimerJob: Job? = null
    private var isProcessingAction = false

    fun handlePlayerDisconnect(userId: String) {
        // Если игрок был в раздаче, просто считаем, что он сделал фолд
        val playerState = getPlayerState(userId)
        if (playerState != null && !playerState.hasFolded) {
            processFold(userId)
        }
    }

    private fun startBlindTimer(blindStructureType: BlindStructureType, room: GameRoom) {
        val durationMinutes = when(blindStructureType) {
            BlindStructureType.STANDARD -> 10
            BlindStructureType.FAST -> 5
            BlindStructureType.TURBO -> 3
        }
        blindIncreaseJob = launch {
            while (isActive) {
                // Получаем информацию о новом уровне блайндов
                val newLevel = room.blindStructure?.getOrNull(currentLevelIndex)
                if (newLevel != null) {
                    // Создаем и рассылаем сообщение
                    val message = OutgoingMessage.BlindsUp(newLevel.smallBlind, newLevel.bigBlind, newLevel.ante, newLevel.level, System.currentTimeMillis() + durationMinutes * 60 * 1000L)
                    gameRoomService.broadcast(room.roomId, message)
                }
                delay(durationMinutes * 60 * 1000L)
                currentLevelIndex++
            }
        }
    }

    fun getIsStarted(): Boolean = isGameStarted

    fun startGame() {
        isGameStarted = true
        val room = gameRoomService.getRoom(roomId)
        // Если это турнир, запускаем таймер
        if(room != null) {
            if (room.gameMode == GameMode.TOURNAMENT && room.blindStructureType != null) {
                startBlindTimer(room.blindStructureType, room)
            }
        }
        startNewHand()
    }

    fun startNewHand() {
        var room = gameRoomService.getRoom(roomId) ?: return // Если комната уже не существует, выходим

        // Находим игроков, которые пропустили 3 и более ходов подряд
        val inactivePlayers = room.players.filter { it.missedTurns >= 3 }
        if (inactivePlayers.isNotEmpty()) {
            // Переводим их в зрители
            inactivePlayers.forEach { playerToKick ->
                launch {
                    gameRoomService.updatePlayerStatus(
                        roomId = roomId,
                        userId = playerToKick.userId,
                        newStatus = PlayerStatus.SPECTATING,
                        newStack = playerToKick.stack // Стек сохраняется
                    )
                }
            }
            // Запрашиваем обновленное состояние комнаты после изменений
            room = gameRoomService.getRoom(roomId) ?: return
        }

        if (room.players.size < 2) {
            println("EXIT < 2 players")
            isGameStarted = false
            gameState = GameState(roomId = roomId)
            val message = OutgoingMessage.GameStateUpdate(null)
            launch { gameRoomService.sendToPlayer(roomId, room.players.first().userId, message) }
            blindIncreaseJob?.cancel()
            gameRoomService.setAllPlayersUnready(roomId = roomId)
            return
        }

        // 1. Определяем игроков для новой раздачи
        playersInGame = room.players.filter { it.status != PlayerStatus.SPECTATING }

        // 2. Проверяем, не закончился ли турнир
        if (playersInGame.size < 2) {
            isGameStarted = false
            if(room.gameMode == GameMode.TOURNAMENT) {
                val winnerUserId = playersInGame.firstOrNull()?.userId ?: ""
                // Отправляем всем сообщение о победителе
                launch {
                    gameRoomService.broadcast(room.roomId, OutgoingMessage.TournamentWinner(winnerUserId))
                    destroy() // Останавливаем движок
                }
                return
            }
            println("EXIT < 2 gameroom players")
            gameState = GameState(roomId = roomId)
            val message = OutgoingMessage.GameStateUpdate(null)
            launch {
                room.players.forEach { player ->
                    gameRoomService.sendToPlayer(roomId, player.userId, message)
                }
            }
            gameRoomService.setAllPlayersUnready(roomId = roomId)
            return
        }

        // 3. Создаем новую колоду
        deck.newDeck()
        roundIsOver = false

        val currentLevel = if (room.gameMode == GameMode.TOURNAMENT && room.blindStructure != null) {
            room.blindStructure.getOrNull(currentLevelIndex) ?: room.blindStructure.last()
        } else {
            // Для кэш-игры используем "нулевой" уровень с фиксированными блайндами
            BlindLevel(level = 0, smallBlind = 10, bigBlind = 20)
        }

        val smallBlindAmount = currentLevel.smallBlind
        val bigBlindAmount = currentLevel.bigBlind
        val anteAmount = currentLevel.ante
        lastBigBlindAmount = bigBlindAmount

        // 4. Двигаем дилера и блайнды
        currentDealerPosition = (currentDealerPosition + 1) % playersInGame.size
        val sbPos = (currentDealerPosition + 1) % playersInGame.size
        val bbPos = (currentDealerPosition + 2) % playersInGame.size
        val actionPos = (currentDealerPosition + 3) % playersInGame.size

        var potFromBlindsAndAntes = 0L

        // 5. Создаем начальное состояние игроков, собирая блайнды и анте
        val initialPlayerStates = playersInGame.mapIndexed { index, player ->
            val sbPost = if (index == sbPos) minOf(player.stack, smallBlindAmount) else 0L
            val bbPost = if (index == bbPos) minOf(player.stack, bigBlindAmount) else 0L
            // Каждый игрок ставит анте (если оно есть)
            val antePost = if (anteAmount > 0) minOf(player.stack - sbPost - bbPost, anteAmount) else 0L

            val totalBet = sbPost + bbPost
            val totalContribution = totalBet + antePost
            potFromBlindsAndAntes += totalContribution

            PlayerState(
                player = player.copy(
                    stack = player.stack - totalContribution,
                    status = PlayerStatus.IN_HAND
                ),
                cards = deck.deal(2).sortedDescending(),
                currentBet = totalBet, // Анте идет сразу в банк, не считается частью ставки
                handContribution = totalContribution,
                hasActedThisRound = false,
                isAllIn = player.stack - totalContribution <= 0
            )
        }

        // 6. Формируем начальное состояние игры
        gameState = GameState(
            roomId = room.roomId,
            stage = GameStage.PRE_FLOP,
            playerStates = initialPlayerStates,
            dealerPosition = currentDealerPosition,
            activePlayerPosition = actionPos,
            pot = potFromBlindsAndAntes,
            bigBlindAmount = bigBlindAmount,
            amountToCall = bigBlindAmount,
            lastRaiseAmount = bigBlindAmount,
            lastAggressorPosition = bbPos,
            turnExpiresAt = System.currentTimeMillis() + 15_000L,
        )

        // Запускаем таймер на 15 секунд для активного игрока
        turnTimerJob = launch {
            delay(15_000L)
            handlePlayerTimeout()
        }

        // 7. Рассылаем всем обновленное состояние
        launch { broadcastGameState() }
    }

    //--- Обработка действий игрока ---
    private fun isValidProcess(userId: String): Boolean {
        val activePlayerId = gameState.playerStates.getOrNull(gameState.activePlayerPosition)?.player?.userId

        // Проверка 1: Ход этого игрока?
        if (activePlayerId != userId) {
            return false
        }
        // Проверка 2: Игра в стадии, когда можно делать ходы?
        if (gameState.stage == GameStage.SHOWDOWN || runItState != RunItState.NONE) {
            return false
        }
        return true
    }

    fun processFold(userId: String, isAutoClick: Boolean = false) {
        synchronized(this) {
            if(isProcessingAction) return
            if(!isValidProcess(userId)) return
            isProcessingAction = true
        }
        try {
            turnTimerJob?.cancel()
            if(!isAutoClick) gameRoomService.resetMissedTurns(roomId, userId)
            updatePlayerState(userId) { it.copy(hasFolded = true, hasActedThisRound = true, lastAction = PlayerAction.Fold()) }
            findNextPlayerOrEndRound()
        } finally {
            isProcessingAction = false
        }
    }

    fun processCheck(userId: String, isAutoClick: Boolean = false) {
        synchronized(this) {
            if(isProcessingAction) return
            if(!isValidProcess(userId)) return
            isProcessingAction = true
        }
        try {
            turnTimerJob?.cancel()
            if(!isAutoClick) gameRoomService.resetMissedTurns(roomId, userId)
            val playerState = getPlayerState(userId) ?: return
            if (playerState.currentBet < gameState.amountToCall) {
                println("Cannot check")
                launch { gameRoomService.sendToPlayer(roomId, userId, OutgoingMessage.ErrorMessage("Cannot check")) }
                return
            }
            updatePlayerState(userId) { it.copy(hasActedThisRound = true, lastAction = PlayerAction.Check()) }
            findNextPlayerOrEndRound()
        } finally {
            isProcessingAction = false
        }
    }

    fun processCall(userId: String) {
        synchronized(this) {
            if(isProcessingAction) return
            if(!isValidProcess(userId)) return
            isProcessingAction = true
        }
        try {
            turnTimerJob?.cancel()
            gameRoomService.resetMissedTurns(roomId, userId)
            val playerState = getPlayerState(userId) ?: return
            val amountToCall = minOf(playerState.player.stack, gameState.amountToCall - playerState.currentBet)

            val newStack = playerState.player.stack - amountToCall
            updatePlayerState(userId) {
                it.copy(
                    player = it.player.copy(stack = newStack),
                    currentBet = it.currentBet + amountToCall,
                    handContribution = it.handContribution + amountToCall,
                    hasActedThisRound = true,
                    isAllIn = newStack <= 0,
                    lastAction = if(newStack <= 0) PlayerAction.AllIn() else PlayerAction.Call(amountToCall)
                )
            }
            gameState = gameState.copy(pot = gameState.pot + amountToCall)
            findNextPlayerOrEndRound()
        } finally {
            isProcessingAction = false
        }
    }

    fun processBet(userId: String, amount: Long) {
        synchronized(this) {
            if(isProcessingAction) return
            if(!isValidProcess(userId)) return
            isProcessingAction = true
        }
        try {
            turnTimerJob?.cancel()
            gameRoomService.resetMissedTurns(roomId, userId)
            val playerState = getPlayerState(userId) ?: return
            // Проверка, достаточно ли фишек у игрока
            if (amount > (playerState.player.stack + playerState.currentBet)) {
                println("Action validation failed: Bet amount ($amount) is larger than stack (${playerState.player.stack}).")
                return // Недостаточно фишек
            }
            val maxBetOnStreet = gameState.playerStates.maxOfOrNull { it.currentBet } ?: 0L
            val amountToCall = maxBetOnStreet - playerState.currentBet

            // Если ставка меньше, чем колл (и это не all-in), то это некорректное действие
            if (amount < amountToCall && amount < playerState.player.stack) {
                println("Action validation failed: Bet amount is too small to call.")
                return
            }

            // Проверка на минимальный рейз (рейз должен быть не меньше предыдущего рейза)
            val raiseAmount = amount - amountToCall
            if (raiseAmount > 0 && raiseAmount < gameState.lastRaiseAmount && (playerState.player.stack - amount) > 0) {
                println("Action validation failed: Raise amount is too small.")
                return
            }

            // Это повышение, поэтому сбрасываем флаги "уже ходил" у всех остальных
            clearHasActedFlags(exceptForUserId = userId)

            val contribution = amount - playerState.currentBet
            val newStack = playerState.player.stack - contribution
            val isRaise = amount > gameState.amountToCall
            val isAllIn = amount >= playerState.player.stack + playerState.currentBet
            val lastAction = when {
                isAllIn -> PlayerAction.AllIn()
                isRaise -> PlayerAction.Raise(amount)
                else -> PlayerAction.Bet(amount)
            }
            updatePlayerState(userId) {
                it.copy(
                    player = it.player.copy(stack = newStack),
                    currentBet = amount,
                    handContribution = it.handContribution + contribution,
                    hasActedThisRound = true,
                    isAllIn = newStack <= 0,
                    lastAction = lastAction
                )
            }

            val newLastRaiseAmount = if(amount > gameState.amountToCall) amount - gameState.amountToCall else gameState.lastRaiseAmount

            gameState = gameState.copy(
                pot = gameState.pot + contribution,
                amountToCall = amount,
                lastRaiseAmount = newLastRaiseAmount
            )
            findNextPlayerOrEndRound()
        } finally {
            isProcessingAction = false
        }
    }

    private fun updatePlayerState(userId: String, transform: (PlayerState) -> PlayerState) {
        gameState = gameState.copy(
            playerStates = gameState.playerStates.map {
                if (it.player.userId == userId) {
                    val newState = transform(it)
                    // Проверяем, не пошел ли игрок в all-in
                    if (newState.player.stack <= 0) {
                        newState.copy(isAllIn = true, player = newState.player.copy(stack = 0))
                    } else {
                        newState
                    }
                } else {
                    it
                }
            }
        )
    }

    private fun findNextPlayerOrEndRound() {
        val activePlayers = gameState.playerStates.filter { !it.hasFolded }
        if (activePlayers.size <= 1) {
            // Если остался один (или ноль) игроков, раздача окончена.
            val winnerId = activePlayers.firstOrNull()?.player?.userId
            if (winnerId != null) {
                givePrize(mapOf(winnerId to gameState.pot))
            }

            // Рассылаем финальное состояние, чтобы UI показал, как победитель получил фишки,
            // и запускаем новую раздачу через 3 секунды.
            launch {
                gameRoomService.updatePlayerStatesInRoom(roomId, gameState.playerStates)
                gameState = gameState.copy(activePlayerPosition = -1, turnExpiresAt = null)
                broadcastGameState()
                delay(3000L) // Пауза для просмотра результата
                startNewHand()
            }
            println("Players folded, start new game")
            return // Важно выйти из функции здесь
        }

        // Игроки, которые еще могут делать ставки (не all-in и не fold)
        val playersWhoCanAct = activePlayers.filter { !it.isAllIn }

        // 1. Проверяем, все ли уже походили
        val allHaveActed = playersWhoCanAct.all { it.hasActedThisRound }
        if (!allHaveActed) {
            // Если не все походили, просто ищем следующего
            advanceToNextActivePlayer()
            return
        }

        // 2. Все походили. Теперь проверяем, все ли ставки равны.
        val maxBet = activePlayers.maxOf { it.currentBet }
        val allBetsEqual = playersWhoCanAct.all { it.currentBet == maxBet }

        if (allBetsEqual) {
            // Если все ставки равны, раунд окончен.
            advanceToNextStage()
        } else {
            // Ставки не равны, значит, кто-то должен еще действовать.
            // Сбрасываем флаги для тех, кто не поставил максимальную ставку.
            clearHasActedFlags(keepForThoseWhoBet = maxBet)
            advanceToNextActivePlayer()
        }
    }

    private fun advanceToNextActivePlayer() {
        turnTimerJob?.cancel() // Отменяем старый таймер

        var nextPos = (gameState.activePlayerPosition + 1) % playersInGame.size
        while (gameState.playerStates[nextPos].hasFolded || gameState.playerStates[nextPos].isAllIn) {
            nextPos = (nextPos + 1) % playersInGame.size
        }
        gameState = gameState.copy(
            activePlayerPosition = nextPos,
            turnExpiresAt = System.currentTimeMillis() + 15_000L
        )

        // Запускаем новый таймер на 15 секунд для нового игрока
        turnTimerJob = launch {
            delay(15_000L)
            handlePlayerTimeout()
        }

        launch { broadcastGameState() }
    }

    private fun handlePlayerTimeout() {
        val timedOutPlayerState = gameState.playerStates.getOrNull(gameState.activePlayerPosition) ?: return
        val userId = timedOutPlayerState.player.userId

        // Увеличиваем счетчик пропущенных ходов
        gameRoomService.incrementMissedTurns(roomId, userId)

        // Определяем, можно ли сделать "чек"
        val canCheck = timedOutPlayerState.currentBet >= gameState.amountToCall

        if (canCheck) {
            processCheck(timedOutPlayerState.player.userId, isAutoClick = true)
        } else {
            processFold(timedOutPlayerState.player.userId, isAutoClick = true)
        }
    }

    private fun clearHasActedFlags(exceptForUserId: String? = null, keepForThoseWhoBet: Long? = null) {
        gameState = gameState.copy(
            playerStates = gameState.playerStates.map { ps ->
                // Сбрасываем флаг, если...
                val shouldReset = when {
                    ps.player.userId == exceptForUserId -> false // не сбрасывать для того, кто только что сходил
                    keepForThoseWhoBet != null && ps.currentBet == keepForThoseWhoBet -> false // не сбрасывать для тех, кто уже уравнял
                    else -> true
                }
                if (shouldReset) ps.copy(hasActedThisRound = false) else ps
            }
        )
    }

    private fun advanceToNextStage() {
        // 1. Сначала сбрасываем ставки игроков с прошлого раунда, если это не all-in шоудаун
        // Проверяем, сколько игроков еще могут делать ставки (не fold и не all-in)
        val playersWhoCanStillBet = gameState.playerStates.filter { !it.hasFolded && !it.isAllIn }

        // Если таких игроков меньше двух, значит, торги завершены и это all-in шоудаун
        if (playersWhoCanStillBet.size < 2) {
            // Убедимся, что в раздаче все еще есть хотя бы 2 игрока (остальные в all-in)
            if (gameState.playerStates.count { !it.hasFolded } >= 2) {
                initiateRunItMultipleTimesNegotiation() // Запускаем логику "Run it Twice"
                return // Выходим, чтобы не продолжать обычный раунд
            }
        }
        val lastActorId = gameState.playerStates.getOrNull(gameState.activePlayerPosition)?.player?.userId
        val newPlayerStates = gameState.playerStates.map { it.copy(
            currentBet = 0,
            hasActedThisRound = false,
            lastAction = if (it.player.userId == lastActorId) it.lastAction else null
        ) }
        val nextAggressor = if (gameState.stage == GameStage.PRE_FLOP) null else gameState.lastAggressorPosition
        gameState = gameState.copy(
            playerStates = newPlayerStates,
            amountToCall = 0,
            lastRaiseAmount = lastBigBlindAmount,
            lastAggressorPosition = nextAggressor
        )
        roundIsOver = false

        // 2. Раздаем карты на стол в зависимости от текущей стадии
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
                gameState = gameState.copy(stage = GameStage.SHOWDOWN)
                handleShowdown()
                return
            }
            GameStage.SHOWDOWN -> return // Уже на вскрытии, ничего не делаем
        }

        // 3. Эта логика выполняется только для обычных раундов торгов
        advanceToNextActivePlayer()
    }

    private fun getPlayerState(userId: String): PlayerState? {
        return gameState.playerStates.find { it.player.userId == userId }
    }

    private fun handleShowdown() {
        println("SHOWDOWN CALLED")
        launch {
            // Находим претендентов на банк
            val contendersForPot = gameState.playerStates.filter { !it.hasFolded }

            if (contendersForPot.isEmpty()) return@launch // Никто не претендует, пропускаем

            if (contendersForPot.size == 1) {
                // Если на банк претендует один, он его и забирает
                givePrize(mapOf(contendersForPot.first().player.userId to gameState.pot))
            } else {
                // Несколько претендентов, оцениваем их руки
                val hands = contendersForPot.map { playerState ->
                    playerState.player.userId to HandEvaluator.evaluate(playerState.cards + gameState.communityCards)
                }
                calculateWinners(hands, gameState.playerStates)
            }

            gameState = gameState.copy(stage = GameStage.SHOWDOWN, turnExpiresAt = null)
            gameRoomService.updatePlayerStatesInRoom(roomId, gameState.playerStates)
            checkForSpectators()
            broadcastGameState()
            delay(5000L) // задержка перед следующей раздачей
            startNewHand()
        }
    }

    private fun givePrize(winners: Map<String, Long>, isNeedShow: Boolean = true) {
        gameState = gameState.copy(
            playerStates = gameState.playerStates.map { playerState ->
                if (playerState.player.userId in winners.keys) {
                    playerState.copy(
                        player = playerState.player.copy(stack = playerState.player.stack + (winners[playerState.player.userId] ?: 0L))
                    )
                } else playerState
            }
        )
        if(isNeedShow) {
            val payments = winners.map { it.key to it.value }
            launch { gameRoomService.broadcast(roomId, OutgoingMessage.BoardResult(payments)) }
        }
    }

    private fun calculateWinners(list: List<Pair<String, Int>>, playerStates: List<PlayerState>, runCount: Int = 1) {
        val topRankPlayerIds = list.sortedByDescending { it.second }.groupBy({ it.second }, { it.first }).toList().map { it.second }
        val contributionsMap = playerStates.associate { it.player.userId to it.handContribution }.toMutableMap()
        val payments = mutableMapOf<String, Long>()
        topRankPlayerIds.forEach{ winnerGroup ->
            // Находим минимальную ставку среди победителей этого уровня
            val minContributionInGroup = winnerGroup.minOfOrNull { contributionsMap[it] ?: 0L } ?: 0L

            // Если у этих победителей уже не осталось денег на столе, они не могут больше ничего выиграть.
            if (minContributionInGroup == 0L) return@forEach // Переходим к следующей группе победителей

            var currentSidePot = 0L
            // Собираем побочный банк со всех, у кого еще остались деньги на столе.
            contributionsMap.keys.forEach { userId ->
                val playerContribution = contributionsMap[userId] ?: 0L
                val amountToTake = minOf(playerContribution, minContributionInGroup)
                if (amountToTake > 0) {
                    currentSidePot += amountToTake
                    // Уменьшаем "оставшуюся" ставку игрока
                    contributionsMap[userId] = playerContribution - amountToTake
                }
            }
            // Распределяем собранный побочный банк между победителями этого уровня
            if (currentSidePot > 0 && winnerGroup.isNotEmpty()) {
                val prizePerWinner = currentSidePot / winnerGroup.size / runCount
                winnerGroup.forEach { winnerId ->
                    payments[winnerId] = (payments[winnerId] ?: 0L) + prizePerWinner
                }
            }
        }
        println("payments: $payments")
        givePrize(payments)
    }

    /**
     * Этот метод ОБЯЗАТЕЛЬНО нужно вызвать, когда игра заканчивается,
     * чтобы остановить все запущенные корутины и избежать утечек памяти.
     */
    fun destroy() {
        blindIncreaseJob?.cancel()
        job.cancel()
    }

    fun getPersonalizedGameStateFor(userId: String): GameState? {
        if(!isGameStarted) return null

        val publicGameState = gameState.copy(
            playerStates = gameState.playerStates.map { it.copy(cards = emptyList()) }
        )
        val playerStateInHand = gameState.playerStates.find { it.player.userId == userId }
        return if (playerStateInHand != null) {
            val personalizedState = gameState.copy(
                playerStates = gameState.playerStates.map { otherPlayerState ->
                    val shouldHideCards = otherPlayerState.hasFolded ||
                            (otherPlayerState.player.userId != userId && gameState.stage != GameStage.SHOWDOWN)
                    if (shouldHideCards) {
                        otherPlayerState.copy(cards = emptyList())
                    } else {
                        otherPlayerState
                    }
                }
            )
            personalizedState
        } else publicGameState
    }

    private suspend fun broadcastGameState(forceRevealCards: Boolean = false) {
        // 1. Получаем ПОЛНЫЙ список всех, кто в комнате (игроки + зрители)
        val room = gameRoomService.getRoom(roomId) ?: return
        val allPlayersInRoom = room.players

        // 2. Создаем "публичную" версию состояния, где все карты скрыты
        val publicGameState = gameState.copy(
            playerStates = gameState.playerStates.map { it.copy(cards = emptyList()) }
        )
        val publicMessage = OutgoingMessage.GameStateUpdate(publicGameState)

        // 3. Рассылаем сообщения
        allPlayersInRoom.forEach { player ->
            // Ищем, участвует ли этот игрок в текущей раздаче
            val playerStateInHand = gameState.playerStates.find { it.player.userId == player.userId }

            if (playerStateInHand != null) {
                // Если это игрок в раздаче - отправляем ему персональное состояние
                val personalizedState = gameState.copy(
                    playerStates = gameState.playerStates.map { otherPlayerState ->
                        // Условие, при котором мы скрываем карты другого игрока:
                        val shouldHideCards =
                            // 1. Если игрок сбросил карты
                            otherPlayerState.hasFolded ||
                            // 2. ИЛИ если это не принудительное вскрытие, И это не наши карты, И это не шоудаун
                            (!forceRevealCards && otherPlayerState.player.userId != player.userId && gameState.stage != GameStage.SHOWDOWN)
                        if (shouldHideCards) {
                            otherPlayerState.copy(cards = emptyList())
                        } else {
                            otherPlayerState
                        }
                    }
                )
                val personalizedMessage = OutgoingMessage.GameStateUpdate(personalizedState)
                gameRoomService.sendToPlayer(roomId, player.userId, personalizedMessage)
            } else {
                // Если это зритель - отправляем ему публичное состояние
                if(gameState.stage != GameStage.SHOWDOWN) gameRoomService.sendToPlayer(roomId, player.userId, publicMessage)
                else gameRoomService.sendToPlayer(roomId, player.userId, OutgoingMessage.GameStateUpdate(gameState))
            }
        }
    }

    private fun initiateRunItMultipleTimesNegotiation() {
        launch {
            broadcastGameState(forceRevealCards = true)
            println("----RUN MULTIPLE CALLED----")
            calculateAndBroadcastEquity(gameState.communityCards, 1)
            val activePlayers = gameState.playerStates.filter { !it.hasFolded }
            val room = gameRoomService.getRoom(roomId)

            if (room?.gameMode != GameMode.CASH) {
                // Если турнир, то крутим один раз
                executeMultiRunShowdown(1); return@launch
            }

            if(gameState.stage == GameStage.RIVER) {
                // Если нам не нужно выбирать количество круток, то сразу крутим один раз
                executeMultiRunShowdown(1); return@launch
            }
            
            val equityPlayersHands = activePlayers.map { it.cards }
            val equityResult = calculateEquity(equityPlayersHands, gameState.communityCards)
            val equitiesMap = activePlayers.mapIndexed { index, ps -> ps.player.userId to equityResult.wins[index] }.toMap()

            val underdogId = equitiesMap.minByOrNull { it.value }?.key
            if (underdogId == null) {
                // Если по какой-то причине не нашли (хотя не должны), просто крутим один раз
                executeMultiRunShowdown(1); return@launch
            }
            val contenderIds = activePlayers.map { it.player.userId }.toSet()
            val favoriteIds = contenderIds - underdogId

            // Сохраняем состояние предложения
            runItOffer = RunItOffer(underdogId, favoriteIds.toSet())
            runItState = RunItState.AWAITING_UNDERDOG_CHOICE

            // Отсылаем предложение андердогу
            gameRoomService.sendToPlayer(roomId, underdogId, OutgoingMessage.OfferRunItForUnderdog(System.currentTimeMillis() + 15_000L))
            runItTimerJob = launch {
                delay(15_000L)
                // Если андердог не ответил, автоматически выбираем "Run it once"
                processUnderdogRunChoice(underdogId, 1)
            }
        }
    }

    private suspend fun executeMultiRunShowdown(runCount: Int) {
        // Возвращаем разницу игроку с максимальным стеком
        val contributions = gameState.playerStates.map { it.player.userId to it.handContribution }.sortedByDescending { it.second }
        val topPlayerId = contributions[0].first
        val diff = contributions[0].second - contributions[1].second
        givePrize(mapOf(topPlayerId to diff), isNeedShow = false)
        gameState = gameState.copy(
            turnExpiresAt = null,
            playerStates = gameState.playerStates.map {
                if (it.player.userId == topPlayerId) {
                    it.copy(
                        handContribution = it.handContribution - diff,
                        currentBet = it.currentBet - diff
                    )
                } else {
                    it
                }
            }
        )

        val contenders = gameState.playerStates.filter { !it.hasFolded }
        val initialCommunityCards = gameState.communityCards

        val cardsNeededPerStreet = listOf(3, 1, 1).drop(initialCommunityCards.size.takeIf { it > 0 }?.let { if (it < 3) 1 else it - 2 } ?: 0)
        
        // --- ГЛАВНЫЙ ЦИКЛ ПО ПРОГОНАМ ДОСОК ---
        for (run in 1..runCount) {
            // Оповещаем клиент, что начинается новый прогон
            gameRoomService.broadcast(roomId, OutgoingMessage.StartBoardRun(run, runCount))

            val currentRunCommunityCards = initialCommunityCards.toMutableList()
            var isFirst = true

            // --- ВНУТРЕННИЙ ЦИКЛ ПО УЛИЦАМ (ФЛОП, ТЕРН, РИВЕР) ---
            for (cardsToDeal in cardsNeededPerStreet) {
                // Считаем и отправляем эквити для текущего состояния
                if(isFirst) isFirst = false
                else {
                    calculateAndBroadcastEquity(currentRunCommunityCards, run)
                    delay(4000)
                }

                // Раздаем карты для следующей улицы
                currentRunCommunityCards.addAll(deck.deal(cardsToDeal))

                // Обновляем GameState для этого прогона
                val tempGameState = gameState.copy(
                    communityCards = currentRunCommunityCards,
                    runIndex = run
                )
                gameRoomService.broadcast(roomId, OutgoingMessage.GameStateUpdate(tempGameState))
            }

            // Финальное эквити, когда уже 5 карт на столе
            calculateAndBroadcastEquity(currentRunCommunityCards, run)

            // Определяем победителя для этой доски
            val hands = contenders.map { ps -> ps.player.userId to HandEvaluator.evaluate(ps.cards + currentRunCommunityCards) }
            calculateWinners(hands, gameState.playerStates, runCount)
            delay(4000)
        }

        // После распределения всех банков проверяем, не выбыл ли кто-то
        gameRoomService.updatePlayerStatesInRoom(roomId, gameState.playerStates)
        checkForSpectators()
        delay(5000L)
        startNewHand()
    }

    private suspend fun checkForSpectators() {
        // Находим всех игроков, которые выбыли в этой раздаче
        val bustedPlayers = gameState.playerStates.filter { it.player.stack <= 0 && it.player.status != PlayerStatus.SPECTATING }
        if (bustedPlayers.isNotEmpty()) {
            // Для каждого выбывшего игрока вызываем метод сервиса
            bustedPlayers.forEach { bustedPlayerState ->
                gameRoomService.updatePlayerStatus(
                    roomId = roomId,
                    userId = bustedPlayerState.player.userId,
                    newStatus = PlayerStatus.SPECTATING,
                    newStack = 0
                )
            }
        }
    }

    fun processUnderdogRunChoice(userId: String, times: Int) {
        runItTimerJob?.cancel()
        val offer = runItOffer ?: return
        if (runItState != RunItState.AWAITING_UNDERDOG_CHOICE || userId != offer.underdogId) return

        if (times > 1) {
            offer.chosenRuns = times
            runItState = RunItState.AWAITING_FAVORITE_CONFIRMATION

            // Отправляем запрос на подтверждение всем остальным
            val confirmationRequest = OutgoingMessage.OfferRunItMultipleTimes(offer.underdogId, times, System.currentTimeMillis() + 15_000L)
            launch {
                offer.favoriteIds.forEach { favoriteId ->
                    gameRoomService.sendToPlayer(roomId, favoriteId, confirmationRequest)
                }
            }
            runItTimerJob = launch {
                delay(15_000L)
                runItState = RunItState.NONE
                runItOffer = null
                launch { executeMultiRunShowdown(1) }
            }
        } else {
            // Андердог выбрал 1 раз, сразу запускаем
            runItState = RunItState.NONE
            runItOffer = null
            launch { executeMultiRunShowdown(1) }
        }
    }

    fun processFavoriteRunConfirmation(userId: String, accepted: Boolean) {
        val offer = runItOffer ?: return
        if (runItState != RunItState.AWAITING_FAVORITE_CONFIRMATION || userId !in offer.favoriteIds) return

        offer.favoriteResponses[userId] = accepted

        // Если все фавориты ответили
        if (offer.favoriteResponses.keys == offer.favoriteIds) {
            runItTimerJob?.cancel()
            val allAccepted = offer.favoriteResponses.values.all { it }
            val finalRunCount = if (allAccepted) offer.chosenRuns else 1

            runItState = RunItState.NONE
            runItOffer = null
            launch { executeMultiRunShowdown(finalRunCount) }
        }
    }

    private suspend fun calculateAndBroadcastEquity(communityCards: List<Card>, runIndex: Int) {
        val contenders = gameState.playerStates.filter { !it.hasFolded }
        if (contenders.size < 2) return

        // Расчет эквити
        val equityPlayersHands = contenders.map { it.cards }
        val equityResult = calculateEquity(equityPlayersHands, communityCards)
        val equitiesMap = contenders.mapIndexed { index, ps -> ps.player.userId to equityResult.wins[index] }.toMap()

        // Расчет аутов для андердогов
        val sortedEquities = equitiesMap.entries.sortedByDescending { it.value }
        val topEquity = sortedEquities[0].value
        val topPlayerIds = sortedEquities.filter { (topEquity - it.value) < 2.0 }.map { it.key }.toSet()
        val underdogs = contenders.filter { it.player.userId !in topPlayerIds }

        val outsMap = mutableMapOf<String, OutsInfo>()

        if (topPlayerIds.isNotEmpty() && communityCards.isNotEmpty()) {
            // coroutineScope гарантирует, что мы дождемся завершения всех запущенных в нем задач
            coroutineScope {
                // 1. Создаем список асинхронных задач для каждого андердога
                val outsJobs = underdogs.map { underdog ->
                    async {
                        val opponentHands = contenders
                            .filter { it.player.userId != underdog.player.userId }
                            .map { it.cards }

                        // Выполняем тяжелый расчет
                        val (directOuts, hasIndirectOuts) = calculateLiveOuts(underdog.cards, opponentHands, communityCards)

                        // Возвращаем пару: ID игрока и результат
                        underdog.player.userId to when {
                            directOuts.isNotEmpty() -> OutsInfo.DirectOuts(directOuts)
                            hasIndirectOuts -> OutsInfo.RunnerRunner
                            else -> OutsInfo.DrawingDead
                        }
                    }
                }
                // 2. Ждем, пока ВСЕ задачи по расчету аутов завершатся
                val outsResults = outsJobs.awaitAll()

                // 3. Собираем результаты в нашу карту
                outsMap.putAll(outsResults)
            }
        }

        // Дополнительная проверка на ничью
        val finalOutsMap = outsMap.toMutableMap()
        outsMap.forEach { (userId, outsInfo) ->
            // Если для игрока определен статус "Drawing Dead"...
            if (outsInfo is OutsInfo.DrawingDead) {
                val playerEquity = equitiesMap[userId] ?: 0.0
                // ...но его эквити все еще больше 1%, то это не настоящий "Drawing Dead".
                // В этом случае мы просто не будем показывать для него информацию об аутах.
                if (playerEquity > 1.0) {
                    finalOutsMap.remove(userId)
                }
            }
        }

        // Отправка сообщения
        val equityMessage = OutgoingMessage.AllInEquityUpdate(equitiesMap, finalOutsMap, runIndex)
        gameRoomService.broadcast(roomId, equityMessage)
    }

    fun getCurrentGameState(): GameState = gameState

    fun updatePlayerConnectionStatus(userId: String, isConnected: Boolean) {
        // Находим игрока в текущем состоянии игры и обновляем его статус
        val updatedPlayerStates = gameState.playerStates.map {
            if (it.player.userId == userId) {
                it.copy(player = it.player.copy(isConnected = isConnected))
            } else {
                it
            }
        }
        // Обновляем основной gameState
        gameState = gameState.copy(playerStates = updatedPlayerStates)
    }

    fun processSocialAction(senderUserId: String, action: SocialAction) {
        val broadcastMessage = OutgoingMessage.SocialActionBroadcast(
            fromPlayerId = senderUserId,
            action = action
        )
        // Рассылаем всем в комнате
        launch {
            gameRoomService.broadcast(roomId, broadcastMessage)
        }
    }
}