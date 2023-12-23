package bguspl.set.ex;

import bguspl.set.Env;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

/**
 * This class manages the players' threads and data
 *
 * @inv id >= 0
 * @inv score >= 0
 */
public class Player implements Runnable {

    /**
     * The game environment object.
     */
    private final Env env;

    /**
     * Game entities.
     */
    private final Table table;

    /**
     * The id of the player (starting from 0).
     */
    public final int id;

    /**
     * The thread representing the current player.
     */
    public Thread playerThread;

    /**
     * The thread of the AI (computer) player (an additional thread used to generate key presses).
     */
    private Thread aiThread;

    /**
     * True iff the player is human (not a computer player).
     */
    private final boolean human;

    /**
     * True iff game should be terminated due to an external event.
     */
    private volatile boolean terminate;

    /**
     * The current score of the player.
     */
    private int score;

//    private Dealer dealer;

    List<Integer> tokens; //list of size 3, each element is a slot
    private BlockingQueue<Integer> pressesQueue;

    public boolean flag = false;

    public volatile int freezed = 0;



    /**
     * The class constructor.
     *
     * @param env    - the environment object.
     * @param dealer - the dealer object.
     * @param table  - the table object.
     * @param id     - the id of the player.
     * @param human  - true iff the player is a human player (i.e. input is provided manually, via the keyboard).
     */
    public Player(Env env, Dealer dealer, Table table, int id, boolean human) {
        this.env = env;
//        this.dealer=dealer;
        this.table = table;
        this.id = id;
        this.human = human;
        tokens = new ArrayList<>();
        pressesQueue = new ArrayBlockingQueue<Integer>(env.config.featureSize);
    }

    //for tests only
    public Player(Env env, Table table, int id, boolean human, int score) {
        this.env = env;
        this.table = table;
        this.id = id;
        this.human = human;
        this.score=score;
        tokens = new ArrayList<>();
        pressesQueue = new ArrayBlockingQueue<Integer>(env.config.featureSize);
    }

    /**
     * The main player thread of each player starts here (main loop for the player thread).
     */
    @Override
    public void run() {
        playerThread = Thread.currentThread();
        env.logger.info("thread " + Thread.currentThread().getName() + " starting.");
        if (!human)
            createArtificialIntelligence();

        try {
            while (!terminate) {
                if (!pressesQueue.isEmpty()) {
                    int queueSlot = pressesQueue.peek();
                    pressesQueue.remove();
                    if (!tokens.contains(queueSlot) && tokens.size() < 3 && table.slotToCard[queueSlot] != null) {
                        this.table.placeToken(id, queueSlot);
                        tokens.add(queueSlot);
                        if (tokens.size() == env.config.featureSize) {
                            sendSetToCheck();
                        }
                    } else {
                        this.table.removeToken(id, queueSlot);
                        tokens.remove((Integer) queueSlot);
                    }

                }
            }
        } catch (InterruptedException e) {
        }

        if (!human) try {
            aiThread.join();
        } catch (InterruptedException ignored) {
        }
        env.logger.info("thread " + Thread.currentThread().getName() + " terminated.");
    }


    private void sendSetToCheck() throws InterruptedException {

        synchronized (table.setsToCheck) {
            env.logger.info("Thread " + Thread.currentThread().getName() + "locked table.setsToCheck, Player"+id);
            table.setsToCheck.add(createSetToCheck());
            table.setsToCheck.notifyAll();
        }

        synchronized (this) {
            while (!flag) {
                //System.out.println("Player" + id + " is waiting");
                this.wait();
            }
            //System.out.println("player woke up for point/penalty"); //debug
            env.logger.info("Player"+id+" woke up for point/penalty");
            flag = false;

            if (freezed == 1)
                penalty();

            if (freezed == 2)
                point();
        }
    }

    public synchronized void WakeUpPlayer() {
        this.notifyAll();
    }

    private int[][] createSetToCheck() {
        int[][] setToAdd;
        int[] cardsToCheck;
//        synchronized (table) {
            setToAdd = new int[2][];
            int[] playerArr = new int[1];
            playerArr[0] = this.id;
            setToAdd[0] = playerArr;
            cardsToCheck = new int[env.config.featureSize];
            int i = 0;
            for (Integer token : tokens) {
                if (table.slotToCard[token]==null) {
                    System.out.println();
                }
                cardsToCheck[i] = table.slotToCard[token];
                i++;
            }
//        }
        setToAdd[1] = cardsToCheck;
        return setToAdd;
    }

    /**
     * Creates an additional thread for an AI (computer) player. The main loop of this thread repeatedly generates
     * key presses. If the queue of key presses is full, the thread waits until it is not full.
     */
    private void createArtificialIntelligence() {
        // note: this is a very very smart AI (!)
        aiThread = new Thread(() -> {
            while (!terminate) {
                Random rand = new Random();
                int RandSlot = rand.nextInt(env.config.tableSize);
                keyPressed(RandSlot);
            }

            env.logger.info("thread " + Thread.currentThread().getName() + " terminated.");
        }, "computer-" + id);
        aiThread.start();
    }

    /**
     * Called when the game should be terminated due to an external event.
     */
    public synchronized void terminate() {
        env.logger.info("Player"+id+" entered terminate");
        terminate = true;
        env.logger.info("Player"+id+" changed terminate to TRUE");
        playerThread.interrupt();
    }

    /**
     * This method is called when a key is pressed.
     *
     * @param slot - the slot corresponding to the key pressed.
     */
    public void keyPressed(int slot) {
        boolean x;
        if (freezed == 0 && table.allCardsOnTable) {
            x = pressesQueue.offer(slot);
            //System.out.println("presses Queue: " + pressesQueue);
            //System.out.println(x);
        }
    }

    /**
     * Award a point to a player and perform other related actions.
     *
     * @post - the player's score is increased by 1.
     * @post - the player's score is updated in the ui.
     */
    public void point() {
        score++;
        //env.ui.setScore(id, score);
        for (long i = env.config.pointFreezeMillis / 1000; i > 0; i--) {
            this.env.ui.setFreeze(id, i * 1000);
            try {
                playerThread.sleep(900);
            } catch (InterruptedException e) {
            }
        }
        int ignored = table.countCards(); // this part is just for demonstration in the unit tests
        env.ui.setScore(id, score);
        this.env.ui.setFreeze(id, 0);
        freezed = 0;
    }

    /**
     * Penalize a player and perform other related actions.
     */
    public void penalty() {
        for (long i = env.config.penaltyFreezeMillis / 1000; i > 0; i--) {
            this.env.ui.setFreeze(id, i * 1000);
            try {
                playerThread.sleep(900);
            } catch (InterruptedException e) {
            }
        }
        this.env.ui.setFreeze(id, 0);
        freezed = 0;
       // System.out.println("finished Penalizing");
    }

    public int score() {
        return score;
    }

    public void emptyTokens() {
        tokens.removeAll(tokens);
    }

    public void emptyPressesQueue() {
        pressesQueue.removeAll(pressesQueue);
    }

    public synchronized void changeFreezedToPenalty() {
        freezed = 1;
    }

    public synchronized void changeFreezedTopoint() {
        freezed = 2;
    }

}
