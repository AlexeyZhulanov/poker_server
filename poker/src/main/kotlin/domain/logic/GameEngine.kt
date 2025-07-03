package com.example.domain.logic

import com.example.domain.model.*
import com.example.dto.ws.*
import com.example.services.GameRoomService
import com.example.util.secureShuffle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class GameEngine(
    private val room: GameRoom,
    private val gameRoomService: GameRoomService
) : CoroutineScope {
    private val job = Job()
    override val coroutineContext = job + Dispatchers.Default

    private var deck = CardDeck()
    private var gameState: GameState = GameState(roomId = room.roomId)
    private var roundIsOver: Boolean = false
    private var currentDealerPosition = -1
    private var currentLevelIndex = 0
    private var blindIncreaseJob: Job? = null // Job для таймера повышения блайндов
    private var playersInGame: List<Player> = emptyList()
    private var runItState = RunItState.NONE
    private var runItOffer: RunItOffer? = null

    init {
        // Если это турнир, запускаем таймер
        if (room.gameMode == GameMode.TOURNAMENT && room.levelDurationMinutes != null) {
            startBlindTimer(room.levelDurationMinutes)
        }
    }

    private fun startBlindTimer(durationMinutes: Int) {
        blindIncreaseJob = launch {
            while (isActive) {
                delay(durationMinutes * 60 * 1000L)
                currentLevelIndex++

                // Получаем информацию о новом уровне блайндов
                val newLevel = room.blindStructure?.getOrNull(currentLevelIndex)
                if (newLevel != null) {
                    // Создаем и рассылаем сообщение
                    val message = OutgoingMessage.BlindsUp(newLevel.smallBlind, newLevel.bigBlind, newLevel.level)
                    gameRoomService.broadcast(room.roomId, message)
                }
            }
        }
    }

    fun startGame() {
        startNewHand()
    }

    fun startNewHand() {
        // 1. Определяем игроков для новой раздачи (у кого есть стек)
        this.playersInGame = if (gameState.playerStates.isEmpty()) {
            // Первая раздача, берем всех из комнаты
            room.players
        } else {
            // Последующие раздачи, фильтруем тех, у кого остались фишки
            gameState.playerStates.filter { it.player.stack > 0 }.map { it.player }
        }

        // 2. Проверяем, не закончился ли турнир
        if (playersInGame.size < 2 && room.gameMode == GameMode.TOURNAMENT) {
            val winnerUsername = playersInGame.firstOrNull()?.username ?: "Unknown"
            // Отправляем всем сообщение о победителе
            launch {
                gameRoomService.broadcast(room.roomId, OutgoingMessage.TournamentWinner(winnerUsername))
            }
            destroy() // Останавливаем движок
            return
        }

        // 3. Создаем новую колоду
        deck.newDeck()
        roundIsOver = false
        val smallBlindAmount: Long
        val bigBlindAmount: Long

        // Получаем блайнды в зависимости от режима игры
        if (room.gameMode == GameMode.TOURNAMENT && room.blindStructure != null) {
            val currentLevel = room.blindStructure.getOrNull(currentLevelIndex) ?: room.blindStructure.last()
            smallBlindAmount = currentLevel.smallBlind
            bigBlindAmount = currentLevel.bigBlind
        } else {
            // Фиксированные блайнды для кэш-игры
            smallBlindAmount = 10L
            bigBlindAmount = 20L
        }

        // 4. Двигаем дилера и блайнды
        currentDealerPosition = (currentDealerPosition + 1) % playersInGame.size
        val smallBlindPos = (currentDealerPosition + 1) % playersInGame.size
        val bigBlindPos = (currentDealerPosition + 2) % playersInGame.size
        // Действие начинает игрок после большого блайнда
        val actionPos = (currentDealerPosition + 3) % playersInGame.size

        // 5. Создаем начальное состояние игроков
        val initialPlayerStates = playersInGame.mapIndexed { index, player ->
            PlayerState(
                player = player,
                cards = deck.deal(2), // Раздаем 2 карты каждому
                currentBet = when (index) {
                    smallBlindPos -> smallBlindAmount
                    bigBlindPos -> bigBlindAmount
                    else -> 0L
                },
                handContribution = 0
            )
        }

        // 4. Формируем начальное состояние игры
        gameState = GameState(
            roomId = room.roomId,
            stage = GameStage.PRE_FLOP,
            playerStates = initialPlayerStates,
            dealerPosition = currentDealerPosition,
            activePlayerPosition = actionPos,
            pot = bigBlindAmount + smallBlindAmount, // Сумма блайндов
            lastRaiseAmount = bigBlindAmount // Последняя ставка равна большому блайнду
        )

        // 5. Рассылаем всем обновленное состояние
        launch {
            broadcastGameState()
        }
    }

    //--- Обработка действий игрока ---

    fun processFold(userId: String) {
        updatePlayerState(userId) { it.copy(hasFolded = true) }
        if (!checkForAllInShowdown()) {
            advanceToNextPlayer()
        }
    }

    fun processCheck(userId: String) {
        val playerState = gameState.playerStates.find { it.player.userId == userId } ?: return
        val maxBetOnStreet = gameState.playerStates.maxOfOrNull { it.currentBet } ?: 0

        if (playerState.currentBet != maxBetOnStreet) {
            // Отправляем сообщение об ошибке конкретному игроку
            launch {
                val errorMessage = OutgoingMessage.ErrorMessage("Cannot check, you need to call or raise.")
                gameRoomService.sendToPlayer(room.roomId, userId, errorMessage)
            }
            return
        }
        if (!checkForAllInShowdown()) {
            advanceToNextPlayer()
        }
    }

    fun processBet(userId: String, amount: Long) {
        val playerState = gameState.playerStates.find { it.player.userId == userId } ?: return
        // Проверка, достаточно ли фишек у игрока
        if (amount > playerState.player.stack) {
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

        val totalBet = playerState.currentBet + amount
        updatePlayerState(userId) {
            it.copy(
                player = it.player.copy(stack = it.player.stack - amount), // Уменьшаем стек
                currentBet = it.currentBet + amount, // Увеличиваем ставку в текущем раунде
                handContribution = it.handContribution + amount
            )
        }
        gameState = gameState.copy(
            pot = gameState.pot + amount,
            lastRaiseAmount = if (totalBet > gameState.lastRaiseAmount) totalBet - gameState.lastRaiseAmount else gameState.lastRaiseAmount
        )
        if (!checkForAllInShowdown()) {
            advanceToNextPlayer()
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

    private fun advanceToNextPlayer() {
        if (roundIsOver) return

        val activePlayers = gameState.playerStates.filter { !it.hasFolded && !it.isAllIn }
        if (activePlayers.size <= 1) {
            // Если остался один активный игрок, раунд (и игра) заканчивается
            handleShowdown()
            return
        }

        val startPos = gameState.activePlayerPosition
        var nextPos = (startPos + 1) % playersInGame.size

        // Ищем следующего активного игрока
        while (gameState.playerStates[nextPos].hasFolded || gameState.playerStates[nextPos].isAllIn) {
            nextPos = (nextPos + 1) % playersInGame.size
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

    private fun advanceToNextStage(isAllInShowdown: Boolean = false) {
        // 1. Сначала сбрасываем ставки игроков с прошлого раунда, если это не all-in шоудаун
        if (!isAllInShowdown) {
            val newPlayerStates = gameState.playerStates.map { it.copy(currentBet = 0) }
            gameState = gameState.copy(playerStates = newPlayerStates, lastRaiseAmount = 0)
            roundIsOver = false
        }

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
                // Если мы дошли до ривера в режиме all-in, шоудаун уже обрабатывается в executeStagedShowdown
                if (!isAllInShowdown) {
                    handleShowdown()
                }
                return
            }
            GameStage.SHOWDOWN -> return // Уже на вскрытии, ничего не делаем
        }

        // 3. Если это all-in шоудаун, мы просто рассылаем состояние с новыми картами и выходим
        if (isAllInShowdown) {
            launch { broadcastGameState() }
            return
        }

        // 4. Эта логика выполняется только для обычных раундов торгов
        var firstToAct = (gameState.dealerPosition + 1) % playersInGame.size
        while (gameState.playerStates[firstToAct].hasFolded || gameState.playerStates[firstToAct].isAllIn) {
            firstToAct = (firstToAct + 1) % playersInGame.size
        }
        gameState = gameState.copy(activePlayerPosition = firstToAct)

        launch { broadcastGameState() }
    }

    private fun handleShowdown() {
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

            gameState = gameState.copy(stage = GameStage.SHOWDOWN)

            broadcastGameState()
            delay(8000L)

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

    private suspend fun broadcastGameState() {
        // Для каждого игрока в текущей раздаче...
        gameState.playerStates.forEach { playerState ->
            // 1. Создаем его персональную версию состояния игры
            val personalizedState = gameState.copy(
                playerStates = gameState.playerStates.map { otherPlayerState ->
                    // Скрываем карты всех, кроме него самого (если не вскрытие)
                    if (otherPlayerState.player.userId != playerState.player.userId && gameState.stage != GameStage.SHOWDOWN) {
                        otherPlayerState.copy(cards = emptyList())
                    } else {
                        otherPlayerState
                    }
                }
            )
            // 2. Создаем сообщение
            val message = OutgoingMessage.GameStateUpdate(personalizedState)

            // 3. Просим GameRoomService отправить это сообщение конкретному игроку
            gameRoomService.sendToPlayer(room.roomId, playerState.player.userId, message)
        }
    }

    private fun checkForAllInShowdown(): Boolean {
        val activePlayers = gameState.playerStates.filter { !it.hasFolded }
        val playersInAllIn = activePlayers.filter { it.isAllIn }

        val isAllInSituation = activePlayers.size >= 2 && (activePlayers.size - playersInAllIn.size) <= 1

        if (isAllInSituation && runItState == RunItState.NONE) {
            launch {
                val contenders = gameState.playerStates.filter { !it.hasFolded }
                val contenderIds = contenders.map { it.player.userId }.toSet()

                // Рассчитываем, сколько раз можно прокрутить доску
                val usedCardsCount = contenders.sumOf { it.cards.size } + gameState.communityCards.size
                val remainingDeckSize = 52 - usedCardsCount
                val cardsNeededPerRun = 5 - gameState.communityCards.size
                val maxRuns = if (cardsNeededPerRun > 0) remainingDeckSize / cardsNeededPerRun else 1

                if (maxRuns > 1) {
                    // Сохраняем состояние предложения
                    runItOffer = RunItOffer(contenderIds, maxRuns)
                    runItState = RunItState.AWAITING_PLAYER_CHOICES

                    val options = (1..maxRuns).toList()
                    val offerMessage = OutgoingMessage.OfferRunItMultipleTimes(options)

                    // Рассылаем предложение ВСЕМ участникам all-in
                    contenderIds.forEach { userId ->
                        gameRoomService.sendToPlayer(room.roomId, userId, offerMessage)
                    }
                } else {
                    // Если нельзя крутить несколько раз, сразу запускаем обычный шоудаун
                    executeMultiRunShowdown(1)
                }
            }
            return true
        }
        return false
    }

    private suspend fun executeMultiRunShowdown(runCount: Int) {
        calculateAndBroadcastEquity()
        delay(3000L) // Пауза для игроков

        while (gameState.stage != GameStage.RIVER && gameState.stage != GameStage.SHOWDOWN) {
            advanceToNextStage(isAllInShowdown = true) // Раздаем следующую улицу
            calculateAndBroadcastEquity() // Пересчитываем и показываем новое эквити
            delay(3000L)
        }

        val contenders = gameState.playerStates.filter { !it.hasFolded }
        val initialCommunityCards = gameState.communityCards
        val usedCards = contenders.flatMap { it.cards } + initialCommunityCards

        // 1. Создаем колоду из оставшихся карт и перемешиваем ее
        val remainingDeck = CardDeck.buildFullDeck()
            .filter { it !in usedCards }
            .toMutableList()
            .apply { secureShuffle() }

        val cardsNeededPerRun = 5 - initialCommunityCards.size
        val boardResults = mutableListOf<BoardResult>()
        val potPerRun = gameState.pot / runCount // Делим банк поровну

        // 2. Выполняем нужное количество прогонов
        repeat(runCount) {
            // 3. Раздаем карты для этой доски
            val runCommunityCards = remainingDeck.take(cardsNeededPerRun)
            // Удаляем розданные карты, чтобы они не использовались в следующем прогоне
            repeat(cardsNeededPerRun) { remainingDeck.removeFirst() }

            val fullBoard = initialCommunityCards + runCommunityCards

            // 4. Оцениваем руки для этой конкретной доски
            val hands = contenders.map { playerState ->
                playerState.player.userId to HandEvaluator.evaluate(playerState.cards + fullBoard)
            }
            val bestHand = hands.maxByOrNull { it.second }?.second
            val winnersForThisBoard = hands.filter { it.second == bestHand }.map { (userId, _) ->
                gameState.playerStates.find { it.player.userId == userId }!!.player.username
            }

            // 5. Сохраняем результат для этой доски и распределяем часть банка
            boardResults.add(BoardResult(fullBoard, winnersForThisBoard))
            distributeWinnings(winnersForThisBoard.map { username -> contenders.find { it.player.username == username }!!.player.userId }, potPerRun)
        }

        // 6. Отправляем итоговый результат со всеми досками на клиент
        val finalMessage = OutgoingMessage.RunItMultipleTimesResult(boardResults)
        gameRoomService.broadcast(room.roomId, finalMessage)

        // 7. Ждем и начинаем новую раздачу
        delay(10000L) // Даем больше времени на просмотр
        startNewHand()
    }

    fun processRunItSelection(userId: String, times: Int) {
        val offer = runItOffer ?: return
        // Проверяем, что мы в правильном состоянии и ответ пришел от участника раздачи
        if (runItState != RunItState.AWAITING_PLAYER_CHOICES || userId !in offer.contenders) {
            return
        }
        // Проверяем, что игрок еще не отвечал и его выбор корректен
        if (offer.responses.containsKey(userId) || times !in 1..offer.maxRuns) {
            return
        }

        offer.responses[userId] = times

        // Проверяем, ответили ли все
        if (offer.responses.size == offer.contenders.size) {
            // Все ответили. Проверяем, все ли выбрали одно и то же число > 1
            val firstChoice = offer.responses.values.first()
            val allChoseSame = offer.responses.values.all { it == firstChoice }

            val runCount = if (allChoseSame && firstChoice > 1) {
                firstChoice
            } else {
                1 // Если есть разногласия или кто-то выбрал 1, крутим один раз
            }

            // Сбрасываем состояние и запускаем исполнение
            runItState = RunItState.NONE
            this.runItOffer = null
            launch { executeMultiRunShowdown(runCount) }
        }
        // Если ответили еще не все, просто ждем
    }

    private suspend fun calculateAndBroadcastEquity() {
        val contenders = gameState.playerStates.filter { !it.hasFolded }
        if (contenders.size < 2) return

        // Расчет эквити
        val equityPlayersHands = contenders.map { it.cards }
        val equityResult = calculateEquity(equityPlayersHands, gameState.communityCards)
        val equitiesMap = contenders.mapIndexed { index, ps -> ps.player.userId to equityResult.wins[index] }.toMap()

        // Расчет аутов для андердогов
        val topEquityPlayerId = equitiesMap.maxByOrNull { it.value }?.key
        val outsMap = mutableMapOf<String, OutsInfo>()
        if (topEquityPlayerId != null) {
            contenders.filter { it.player.userId != topEquityPlayerId }.forEach { underdog ->
                val opponentHands = contenders.filter { it.player.userId != underdog.player.userId }.map { it.cards }
                val (directOuts, hasIndirectOuts) = calculateLiveOuts(underdog.cards, opponentHands, gameState.communityCards)
                outsMap[underdog.player.userId] = when {
                    directOuts.isNotEmpty() -> OutsInfo.DirectOuts(directOuts)
                    hasIndirectOuts -> OutsInfo.RunnerRunner
                    else -> OutsInfo.DrawingDead
                }
            }
        }
        // Отправка сообщения
        val equityMessage = OutgoingMessage.AllInEquityUpdate(equitiesMap, outsMap)
        gameRoomService.broadcast(room.roomId, equityMessage)
    }

    fun getCurrentGameState(): GameState = gameState

    fun processSocialAction(senderUserId: String, action: SocialAction) {
        val broadcastMessage = OutgoingMessage.SocialActionBroadcast(
            fromPlayerId = senderUserId,
            action = action
        )
        // Рассылаем всем в комнате
        launch {
            gameRoomService.broadcast(room.roomId, broadcastMessage)
        }
    }
}