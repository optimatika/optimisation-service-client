package se.optimatika.optimisation.service.client;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Covers aborting a solve, and what the server does when more models are submitted than it can solve at once.
 * <p>
 * Much of this is about timing rather than about solutions, so it mostly works at the {@link OptClientV1}
 * layer where the queue key is visible. The exception is cancelling, which is {@link OptModel}'s own API.
 * <p>
 * Two properties of the server decide what these tests can assume:
 * <ul>
 * <li>It runs {@link #NB_CONCURRENT_SOLVES} solves at once and queues the rest. That number follows from the
 * machine it is on, so it is read from the server's environment rather than assumed – see
 * {@link #readCapacity()}. Nothing here hard-codes it, and the one test that needs more than one worker says
 * so and skips when there is only one.</li>
 * <li>The server stops a solve at its time limits: without a licence key after 60 seconds, or after 30 once it
 * has a feasible solution; licensed, by default after 24 hours, or one hour once it has one. So no model stays
 * unsolved forever here, and every window a test waits in is kept well under a minute, so that a model meant
 * to still be running actually is.</li>
 * </ul>
 * Each test starts by waiting for the server to be idle, and ends by aborting everything it submitted, so
 * that "every worker is busy" is something the test set up rather than something it inherited. Against the
 * shared instance it skips itself: that runs on several instances and serves other users, so there is no one
 * server to have to itself. Run it against a server of your own, with {@code SERVICE_HOST} – a container
 * started with {@code docker run --rm -p 8080:8080 ghcr.io/optimatika/optimisation-service}, say, and
 * {@code SERVICE_HOST=http://localhost:8080}. Without a licence key that server solves one model at a time, and
 * the one test that needs more skips itself.
 * <p>
 * <b>One run at a time.</b> These tests work by filling the server up, so two runs against the same server
 * fight: each one's models occupy workers the other is counting, and neither ever finds the server idle.
 * Running this suite while someone else is running it produces failures that say nothing about the code –
 * typically an idle or capacity wait that times out, or a count that includes a model this run never
 * submitted.
 * <p>
 * Where a test needs to know how busy the server is, it reads the running and queued counts from the server's
 * environment. The tempting alternative – offer the server a model that solves in milliseconds and see
 * whether it comes back – does not mean what it looks like it means: on a server with few CPUs a hard MILP can
 * starve everything else, so a trivial model can sit unsolved with a worker free.
 * {@link #awaitCapacity()} is still used where the occupying solves are known to have landed, but only ever
 * to confirm, never to establish.
 */
@Tag("integration")
public class AbortAndCapacityTest extends AbstractTest {

    /**
     * How long a quick model is given to find a worker before the server is taken to be at capacity. It
     * solves in milliseconds once it has one, so this is generous many times over.
     */
    private static final long CAPACITY_PROBE_MILLIS = 5_000L;

    /** How long {@link #awaitCapacity()} keeps probing before giving up on the server ever filling up. */
    private static final long CAPACITY_TIMEOUT_MILLIS = 45_000L;

    /** Objective value of flugpl.mps. */
    private static final BigDecimal FLUGPL_OPTIMUM = new BigDecimal("1201500");

    /** How long {@link #awaitIdle()} waits for the server to go idle between tests. */
    private static final long IDLE_TIMEOUT_MILLIS = 30_000L;

    /**
     * How many solves the server runs at once, read once by {@link #readCapacity()} before any test runs.
     * Constant for the rest of the run.
     */
    private static int NB_CONCURRENT_SOLVES;

    private static final long POLL_INTERVAL_MILLIS = 250L;

    /** Objective value of {@link #quickModel(OptClientV1)}. */
    private static final BigDecimal QUICK_OPTIMUM = new BigDecimal("20");

    /** How long a model started through {@link OptModel} is given to reach the server. */
    private static final long SUBMISSION_MILLIS = 15_000L;

    /**
     * Reads how many solves the server runs at once from its environment.
     */
    @BeforeAll
    public static void readCapacity() {

        String environment = new OptClientV1(URI.create(HOST)).getServiceEnvironment();

        // Several instances, each with its own workers and queue, and other people's solves on them: there is
        // no one server to have to itself.
        Assumptions.assumeFalse(environment.contains("Polar shared instance"),
                "Not against the shared instance – it runs on several instances and serves other users");

        NB_CONCURRENT_SOLVES = AbortAndCapacityTest.capacity(environment, "workers");

        Assertions.assertTrue(NB_CONCURRENT_SOLVES >= 1, "The server runs nothing at all, so there is no capacity to test against: " + environment);

        System.out.println("Server runs " + NB_CONCURRENT_SOLVES + " solve(s) at a time");
    }

    /**
     * Polls until the solve is no longer {@code PENDING}, and returns the response it settled on. A timeout
     * is returned as the last {@code PENDING} response – the caller asserts on what it expected instead, so
     * this is used both to wait for a solve and to establish that one is not moving.
     */
    private static Map<String, Object> awaitSettled(final OptClientV1 client, final String key, final long timeoutMillis) throws InterruptedException {

        long deadline = System.currentTimeMillis() + timeoutMillis;

        Map<String, Object> response = client.pollResultParsed(key);

        while ("PENDING".equals(response.get(OptClientV1.STATUS)) && System.currentTimeMillis() < deadline) {
            Thread.sleep(POLL_INTERVAL_MILLIS);
            response = client.pollResultParsed(key);
        }

        return response;
    }

    private static byte[] bytes(final OptModel model) {

        try (InputStream is = model.exportModel("EBM")) {
            return is.readAllBytes();
        } catch (IOException cause) {
            throw new RuntimeException(cause);
        }
    }

    /**
     * One count from the {@code capacity} section of the server's environment.
     */
    private static int capacity(final String environment, final String field) {

        Matcher matcher = Pattern.compile("\"capacity\":\\{[^}]*\"" + field + "\":(\\d+)").matcher(environment);

        Assertions.assertTrue(matcher.find(), "No capacity." + field + " in the environment – is the server older than this test? " + environment);

        return Integer.parseInt(matcher.group(1));
    }

    private static boolean isDone(final OptClientV1 client, final String key) {
        return "DONE".equals(client.pollResultParsed(key).get(OptClientV1.STATUS));
    }

    /**
     * Market split: equality constraints over binary variables with a right-hand side of half the coefficient
     * sum. Infeasible, and famously hard to prove so – branch and bound has to exhaust the tree. Unlike
     * {@code ej.mps} it never finds a feasible solution, so the shorter limit, for a solve that has one, never
     * applies to it, and it runs until the server's abort limit – 60 seconds without a licence key. That
     * longer window is what the capacity tests need.
     * <p>
     * At 60 variables it is also above the size at which a licensed server hands a MILP to a native solver,
     * HiGHS or SCIP. There these tests abort a native solver rather than the built-in one.
     */
    private static OptModel newHardModel(final OptClientV1 client) {

        OptModel model = client.newModel();

        OptVariable[] variables = new OptVariable[60];
        for (int j = 0; j < variables.length; j++) {
            variables[j] = model.newBinaryVariable("x" + j);
            model.objective().set(variables[j], 1);
        }

        Random random = new Random(1234L);

        for (int i = 0; i < 6; i++) {

            OptConstraint constraint = model.newConstraint("split" + i);
            int sum = 0;

            for (OptVariable variable : variables) {
                int coefficient = random.nextInt(100);
                constraint.set(variable, coefficient);
                sum += coefficient;
            }

            constraint.level(sum / 2);
        }

        return model;
    }

    /**
     * Two variables and one constraint. The built-in solver takes it and is done in milliseconds, which is
     * what makes it useful for telling "no worker was free" apart from "nothing was solved".
     * <p>
     * Minimised, like everything else these tests submit, giving {@code A = 2, B = 0} and
     * {@link #QUICK_OPTIMUM}.
     */
    private static byte[] quickModel(final OptClientV1 client) {

        OptModel model = client.newModel();

        OptVariable varA = model.newRealVariable("A").lower(0);
        OptVariable varB = model.newRealVariable("B").lower(0);

        model.newConstraint("UM2").set(varA, 1).set(varB, 1).level(2);
        model.objective().set(varA, 10).set(varB, 20);

        return AbortAndCapacityTest.bytes(model);
    }

    private static byte[] readResource(final String path) throws IOException {
        try (InputStream is = ClassLoader.getSystemResourceAsStream(path)) {
            Assertions.assertNotNull(is, path + " is not on the test classpath");
            return is.readAllBytes();
        }
    }

    private OptClientV1 myClient;

    /** Started through {@link OptModel}, whose queue keys are known only to the {@link Future}. */
    private final List<Future<OptResult>> mySlowSolves = new ArrayList<>();

    /** The queue key of everything this test submitted through {@link OptClientV1}. */
    private final List<String> mySubmitted = new ArrayList<>();

    /**
     * Aborts everything this test submitted, finished or not, so that the next one starts on an idle server.
     */
    @AfterEach
    public void abortOwnSolves() {

        for (String key : mySubmitted) {
            myClient.abortParsed(key);
        }

        for (Future<OptResult> solve : mySlowSolves) {
            String key = ((OptModel.RemoteSolve) solve).key();
            if (key != null) {
                myClient.abortParsed(key);
            }
        }
    }

    /**
     * Leaves the server idle, not merely told to be idle. Aborting is not instantaneous – a solver has to
     * notice the interrupt and its worker has to come back for the next problem – so the previous test's
     * solve can still be winding down, and this test would then count it as its own.
     */
    @BeforeEach
    public void awaitIdle() throws Exception {

        myClient = new OptClientV1(URI.create(HOST));

        long deadline = System.currentTimeMillis() + IDLE_TIMEOUT_MILLIS;

        String environment = myClient.getServiceEnvironment();

        while (System.currentTimeMillis() < deadline) {

            if (AbortAndCapacityTest.capacity(environment, "running") == 0 && AbortAndCapacityTest.capacity(environment, "queued") == 0) {
                return;
            }

            Thread.sleep(POLL_INTERVAL_MILLIS);

            environment = myClient.getServiceEnvironment();
        }

        Assertions.fail("The server never went idle, so this test cannot tell its own solves from what it inherited."
                + " Something else is solving on it – another run of this suite, or one stopped part way, whose solves"
                + " end at the server's own time limit: " + environment);
    }

    /**
     * Aborting a solve that has already finished leaves it alone – it stays {@code DONE} and keeps its
     * result, rather than having it wiped.
     */
    @Test
    public void testAbortFinishedSolveKeepsItsResult() throws Exception {

        String key = this.submit(AbortAndCapacityTest.readResource("optimisation/MIPLIB/flugpl.mps"), "MPS");

        Map<String, Object> settled = AbortAndCapacityTest.awaitSettled(myClient, key, 60_000L);
        Assertions.assertEquals("DONE", settled.get(OptClientV1.STATUS));
        Assertions.assertNotNull(settled.get(OptClientV1.RESULT));

        Map<String, Object> aborted = myClient.abortParsed(key);

        Assertions.assertEquals("DONE", aborted.get(OptClientV1.STATUS));

        OptResult result = (OptResult) aborted.get(OptClientV1.RESULT);
        Assertions.assertNotNull(result, "Aborting a finished solve must not discard its result");
        Assertions.assertEquals(0, FLUGPL_OPTIMUM.compareTo(result.getValue()));
    }

    /**
     * Aborting one solve disturbs nothing else. Every worker is put on a model that runs until the server's
     * time limit, and a quick one is left waiting in the queue. Aborting one of the running solves must leave
     * the other running and the queued one intact – and the worker it frees must be fit for use, which the
     * queued model then being solved correctly proves.
     */
    @Test
    public void testAbortOneSolveLeavesTheOthersAlone() throws Exception {

        List<String> running = this.occupyEveryWorker();

        // Submitted in this order because the queue is FIFO: the freed worker takes the quick model, and the
        // bystander is still waiting behind it at the end. With only one worker any other order would leave
        // the quick model stuck behind the bystander, which runs for minutes.
        String queued = this.submit(AbortAndCapacityTest.quickModel(myClient));
        String bystander = this.submit(AbortAndCapacityTest.bytes(AbortAndCapacityTest.newHardModel(myClient)));

        String victim = running.get(0);

        Assertions.assertEquals("DONE", myClient.abortParsed(victim).get(OptClientV1.STATUS));

        Map<String, Object> settled = AbortAndCapacityTest.awaitSettled(myClient, queued, 30_000L);
        Assertions.assertEquals("DONE", settled.get(OptClientV1.STATUS), "The queued solve never got the freed worker");

        OptResult result = (OptResult) settled.get(OptClientV1.RESULT);
        Assertions.assertNotNull(result, "The queued solve was disturbed by the abort");
        Assertions.assertEquals(0, QUICK_OPTIMUM.compareTo(result.getValue()));

        Assertions.assertFalse(AbortAndCapacityTest.isDone(myClient, bystander), "A queued solve was abandoned by an abort aimed at another one");

        for (int i = 1; i < running.size(); i++) {
            Assertions.assertFalse(AbortAndCapacityTest.isDone(myClient, running.get(i)), "Aborting one solve stopped another running one too");
        }
    }

    /**
     * A solve that would run far longer than the caller is willing to wait can be stopped, and stops
     * promptly. It ends {@code DONE} with no result – whatever partial solution the
     * solver had is discarded, so a poll finds nothing to read.
     * <p>
     * Uses {@code ej.mps}: three integer variables and one equality constraint, which branch and bound takes
     * effectively forever on. Small enough that the built-in solver handles it, so this is the built-in
     * solver's interrupt handling specifically.
     */
    @Test
    public void testAbortOngoingSolve() throws Exception {

        String key = this.submit(AbortAndCapacityTest.readResource("optimisation/MIPLIB/ej.mps"), "MPS");

        Map<String, Object> whileRunning = AbortAndCapacityTest.awaitSettled(myClient, key, CAPACITY_PROBE_MILLIS);
        Assertions.assertEquals("PENDING", whileRunning.get(OptClientV1.STATUS), "ej.mps should not have been solved this quickly");

        long started = System.currentTimeMillis();
        Map<String, Object> aborted = myClient.abortParsed(key);
        long took = System.currentTimeMillis() - started;

        Assertions.assertEquals("DONE", aborted.get(OptClientV1.STATUS), "The abort should stop the solve outright");
        Assertions.assertNull(aborted.get(OptClientV1.RESULT), "An aborted solve must not publish a result");
        Assertions.assertTrue(took < 10_000L, "The abort should return promptly, took " + took + "ms");

        Map<String, Object> polled = myClient.pollResultParsed(key);
        Assertions.assertEquals("DONE", polled.get(OptClientV1.STATUS), "A client polling an aborted solve should stop polling");
        Assertions.assertNull(polled.get(OptClientV1.RESULT));

        // Aborting again is a no-op rather than an error.
        Assertions.assertEquals("DONE", myClient.abortParsed(key).get(OptClientV1.STATUS));
    }

    /**
     * Aborting a key the server has never seen is reported as such rather than silently accepted. Keys whose
     * results have expired look the same. Polling one is refused the same way, with an {@code IOException}.
     */
    @Test
    public void testAbortUnknownKey() throws Exception {

        Assertions.assertNull(myClient.abort("no-such-key-at-all"));
        Assertions.assertTrue(myClient.abortParsed("no-such-key-at-all").isEmpty());
    }

    /**
     * Cancelling with {@code mayInterruptIfRunning} set has to reach the server, not just stop the client
     * polling. {@link OptModel} does not expose the queue key, so whether the server stopped is shown by the
     * worker it frees: a model queued behind the cancelled one gets solved.
     * <p>
     * A solve that never reached the server in the first place would free nothing either, and so would pass
     * this. What rules that out is {@link #testCancelWithoutInterruptLeavesTheServerSolving()}: same setup,
     * same wait, opposite expectation, so submissions failing to land turns that test red rather than turning
     * this one falsely green.
     */
    @Test
    public void testCancelAbortsRemoteSolve() throws Exception {

        List<String> others = this.occupyAllButOneWorker();

        Future<OptResult> cancelled = this.startSlowSolve();

        this.awaitSubmitted();

        String queued = this.submit(AbortAndCapacityTest.quickModel(myClient));

        Assertions.assertTrue(cancelled.cancel(true));
        Assertions.assertTrue(cancelled.isCancelled());

        Map<String, Object> settled = AbortAndCapacityTest.awaitSettled(myClient, queued, 30_000L);
        Assertions.assertEquals("DONE", settled.get(OptClientV1.STATUS), "Cancelling did not free the worker, so the solve was not aborted");

        OptResult result = (OptResult) settled.get(OptClientV1.RESULT);
        Assertions.assertNotNull(result);
        Assertions.assertEquals(0, QUICK_OPTIMUM.compareTo(result.getValue()));

        Assertions.assertThrows(CancellationException.class, () -> cancelled.get(1, TimeUnit.SECONDS));

        for (String other : others) {
            Assertions.assertFalse(AbortAndCapacityTest.isDone(myClient, other), "Cancelling one solve stopped another one too");
        }
    }

    /**
     * Cancelling before the server has handed back a key still has to leave nothing running, rather than a
     * solve that nothing can name. Two outcomes are both correct and this does not distinguish them: the
     * cancellation can beat the submission, in which case the model is never sent at all, or it can arrive
     * afterwards and be sent as an abort once the key does. What is asserted is what they have in common –
     * the worker is free afterwards.
     * <p>
     * Freeness is established by giving the server a model that solves in milliseconds and requiring it to
     * come back, rather than by reading a count. The environment reports how many workers are holding a
     * problem, which is not the same as how many solves have escaped an abort: an aborted solve stays counted
     * until its solver notices the interrupt and the worker exits, and on a native solver that is not
     * immediate.
     */
    @Test
    public void testCancelBeforeSubmissionCompletes() throws Exception {

        this.occupyAllButOneWorker();

        Future<OptResult> cancelled = this.startSlowSolve();

        // No wait at all – the model is almost certainly still in flight, so the abort has no key to send yet
        // and has to wait for one.
        Assertions.assertTrue(cancelled.cancel(true));

        // Long enough for the submission to have completed and the abort to have followed it, so that the
        // probe below is asking about a settled server rather than racing the same flight.
        this.awaitSubmitted();

        Assertions.assertTrue(cancelled.isCancelled());
        Assertions.assertThrows(CancellationException.class, () -> cancelled.get(1, TimeUnit.SECONDS));

        String probe = this.submit(AbortAndCapacityTest.quickModel(myClient));

        Map<String, Object> settled = AbortAndCapacityTest.awaitSettled(myClient, probe, 30_000L);
        Assertions.assertEquals("DONE", settled.get(OptClientV1.STATUS), "A cancelled solve was left holding the worker");

        OptResult result = (OptResult) settled.get(OptClientV1.RESULT);
        Assertions.assertNotNull(result);
        Assertions.assertEquals(0, QUICK_OPTIMUM.compareTo(result.getValue()));
    }

    /**
     * {@code cancel(false)} is the other half of the contract: abandon the local side only, and leave a solve
     * that has already started to the server. Proved the same way as {@link #testCancelAbortsRemoteSolve()},
     * by what does not happen – the worker stays occupied, so the queued model stays queued.
     * <p>
     * Together with that test this is what shows the abort is doing the work: the two set up the same thing
     * and differ only in the flag.
     */
    @Test
    public void testCancelWithoutInterruptLeavesTheServerSolving() throws Exception {

        this.occupyAllButOneWorker();

        Future<OptResult> cancelled = this.startSlowSolve();

        this.awaitSubmitted();

        String queued = this.submit(AbortAndCapacityTest.quickModel(myClient));

        Assertions.assertTrue(cancelled.cancel(false));
        Assertions.assertTrue(cancelled.isCancelled());

        Assertions.assertThrows(CancellationException.class, () -> cancelled.get(1, TimeUnit.SECONDS));

        Map<String, Object> settled = AbortAndCapacityTest.awaitSettled(myClient, queued, CAPACITY_PROBE_MILLIS);

        Assertions.assertEquals("PENDING", settled.get(OptClientV1.STATUS), "cancel(false) should not have freed the worker, but the queued solve got one"
                + " - either the abort was sent when it should not have been, or the cancelled model never reached the server");
    }

    /**
     * The counts the server's environment reports account for everything it is holding, split at the capacity
     * limit: {@link #NB_CONCURRENT_SOLVES} running and the rest still queued.
     * <p>
     * The extra models are submitted only once every worker is already occupied until the server's time limit,
     * so that none of them can be on its way from the queue to a worker when the counts are read.
     */
    @Test
    public void testEnvironmentCountsRunningAndQueued() throws Exception {

        this.occupyEveryWorker();

        int nbQueued = 3;
        for (int i = 0; i < nbQueued; i++) {
            this.submit(AbortAndCapacityTest.bytes(AbortAndCapacityTest.newHardModel(myClient)));
        }

        String environment = myClient.getServiceEnvironment();

        Assertions.assertEquals(NB_CONCURRENT_SOLVES, AbortAndCapacityTest.capacity(environment, "running"),
                "Should have been running at its limit: " + environment);
        Assertions.assertEquals(nbQueued, AbortAndCapacityTest.capacity(environment, "queued"),
                "The rest should have been waiting in the queue: " + environment);
    }

    /**
     * One very slow solve does not stop another model being submitted, and a quick model submitted while it
     * runs finishes on its own schedule rather than waiting for the slow one.
     * <p>
     * Overtaking needs a second worker to overtake on. Where the server runs one solve at a time the quick
     * model can only wait its turn, and that is what {@link #testSubmissionBeyondCapacityWaitsForIt()} covers
     * instead – so this skips rather than asserting something the configuration makes impossible.
     */
    @Test
    public void testQuickSolveOvertakesSlowOne() throws Exception {

        Assumptions.assumeTrue(NB_CONCURRENT_SOLVES > 1, "Overtaking needs more than one worker, and this server runs " + NB_CONCURRENT_SOLVES);

        String slowKey = this.submit(AbortAndCapacityTest.readResource("optimisation/MIPLIB/ej.mps"), "MPS");

        String quickKey = this.submit(AbortAndCapacityTest.readResource("optimisation/MIPLIB/flugpl.mps"), "MPS");
        Assertions.assertNotNull(quickKey, "Submitting while a slow solve runs should still be accepted");

        Map<String, Object> settled = AbortAndCapacityTest.awaitSettled(myClient, quickKey, 20_000L);

        Assertions.assertEquals("DONE", settled.get(OptClientV1.STATUS), "The quick model waited for the slow one");

        OptResult result = (OptResult) settled.get(OptClientV1.RESULT);
        Assertions.assertNotNull(result);
        Assertions.assertTrue(result.isOptimal());
        Assertions.assertEquals(0, FLUGPL_OPTIMUM.compareTo(result.getValue()));

        Assertions.assertFalse(AbortAndCapacityTest.isDone(myClient, slowKey), "The slow model should still be running");
    }

    /**
     * With every worker already taken, a further submission is accepted and given a key, but no worker takes
     * it – so it produces no result for as long as the others hold the workers.
     * <p>
     * The submission is not rejected. The server only refuses once the queue itself is full, which is a fixed
     * depth per worker, and answers that with HTTP 429 rather than a key.
     * <p>
     * That it stays pending is not on its own proof of anything – a broken submission would look identical.
     * So the running models are then aborted, and the same key has to come back with the right answer.
     */
    @Test
    public void testSubmissionBeyondCapacityWaitsForIt() throws Exception {

        List<String> running = this.occupyEveryWorker();

        String key = this.submit(AbortAndCapacityTest.quickModel(myClient));
        Assertions.assertNotNull(key, "The submission should be accepted and queued, not rejected");

        Map<String, Object> whileFull = AbortAndCapacityTest.awaitSettled(myClient, key, CAPACITY_PROBE_MILLIS);
        Assertions.assertEquals("PENDING", whileFull.get(OptClientV1.STATUS),
                "One solve too many ran, even though the server takes only " + NB_CONCURRENT_SOLVES + " at a time");
        Assertions.assertNull(whileFull.get(OptClientV1.RESULT));

        for (String hog : running) {
            myClient.abortParsed(hog);
        }

        Map<String, Object> settled = AbortAndCapacityTest.awaitSettled(myClient, key, 30_000L);
        Assertions.assertEquals("DONE", settled.get(OptClientV1.STATUS), "It was never picked up once capacity freed");

        OptResult result = (OptResult) settled.get(OptClientV1.RESULT);
        Assertions.assertNotNull(result, "Waiting for capacity must not cost the solve its result");
        Assertions.assertEquals(0, QUICK_OPTIMUM.compareTo(result.getValue()));
    }

    /**
     * Blocks until the server demonstrably has no worker to spare: a model that solves in milliseconds is
     * given {@link #CAPACITY_PROBE_MILLIS} to be solved, and capacity is reached once one is not. Each probe
     * is taken off the queue again afterwards, so none of them stands in the way of what the test does next.
     * <p>
     * Only ever called with occupying solves that have already been accepted by the server, because a probe
     * that stays {@code PENDING} does not prove on its own that no worker is free – on a server with few CPUs
     * a running hard MILP can starve a trivial one badly enough to look the same. With the occupying solves known to be
     * there, this is confirmation that they are being worked on rather than merely accepted.
     */
    private void awaitCapacity() throws Exception {

        long deadline = System.currentTimeMillis() + CAPACITY_TIMEOUT_MILLIS;

        while (System.currentTimeMillis() < deadline) {

            String probe = this.submit(AbortAndCapacityTest.quickModel(myClient));

            Map<String, Object> settled = AbortAndCapacityTest.awaitSettled(myClient, probe, CAPACITY_PROBE_MILLIS);

            myClient.abortParsed(probe);

            if ("PENDING".equals(settled.get(OptClientV1.STATUS))) {
                return;
            }
        }

        Assertions.fail("The server never reached capacity - a worker stayed free for " + CAPACITY_TIMEOUT_MILLIS + "ms");
    }

    /**
     * Waits long enough that a solve started with {@link #startSlowSolve()} has reached the server.
     * {@link OptModel#minimise()} returns before the model has left the client, and there is nothing to
     * observe in the meantime – the queue key it would be observed by is exactly what {@link OptModel} does
     * not expose. So this is a fixed wait, generous for a local serialisation and one HTTP POST, and the
     * tests that use it say so in their failure messages when it turns out not to have been enough.
     */
    private void awaitSubmitted() throws InterruptedException {
        Thread.sleep(SUBMISSION_MILLIS);
    }

    /**
     * Fills all but one worker with a model that runs until the server's own time limit, leaving exactly one
     * free for the caller to fill itself. Submitted through {@link OptClientV1}, so these have demonstrably
     * reached the server by the time this returns – which is what makes the one remaining worker the only
     * thing in doubt.
     * <p>
     * Returns an empty list where the server runs one solve at a time, which is the whole of it.
     *
     * @return the keys of the solves now occupying the other workers
     */
    private List<String> occupyAllButOneWorker() {

        List<String> retVal = new ArrayList<>(NB_CONCURRENT_SOLVES);

        for (int i = 1; i < NB_CONCURRENT_SOLVES; i++) {
            retVal.add(this.submit(AbortAndCapacityTest.bytes(AbortAndCapacityTest.newHardModel(myClient))));
        }

        return retVal;
    }

    /**
     * Fills every worker with a model that runs until the server's own time limit, and does not return until
     * that is observably the case.
     *
     * @return the keys of the solves now running, one per worker
     */
    private List<String> occupyEveryWorker() throws Exception {

        List<String> retVal = new ArrayList<>(NB_CONCURRENT_SOLVES);

        for (int i = 0; i < NB_CONCURRENT_SOLVES; i++) {
            retVal.add(this.submit(AbortAndCapacityTest.bytes(AbortAndCapacityTest.newHardModel(myClient))));
        }

        this.awaitCapacity();

        return retVal;
    }

    /**
     * Submits a model that runs until the server's own time limit, through {@link OptModel} rather than
     * through {@link OptClientV1}, so that the {@link Future} is the only handle on it – which is the point
     * of the tests that use this. Returns as soon as the solve is under way locally; the model itself is
     * still in flight.
     */
    private Future<OptResult> startSlowSolve() {

        Future<OptResult> retVal = AbortAndCapacityTest.newHardModel(myClient).minimise();

        mySlowSolves.add(retVal);

        return retVal;
    }

    private String submit(final byte[] model) {
        return this.submit(model, "EBM");
    }

    private String submit(final byte[] model, final String format) {

        String key = (String) myClient.putOnQueueParsed(model, format, false).get(OptClientV1.KEY);

        Assertions.assertNotNull(key, "The server did not accept the submission");

        mySubmitted.add(key);

        return key;
    }

}
