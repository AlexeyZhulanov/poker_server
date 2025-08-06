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

    init {
        val room = gameRoomService.getRoom(roomId)
        // Если это турнир, запускаем таймер
        if(room != null) {
            if (room.gameMode == GameMode.TOURNAMENT && room.blindStructureType != null) {
                startBlindTimer(room.blindStructureType, room)
            }
        }
    }

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
                delay(durationMinutes * 60 * 1000L)
                currentLevelIndex++

                // Получаем информацию о новом уровне блайндов
                val newLevel = room.blindStructure?.getOrNull(currentLevelIndex)
                if (newLevel != null) {
                    // Создаем и рассылаем сообщение
                    val message = OutgoingMessage.BlindsUp(newLevel.smallBlind, newLevel.bigBlind, newLevel.ante, newLevel.level)
                    gameRoomService.broadcast(room.roomId, message)
                }
            }
        }
    }

    fun startGame() {
        startNewHand()
    }

    fun startNewHand() {
        val room = gameRoomService.getRoom(roomId) ?: return // Если комната уже не существует, выходим

        if (room.players.size < 2) {
            println("EXIT < 2 players")
            gameState = GameState(roomId = roomId)
            val message = OutgoingMessage.GameStateUpdate(null)
            launch { gameRoomService.sendToPlayer(roomId, room.players.first().userId, message) }
            blindIncreaseJob?.cancel()
            gameRoomService.setAllPlayersUnready(roomId = roomId)
            return
        }

        // 1. Определяем игроков для новой раздачи (у кого есть стек)
        playersInGame = if (gameState.playerStates.isEmpty()) {
            // Первая раздача, берем всех из комнаты
            room.players.filter { it.status == PlayerStatus.IN_HAND }
        } else {
            // Последующие раздачи, фильтруем тех, у кого остались фишки
            gameState.playerStates.filter { it.player.stack > 0 }.map { it.player }
        }

        // 2. Проверяем, не закончился ли турнир
        if (playersInGame.size < 2) {
            if(room.gameMode == GameMode.TOURNAMENT) {
                val winnerUsername = playersInGame.firstOrNull()?.username ?: "Unknown"
                // Отправляем всем сообщение о победителе
                launch {
                    gameRoomService.broadcast(room.roomId, OutgoingMessage.TournamentWinner(winnerUsername))
                }
                destroy() // Останавливаем движок
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
                player = player.copy(stack = player.stack - totalContribution),
                cards = deck.deal(2),
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
            amountToCall = bigBlindAmount,
            lastRaiseAmount = bigBlindAmount,
            lastAggressorPosition = bbPos
        )
        // 7. Рассылаем всем обновленное состояние
        launch { broadcastGameState() }
    }

    //--- Обработка действий игрока ---

    fun processFold(userId: String) {
        updatePlayerState(userId) { it.copy(hasFolded = true, hasActedThisRound = true) }
        findNextPlayerOrEndRound()
    }

    fun processCheck(userId: String) {
        val playerState = getPlayerState(userId) ?: return
        if (playerState.currentBet < gameState.amountToCall) {
            println("Cannot check")
            launch { gameRoomService.sendToPlayer(roomId, userId, OutgoingMessage.ErrorMessage("Cannot check")) }
            return
        }
        updatePlayerState(userId) { it.copy(hasActedThisRound = true) }
        findNextPlayerOrEndRound()
    }

    fun processCall(userId: String) {
        val playerState = getPlayerState(userId) ?: return
        val amountToCall = minOf(playerState.player.stack, gameState.amountToCall - playerState.currentBet)

        val newStack = playerState.player.stack - amountToCall
        updatePlayerState(userId) {
            it.copy(
                player = it.player.copy(stack = newStack),
                currentBet = it.currentBet + amountToCall,
                handContribution = it.handContribution + amountToCall,
                hasActedThisRound = true,
                isAllIn = newStack <= 0
            )
        }
        gameState = gameState.copy(pot = gameState.pot + amountToCall)
        findNextPlayerOrEndRound()
    }

    fun processBet(userId: String, amount: Long) {
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
        updatePlayerState(userId) {
            it.copy(
                player = it.player.copy(stack = newStack),
                currentBet = amount,
                handContribution = it.handContribution + contribution,
                hasActedThisRound = true,
                isAllIn = newStack <= 0
            )
        }

        gameState = gameState.copy(
            pot = gameState.pot + contribution,
            amountToCall = amount
        )
        findNextPlayerOrEndRound()
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
                // Используем наш существующий метод для передачи фишек победителю.
                distributeWinnings(listOf(winnerId), gameState.pot)
            }

            // Рассылаем финальное состояние, чтобы UI показал, как победитель получил фишки,
            // и запускаем новую раздачу через 3 секунды.
            launch {
                broadcastGameState()
                delay(2000L) // Пауза для просмотра результата
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
        var nextPos = (gameState.activePlayerPosition + 1) % playersInGame.size
        while (gameState.playerStates[nextPos].hasFolded || gameState.playerStates[nextPos].isAllIn) {
            nextPos = (nextPos + 1) % playersInGame.size
        }
        gameState = gameState.copy(activePlayerPosition = nextPos)
        launch { broadcastGameState() }
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

        val newPlayerStates = gameState.playerStates.map { it.copy(currentBet = 0, hasActedThisRound = false) }
        val nextAggressor = if (gameState.stage == GameStage.PRE_FLOP) null else gameState.lastAggressorPosition
        gameState = gameState.copy(
            playerStates = newPlayerStates,
            amountToCall = 0,
            lastRaiseAmount = 0,
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
            // Убедимся, что все ставки на столе включены в общий вклад игроков
            // Это важно для корректного расчета банков
            gameState.playerStates.forEach { ps ->
                updatePlayerState(ps.player.userId) {
                    it.copy(handContribution = it.handContribution + it.currentBet, currentBet = 0)
                }
            }

            // 1. Рассчитываем все банки (основной и побочные)
            val pots = calculatePots()

            // 2. Итерируем по каждому банку и определяем для него победителя
            pots.forEach { pot ->
                // Находим претендентов на этот конкретный банк
                val contendersForPot = gameState.playerStates
                    .filter { it.player.userId in pot.eligiblePlayerIds && !it.hasFolded }

                if (contendersForPot.isEmpty()) return@forEach // Никто не претендует, пропускаем

                if (contendersForPot.size == 1) {
                    // Если на банк претендует один, он его и забирает
                    distributeWinnings(listOf(contendersForPot.first().player.userId), pot.amount)
                } else {
                    // Несколько претендентов, оцениваем их руки
                    val hands = contendersForPot.map { playerState ->
                        playerState.player.userId to HandEvaluator.evaluateBestHandDetailed(playerState.cards + gameState.communityCards)
                    }

                    val bestHandResult = hands.maxByOrNull { it.second.result }?.second?.result
                    val winners = hands.filter { it.second.result == bestHandResult }

                    // Сохраняем выигрышные карты для отправки на клиент
                    val showdownHands = winners.associate { (userId, evaluatedHand) -> userId to evaluatedHand.hand }
                    gameState = gameState.copy(showdownResults = (gameState.showdownResults ?: emptyMap()) + showdownHands)

                    // Распределяем этот конкретный банк между победителями
                    distributeWinnings(winners.map { it.first }, pot.amount)
                }
            }

            checkForSpectators()

            gameState = gameState.copy(stage = GameStage.SHOWDOWN)

            broadcastGameState()
            delay(5000L) // задержка перед следующей раздачей

            // Перед стартом новой руки очищаем результаты вскрытия
            gameState = gameState.copy(showdownResults = null)
            startNewHand()
        }
    }

    private fun distributeWinnings(winnerIds: List<String>, totalPot: Long) {
        val prizePerWinner = totalPot / winnerIds.size

        winnerIds.forEach { winnerId ->
            updatePlayerState(winnerId) { playerState ->
                playerState.copy(player = playerState.player.copy(stack = playerState.player.stack + prizePerWinner))
            }
        }
    }

    private fun calculatePots(): List<Pot> {
        val pots = mutableListOf<Pot>()
        // 1. Собираем информацию о всех, кто не сбросил карты
        val contenders = gameState.playerStates
            .filter { !it.hasFolded && it.handContribution > 0 }
            .sortedBy { it.handContribution } // Сортируем по возрастанию их вклада в банк

        if (contenders.isEmpty()) return emptyList()

        // 2. Создаем список "слоев" ставок
        val contributionLevels = contenders.map { it.handContribution }.distinct()
        var lastLevel = 0L

        // 3. Идем по каждому уровню ставок и формируем банки
        contributionLevels.forEach { level ->
            val potAmount = (level - lastLevel) * contenders.count { it.handContribution >= level }
            val eligiblePlayerIds = contenders
                .filter { it.handContribution >= level }
                .map { it.player.userId }
                .toSet()

            pots.add(Pot(potAmount, eligiblePlayerIds))
            lastLevel = level
        }

        // Объединяем банки с одинаковым набором претендентов
        return pots.groupBy { it.eligiblePlayerIds }
            .map { (ids, potList) ->
                Pot(potList.sumOf { it.amount }, ids)
            }
    }

    /**
     * Этот метод ОБЯЗАТЕЛЬНО нужно вызвать, когда игра заканчивается,
     * чтобы остановить все запущенные корутины и избежать утечек памяти.
     */
    fun destroy() {
        blindIncreaseJob?.cancel()
        job.cancel()
    }

    private suspend fun broadcastGameState(forceRevealCards: Boolean = false) {
        // Для каждого игрока в текущей раздаче...
        gameState.playerStates.forEach { playerState ->
            // 1. Создаем его персональную версию состояния игры
            val personalizedState = gameState.copy(
                playerStates = gameState.playerStates.map { otherPlayerState ->
                    // Скрываем карты всех, кроме него самого (если не вскрытие)
                    if (!forceRevealCards && otherPlayerState.player.userId != playerState.player.userId && gameState.stage != GameStage.SHOWDOWN) {
                        otherPlayerState.copy(cards = emptyList())
                    } else {
                        otherPlayerState
                    }
                }
            )
            // 2. Создаем сообщение
            val message = OutgoingMessage.GameStateUpdate(personalizedState)

            // 3. Просим GameRoomService отправить это сообщение конкретному игроку
            gameRoomService.sendToPlayer(roomId, playerState.player.userId, message)
        }
    }

    private fun initiateRunItMultipleTimesNegotiation() {
        launch {
            broadcastGameState(forceRevealCards = true)
            println("----RUN MULTIPLE CALLED----")
            calculateAndBroadcastEquity(gameState.communityCards, 1)
            val activePlayers = gameState.playerStates.filter { !it.hasFolded }
            val playersInAllIn = activePlayers.filter { it.isAllIn }
            val room = gameRoomService.getRoom(roomId)

            if (room?.gameMode != GameMode.CASH) {
                // Если турнир, то крутим один раз
                executeMultiRunShowdown(1); return@launch
            }

            val underdog = playersInAllIn.minByOrNull { it.handContribution }
            if (underdog == null) {
                // Если по какой-то причине не нашли (хотя не должны), просто крутим один раз
                executeMultiRunShowdown(1); return@launch
            }
            val underdogId = underdog.player.userId
            val contenderIds = activePlayers.map { it.player.userId }.toSet()
            val favoriteIds = contenderIds - underdogId

            // Сохраняем состояние предложения
            runItOffer = RunItOffer(underdogId, favoriteIds.toSet())
            runItState = RunItState.AWAITING_UNDERDOG_CHOICE

            // Отсылаем предложение андердогу
            gameRoomService.sendToPlayer(roomId, underdogId, OutgoingMessage.OfferRunItForUnderdog)
        }
    }

    private suspend fun executeMultiRunShowdown(runCount: Int) {
        val contenders = gameState.playerStates.filter { !it.hasFolded }
        val initialCommunityCards = gameState.communityCards
        //val usedCards = contenders.flatMap { it.cards } + initialCommunityCards

//        val remainingDeck = CardDeck.buildFullDeck()
//            .filter { it !in usedCards }
//            .toMutableList()
//            .apply { secureShuffle() }

        val cardsNeededPerStreet = listOf(3, 1, 1).drop(initialCommunityCards.size.takeIf { it > 0 }?.let { if (it < 3) 1 else it - 2 } ?: 0)

        val boardResults = mutableListOf<BoardResult>()
        val potPerRun = gameState.pot / runCount

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
                    delay(2000)
                }

                // Раздаем карты для следующей улицы
                currentRunCommunityCards.addAll(deck.deal(cardsToDeal))
//                repeat(cardsToDeal) {
//                    if (remainingDeck.isNotEmpty()) {
//                        currentRunCommunityCards.add(remainingDeck.removeFirst())
//                    }
//                }

                // Обновляем GameState для этого прогона
                val tempGameState = gameState.copy(
                    communityCards = currentRunCommunityCards,
                    runIndex = run
                )
                gameRoomService.broadcast(roomId, OutgoingMessage.GameStateUpdate(tempGameState))
            }

            // Финальное эквити, когда уже 5 карт на столе
            calculateAndBroadcastEquity(currentRunCommunityCards, run)
            delay(2000)

            // Определяем победителя для этой доски
            val hands = contenders.map { ps -> ps.player.userId to HandEvaluator.evaluate(ps.cards + currentRunCommunityCards) }
            val bestHand = hands.maxByOrNull { it.second }?.second
            val winners = hands.filter { it.second == bestHand }.map { (userId, _) -> getPlayerState(userId)!!.player.username }

            boardResults.add(BoardResult(currentRunCommunityCards, winners))
            distributeWinnings(winners.map { username -> contenders.find { it.player.username == username }!!.player.userId }, potPerRun)
        }

        // Отправляем итоговый результат со всеми досками
        gameRoomService.broadcast(roomId, OutgoingMessage.RunItMultipleTimesResult(boardResults))

        // После распределения всех банков проверяем, не выбыл ли кто-то
        checkForSpectators()

        delay(7000L)
        startNewHand()
    }

    private suspend fun checkForSpectators() {
        val updatedPlayerStates = gameState.playerStates.map { playerState ->
            if (playerState.player.stack <= 0 && !playerState.hasFolded) {
                // Игрок проиграл все фишки, меняем его статус
                playerState.copy(
                    player = playerState.player.copy(status = PlayerStatus.SPECTATING)
                )
            } else {
                playerState
            }
        }
        gameState = gameState.copy(playerStates = updatedPlayerStates)

        // Оповещаем всех об изменении статусов выбывших игроков
        updatedPlayerStates.forEach { ps ->
            if (ps.player.status == PlayerStatus.SPECTATING) {
                gameRoomService.broadcast(
                    roomId,
                    OutgoingMessage.PlayerStatusUpdate(ps.player.userId, PlayerStatus.SPECTATING, 0)
                )
            }
        }
    }

    fun processUnderdogRunChoice(userId: String, times: Int) {
        val offer = runItOffer ?: return
        if (runItState != RunItState.AWAITING_UNDERDOG_CHOICE || userId != offer.underdogId) return

        if (times > 1) {
            offer.chosenRuns = times
            runItState = RunItState.AWAITING_FAVORITE_CONFIRMATION

            // Отправляем запрос на подтверждение всем остальным
            val confirmationRequest = OutgoingMessage.OfferRunItMultipleTimes(offer.underdogId, times)
            launch {
                offer.favoriteIds.forEach { favoriteId ->
                    gameRoomService.sendToPlayer(roomId, favoriteId, confirmationRequest)
                }
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
        val topEquityPlayerId = equitiesMap.maxByOrNull { it.value }?.key
        val underdogs = contenders.filter { it.player.userId != topEquityPlayerId }

        val outsMap = mutableMapOf<String, OutsInfo>()

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
        // Отправка сообщения
        val equityMessage = OutgoingMessage.AllInEquityUpdate(equitiesMap, outsMap, runIndex)
        gameRoomService.broadcast(roomId, equityMessage)
    }

    fun getCurrentGameState(): GameState = gameState

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