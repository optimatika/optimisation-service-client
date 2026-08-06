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
 * <li>It runs {@link #NB_CONCURRENT_SOLVES} solves at once and queues the rest. That number is not exposed by
 * any endpoint and has already changed twice, so it is measured rather than assumed – see
 * {@link #measureCapacity()}. Nothing here hard-codes it, and the one test that needs more than one worker
 * says so and skips when there is only one.</li>
 * <li>The licence caps a single solve at 30 seconds once it has a feasible solution and 120 seconds
 * regardless, as {@link OptClientV1#getServiceEnvironment()} reports. So no model stays unsolved forever here,
 * and every window a test waits in has to be short enough that a model meant to still be running actually
 * is.</li>
 * </ul>
 * Each test starts and ends with {@link OptClientV1#abortAll()}, so that "every worker is busy" is something
 * the test set up rather than something it inherited. That purges other clients' solves too – this belongs
 * against a test server.
 * <p>
 * <b>One run at a time.</b> These tests work by filling the server up and by clearing it out, so two of them
 * against the same server fight: each one's {@link OptClientV1#abortAll()} throws away the models the other
 * is waiting on, and each one's models occupy workers the other is counting. Running this suite while someone
 * else is running it produces failures that say nothing about the code – typically a capacity wait that times
 * out, or a count that includes a model this run never submitted.
 * <p>
 * Where a test needs to know that the server is busy, it asks {@link OptClientV1#abortAll()}, which reports
 * the running and queued counts outright. The tempting alternative – offer the server a model that solves in
 * milliseconds and see whether it comes back – does not mean what it looks like it means: this container has
 * two vCPUs, and a hard MILP on one of them starves everything else, so a trivial model can sit unsolved with
 * a worker free. {@link #awaitCapacity()} is still used where the occupying solves are known to have landed,
 * but only ever to confirm, never to establish.
 */
@Tag("integration")
public class AbortAndCapacityTest {

    private static final String HOST = System.getenv("SERVICE_HOST") != null ? System.getenv("SERVICE_HOST")
            : "https://optimatika-boot-services-969062758986.europe-north1.run.app";

    /**
     * How many solves the server runs at once, measured once by {@link #measureCapacity()} before any test
     * runs. Constant for the rest of the run.
     * <p>
     * {@code OptimisationServer} derives it from the machine it is on, and nothing serves it over HTTP, so
     * asking the server to run more solves than it can and counting how many it took is the only way to find
     * out.
     */
    private static int NB_CONCURRENT_SOLVES;

    /** How many models {@link #measureCapacity()} submits. More than any plausible worker count. */
    private static final int CAPACITY_MEASUREMENT_SUBMISSIONS = 5;

    /** Objective value of flugpl.mps. */
    private static final BigDecimal FLUGPL_OPTIMUM = new BigDecimal("1201500");

    /** Objective value of {@link #quickModel(OptClientV1)}. */
    private static final BigDecimal QUICK_OPTIMUM = new BigDecimal("20");

    /**
     * How long a quick model is given to find a worker before the server is taken to be at capacity. It
     * solves in milliseconds once it has one, so this is generous many times over.
     */
    private static final long CAPACITY_PROBE_MILLIS = 5_000L;

    /** How long {@link #awaitCapacity()} keeps probing before giving up on the server ever filling up. */
    private static final long CAPACITY_TIMEOUT_MILLIS = 45_000L;

    /** How long a model started through {@link OptModel} is given to reach the server. */
    private static final long SUBMISSION_MILLIS = 15_000L;

    private static final long POLL_INTERVAL_MILLIS = 250L;

    /** How long {@link #clearServer()} waits for the server to actually go idle between tests. */
    private static final long IDLE_TIMEOUT_MILLIS = 30_000L;

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

    private static boolean isDone(final OptClientV1 client, final String key) {
        return "DONE".equals(client.pollResultParsed(key).get(OptClientV1.STATUS));
    }

    /**
     * Market split: equality constraints over binary variables with a right-hand side of half the coefficient
     * sum. Infeasible, and famously hard to prove so – branch and bound has to exhaust the tree. Unlike
     * {@code ej.mps} it never finds a feasible solution, so the 30 second suffice limit never applies to it
     * and it runs until the 120 second abort limit. That longer window is what the capacity tests need.
     * <p>
     * At 60 variables it is also over the size at which the server hands a MILP to a native solver, so these
     * tests abort SCIP or HiGHS rather than the built-in solver.
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

    /**
     * Measures how many solves the server runs at once, by giving it more than it can take and asking
     * {@link OptClientV1#abortAll()} how many were running.
     * <p>
     * The models are ones that run until the server's own time limit, so none of them can finish early and be
     * missed by the count, and they are given a moment to be picked up before the question is asked.
     */
    @BeforeAll
    public static void measureCapacity() throws Exception {

        OptClientV1 client = new OptClientV1(URI.create(HOST));

        client.abortAll();

        byte[] hard = AbortAndCapacityTest.bytes(AbortAndCapacityTest.newHardModel(client));

        for (int i = 0; i < CAPACITY_MEASUREMENT_SUBMISSIONS; i++) {
            Assertions.assertNotNull(client.putOnQueueParsed(hard, "EBM", false).get(OptClientV1.KEY), "The server did not accept the submission");
        }

        Thread.sleep(4_000L);

        Map<String, Object> aborted = client.abortAll();

        NB_CONCURRENT_SOLVES = ((Integer) aborted.get(OptClientV1.ONGOING)).intValue();

        // Only the ongoing count is asserted on. The queued count, and the total, include whatever anyone
        // else has on the server, so neither can be checked against what this method submitted. The ongoing
        // count survives that: it is bounded by the number of workers no matter whose solves they are.
        Assertions.assertTrue(NB_CONCURRENT_SOLVES >= 1, "The server ran nothing at all, so there is no capacity to test against: " + aborted);
        Assertions.assertTrue(NB_CONCURRENT_SOLVES <= CAPACITY_MEASUREMENT_SUBMISSIONS,
                "The server ran more solves than were submitted to it, so something else is using it: " + aborted);

        System.out.println("Server runs " + NB_CONCURRENT_SOLVES + " solve(s) at a time");
    }

    private OptClientV1 myClient;

    /**
     * Leaves the server idle, not merely told to be idle. Aborting is not instantaneous – a solver has to
     * notice the interrupt and its worker has to come back for the next problem – so a single
     * {@link OptClientV1#abortAll()} can return while the previous test's solve is still winding down, and the
     * next test would then count it as its own.
     */
    @BeforeEach
    public void clearServer() throws Exception {

        myClient = new OptClientV1(URI.create(HOST));

        long deadline = System.currentTimeMillis() + IDLE_TIMEOUT_MILLIS;

        while (System.currentTimeMillis() < deadline) {

            Map<String, Object> aborted = myClient.abortAll();

            if (((Integer) aborted.get(OptClientV1.QUEUED)).intValue() == 0 && ((Integer) aborted.get(OptClientV1.ONGOING)).intValue() == 0) {
                return;
            }

            Thread.sleep(POLL_INTERVAL_MILLIS);
        }

        Assertions.fail("The server never went idle, so this test cannot tell its own solves from what it inherited");
    }

    @AfterEach
    public void releaseServer() {
        myClient.abortAll();
    }

    /**
     * The counts {@link OptClientV1#abortAll()} reports account for everything the server is holding, split
     * at the capacity limit: {@link #NB_CONCURRENT_SOLVES} running and the rest still queued.
     * <p>
     * The extra models are submitted only once every worker is already occupied for the next two minutes.
     * That is not merely tidy: a problem is briefly in neither the queue nor the ongoing set while a worker
     * is picking it up, and {@code abortAll} counts neither collection then. With no worker free to pick
     * anything up, that window cannot open.
     */
    @Test
    public void testAbortAllCountsQueuedAndOngoing() throws Exception {

        List<String> running = this.occupyEveryWorker();

        int nbQueued = 3;
        List<String> queued = new ArrayList<>(nbQueued);
        for (int i = 0; i < nbQueued; i++) {
            queued.add(this.submit(AbortAndCapacityTest.bytes(AbortAndCapacityTest.newHardModel(myClient))));
        }

        Map<String, Object> aborted = myClient.abortAll();

        Assertions.assertEquals(NB_CONCURRENT_SOLVES, ((Integer) aborted.get(OptClientV1.ONGOING)).intValue(), "Should have been running at its limit");
        Assertions.assertEquals(nbQueued, ((Integer) aborted.get(OptClientV1.QUEUED)).intValue(), "The rest should have been waiting in the queue");

        List<String> all = new ArrayList<>(running);
        all.addAll(queued);

        for (String key : all) {
            Map<String, Object> response = AbortAndCapacityTest.awaitSettled(myClient, key, 10_000L);
            Assertions.assertEquals("DONE", response.get(OptClientV1.STATUS), key);
            Assertions.assertNull(response.get(OptClientV1.RESULT), "An aborted solve must not publish a result");
        }
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
     * Requirement 1, the "without disturbing anything else" half. Every worker is put on a model that runs
     * for minutes and a quick one is left waiting in the queue. Aborting one of the running solves must leave
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
     * Requirement 1, the ongoing half: a solve that would run far longer than the caller is willing to wait
     * can be stopped, and stops promptly. It ends {@code DONE} with no result – whatever partial solution the
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
     * results have expired look the same.
     * <p>
     * Note that {@code poll-result} does not make this distinction – it answers {@code PENDING} for a key it
     * has never issued – so aborting is the only way to ask whether a key means anything.
     */
    @Test
    public void testAbortUnknownKey() throws Exception {

        Assertions.assertNull(myClient.abort("no-such-key-at-all"));
        Assertions.assertTrue(myClient.abortParsed("no-such-key-at-all").isEmpty());
    }

    /**
     * Cancelling with {@code mayInterruptIfRunning} set has to reach the server, not just stop the client
     * polling. {@link OptModel} does not expose the queue key, so what the server is still holding afterwards
     * is asked of {@link OptClientV1#abortAll()}, which reports it directly.
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
     * come back, rather than by reading a count. {@link OptClientV1#abortAll()} reports how many workers are
     * holding a problem, which is not the same as how many solves have escaped an abort: an aborted solve
     * stays counted until its solver notices the interrupt and the worker exits, and on a native solver that
     * is not immediate.
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
     * Requirement 2: one very slow solve does not stop another model being submitted, and a quick model
     * submitted while it runs finishes on its own schedule rather than waiting for the slow one.
     * <p>
     * Overtaking needs a second worker to overtake on. Where the server runs one solve at a time the quick
     * model can only wait its turn, and that is what
     * {@link #testSubmissionBeyondCapacityWaitsForIt()} covers instead – so this skips rather than asserting
     * something the configuration makes impossible.
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
     * Requirement 3: with every worker already taken, a further submission is accepted and given a key, but
     * no worker takes it – so it produces no result for as long as the others hold the workers.
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
     * that stays {@code PENDING} does not prove on its own that no worker is free – on two vCPUs a running
     * hard MILP starves a trivial one badly enough to look the same. With the occupying solves known to be
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
     * Waits long enough that a solve started with {@link #startSlowSolve()} has reached the server.
     * {@link OptModel#minimise()} returns before the model has left the client, and there is nothing to
     * observe in the meantime – the queue key it would be observed by is exactly what {@link OptModel} does
     * not expose. So this is a fixed wait, generous for a local serialisation and one HTTP POST, and the tests
     * that use it say so in their failure messages when it turns out not to have been enough.
     */
    private void awaitSubmitted() throws InterruptedException {
        Thread.sleep(SUBMISSION_MILLIS);
    }

    /**
     * Submits a model that runs until the server's own time limit, through {@link OptModel} rather than
     * through {@link OptClientV1}, so that the {@link Future} is the only handle on it – which is the point
     * of the tests that use this. Returns as soon as the solve is under way locally; the model itself is
     * still in flight.
     */
    private Future<OptResult> startSlowSolve() {
        return AbortAndCapacityTest.newHardModel(myClient).minimise();
    }

    private String submit(final byte[] model) {
        return this.submit(model, "EBM");
    }

    private String submit(final byte[] model, final String format) {

        String key = (String) myClient.putOnQueueParsed(model, format, false).get(OptClientV1.KEY);

        Assertions.assertNotNull(key, "The server did not accept the submission");

        return key;
    }

}
