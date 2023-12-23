package bguspl.set.ex;

import bguspl.set.Env;

import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * This class manages the dealer's threads and data
 * @inv players.length > 0
 */
public class Dealer implements Runnable {

    /**
     * The game environment object.
     */
    private final Env env;

    /**
     * Game entities.
     */
    private final Table table;
    private final Player[] players;

    /**
     * The list of card ids that are left in the dealer's deck.
     */
    public final List<Integer> deck; //change to private later

    /**
     * True iff game should be terminated due to an external event.
     */
    public volatile boolean terminate;

    /**
     * The time when the dealer needs to reshuffle the deck due to turn timeout.
     */
    private long reshuffleTime = 0;

    private long lastUpdateTime = 0;

    public Dealer(Env env, Table table, Player[] players) {
        this.env = env;
        this.table = table;
        this.players = players;
        deck = IntStream.range(0, env.config.deckSize).boxed().collect(Collectors.toList());
    }

    /**
     * The dealer thread starts here (main loop for the dealer thread).
     */
    @Override
    public void run() {
        env.logger.info("Thread " + Thread.currentThread().getName() + " starting.");
        for (Player p : players) {
            new Thread(p, "Player" + p.id).start();
        }
        try {
            while (!shouldFinish()) {
                placeCardsOnTable();
                timerLoop();
                updateTimerDisplay(true, env.config.turnTimeoutMillis);
                removeAllCardsFromTable();
                printInfoAboutSets();
            }
        } catch (InterruptedException e) {

        }
        announceWinners();
        closePlayersThreads();
        env.logger.info("Thread " + Thread.currentThread().getName() + " terminated.");
    }

    private void closePlayersThreads() {
        for (Player p : players) {
            p.terminate();
            try {
                p.playerThread.join();
            } catch (InterruptedException e) {
            }
        }
    }

    private Player getPlayer(int id) {
        for (Player p : players)
            if (p.id == id)
                return p;
        return players[0];
    }

    /**
     * The inner loop of the dealer thread that runs as long as the countdown did not time out.
     */
    private void timerLoop() throws InterruptedException {
        reshuffleTime = System.currentTimeMillis();
        lastUpdateTime = System.currentTimeMillis();
        this.env.ui.setCountdown(env.config.turnTimeoutMillis, false);
        long counter = env.config.turnTimeoutMillis;
        while (!terminate && counter >= 0) {
            sleepUntilWokenOrTimeout();
            env.logger.info("thread-"+Thread.currentThread()+" woke up (Dealer)");
            long curr = System.currentTimeMillis();
            boolean foundSet=false;
            if (curr - lastUpdateTime >= 950) { //a second passed
                counter -= 1000;
                lastUpdateTime = System.currentTimeMillis();
                updateTimerDisplay(false, counter);
            }
            while (!table.setsToCheck.isEmpty()) {
                foundSet=checkSet();
                if (foundSet) {
                    updateTimerDisplay(true, env.config.turnTimeoutMillis);
                    counter = env.config.turnTimeoutMillis;
                    lastUpdateTime = System.currentTimeMillis();
                }
                else if (curr - lastUpdateTime >= 950) { //a second passed
                    counter -= 1000;
                    lastUpdateTime = System.currentTimeMillis();
                    updateTimerDisplay(false, counter);
                }
            }
        }
    }

    /**
     * Sleep for a fixed amount of time or until the thread is awakened for some purpose.
     */
    private void sleepUntilWokenOrTimeout() throws InterruptedException {
        synchronized (table.setsToCheck) {
            while (table.setsToCheck.isEmpty() && System.currentTimeMillis() - lastUpdateTime < 950)
                table.setsToCheck.wait(950);
        }
    }

    private void sleepUntilWokenOrTimeout2() throws InterruptedException {
        synchronized (table.setsToCheck) {
            while (table.setsToCheck.isEmpty() && System.currentTimeMillis() - lastUpdateTime < 950)
                table.setsToCheck.wait(950);
        }
    }

    boolean checkSet() {

            //System.out.println("dealer entered checkSet"); //debug
            boolean isSet;
            int[][] setToCheck;

            synchronized (table.setsToCheck) {
                setToCheck = table.setsToCheck.remove(); //setToCheck supposed to be thread safe
                //}

                int player = setToCheck[0][0];
                Player playerToCheck = getPlayer(player);
                int[] cardsToCheck = setToCheck[1];

                isSet = this.env.util.testSet(cardsToCheck);
                //System.out.println("isSet-answer:" + isSet); //debug

                if (isSet) {
                    // System.out.println("entered isSet in checkSet in Dealer"); //debug
                    for (int i = 0; i < env.config.featureSize; i++) {
                    /*System.out.println("cardsToCheck[i]" + cardsToCheck[i]); //debug
                    System.out.println("table.cardToSlot[cardsToCheck[i]]" + table.cardToSlot[cardsToCheck[i]]); *///debug
                        int slotToRemoveFrom = table.cardToSlot[cardsToCheck[i]];
                        this.env.ui.removeTokens(slotToRemoveFrom); //visually removes tokens from the slot
                        for (Player p : players) { //removes relevant tokens from tokens lists in players
                            p.tokens.remove(Integer.valueOf(slotToRemoveFrom));
                        }
                        env.logger.info("Dealer found set: "+cardsToCheck+" by Player "+playerToCheck);
                    }
                    playerToCheck.changeFreezedTopoint();
                    playerToCheck.tokens.removeAll(playerToCheck.tokens); //empty player's tokens
                    removeCardsFromTable(cardsToCheck);
                    placeCardsOnTable();
                    removeAfterSet(cardsToCheck);
//                updateTimerDisplay(true, System.currentTimeMillis());
                } else {
                    //System.out.println("entered !isSet - Let's penalize"); //debug
                    playerToCheck.changeFreezedToPenalty();
                    env.logger.info("Dealer found wrong set");
                }
                env.logger.info("Dealer finished checking set");
                playerToCheck.flag = true;
                playerToCheck.WakeUpPlayer();
            }
            return isSet;
    }

    void checkSetDemo() {
        while (!table.setsToCheck.isEmpty()) {
            //System.out.println("dealer entered checkSet"); //debug
            boolean isSet;
            int[][] setToCheck;

            synchronized (table.setsToCheck) {
                setToCheck = table.setsToCheck.remove(); //setToCheck supposed to be thread safe
                //}

                int player = setToCheck[0][0];
                Player playerToCheck = getPlayer(player);
                int[] cardsToCheck = setToCheck[1];

                isSet = this.env.util.testSet(cardsToCheck);
                //System.out.println("isSet-answer:" + isSet); //debug

                if (isSet) {
//                    for (int i = 0; i < env.config.featureSize; i++) {
//                        int slotToRemoveFrom = table.cardToSlot[cardsToCheck[i]];
//                        this.env.ui.removeTokens(slotToRemoveFrom); //visually removes tokens from the slot
//                        for (Player p : players) { //removes relevant tokens from tokens lists in players
//                            p.tokens.remove(Integer.valueOf(slotToRemoveFrom));
//                        }
//                        env.logger.info("Dealer found set: "+cardsToCheck+" by Player "+playerToCheck);
//                    }
                    playerToCheck.changeFreezedTopoint();
//                    playerToCheck.tokens.removeAll(playerToCheck.tokens); //empty player's tokens
//                    removeCardsFromTable(cardsToCheck);
//                    placeCardsOnTable();
//                    removeAfterSet(cardsToCheck);
                } else {
                    playerToCheck.changeFreezedToPenalty();
                    env.logger.info("Dealer found wrong set");
                }
                env.logger.info("Dealer finished checking set");
                playerToCheck.flag = true;
                playerToCheck.WakeUpPlayer();
            }
        }
    }

    private void printInfoAboutSets() {
        List<int[]> l = env.util.findSets(deck, 1000);
        System.out.println("num of sets: " + l.size());
        System.out.println("Size of deck: " + deck.size());
        //System.out.println("Cards of first Set: " + l.get(0)[0] + " ," + l.get(0)[1] + " ," + l.get(0)[2]);
    }


    void removeAfterSet(int[] cardsOfSet) {
        for (int[][] arr : table.setsToCheck) {
            int[] crdsToChck = arr[1];
            boolean stop = false;
            for (int i = 0; i < env.config.featureSize && !stop; i++) {
                if (crdsToChck[i] == cardsOfSet[0] || crdsToChck[i] == cardsOfSet[1] || crdsToChck[i] == cardsOfSet[2]) {
                    table.setsToCheck.remove(arr);
                    stop = true;
                }
            }
        }
    }

    /**
     * Called when the game should be terminated due to an external event.
     */
    public synchronized void terminate() {
        env.logger.info("thread- "+Thread.currentThread()+" entered terminate (Dealer)");
        terminate = true;
    }

    /**
     * Check if the game should be terminated or the game end conditions are met.
     *
     * @return true iff the game should be finished.
     */
    private boolean shouldFinish() {
        env.logger.info("Entered shouldFinish, zero for no sets and 1 for there are sets:"+(env.util.findSets(deck, 1)).size());
        return terminate || (env.util.findSets(deck, 1)).size() == 0;
    }

    /**
     * Checks if any cards should be removed from the table and returns them to the deck.
     */
    private void removeCardsFromTable(int[] cardsOfSet) {
        synchronized (table) {
            table.allCardsOnTable = false;
            for (int i = 0; i < env.config.featureSize; i++) {
//                System.out.println(cardsOfSet[i]); //debug
//                System.out.println(table.cardToSlot[cardsOfSet[i]]); //debug
                int slotToRemoveFrom = table.cardToSlot[cardsOfSet[i]];
                table.removeCard(slotToRemoveFrom);
            }
        }
    }

    /**
     * Check if any cards can be removed from the deck and placed on the table.
     */
    public void placeCardsOnTable() {
        synchronized (table) {
            table.allCardsOnTable = false;
            if (table.countCards() < env.config.tableSize) {
                Collections.shuffle(deck);
                for (int i = 0; i < env.config.tableSize; i++) {
                    if (table.slotToCard[i] == null && deck.size() > 0) { // && i < deck.size()
                        int card = deck.get(0);
                        table.placeCard(card, i);
                        deck.remove(0);
                    }
                }
            }
            table.allCardsOnTable = true;
        }
    }

    /**
     * Reset and/or update the countdown and the countdown display.
     */
    private void updateTimerDisplay(boolean reset, long curr) {
        if (reset)
            reset();
        if (curr < 0)
            this.env.ui.setCountdown(0, false);
        else if (curr > env.config.turnTimeoutWarningMillis)
            this.env.ui.setCountdown(curr, false);
        else
            this.env.ui.setCountdown(curr, true);
    }

    private void reset() {
        reshuffleTime = System.currentTimeMillis();
    }

    /**
     * Returns all the cards from the table to the deck.
     */
    private void removeAllCardsFromTable() {
        synchronized (table) {
            table.allCardsOnTable = false;
            for (int slotInd = 0; slotInd < env.config.tableSize; slotInd++) {
                if (table.slotToCard[slotInd] != null) {
                    int cardToRemove = table.slotToCard[slotInd];
                    deck.add(cardToRemove);
                    for (Player p : players) { //was out of the {}
                        table.removeToken(p.id, slotInd);
                    }
                    table.removeCard(slotInd);
                }
            }
            removePlayersTokens();
            removePlayersPresses();
        }
    }

    private void removePlayersTokens() {
        for (Player p : players)
            p.emptyTokens();
    }

    private void removePlayersPresses() {
        for (Player p : players)
            p.emptyPressesQueue();
    }

    /**
     * Check who is/are the winner/s and displays them.
     */
    protected int[] announceWinners() {
        int maxScore = -1;              //finding the Maximum score
        for (Player player : players) {
            if (player.score() > maxScore)
                maxScore = player.score();
        }
        int PlayersSize = 0;
        for (Player player : players) {   //finding number of winners
            if (player.score() == maxScore)
                PlayersSize++;
        }
        int[] winners = new int[PlayersSize];

        int ind = 0;
        for (Player player : players) {     //creating winners array
            if (player.score() == maxScore) {
                winners[ind] = player.id;
                ind++;
            }
        }
        this.env.ui.announceWinner(winners);
        return winners;
    }

}

