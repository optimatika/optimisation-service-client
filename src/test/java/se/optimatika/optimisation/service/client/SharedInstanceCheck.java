package se.optimatika.optimisation.service.client;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.net.CookieManager;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks the live shared instance before a release of the client. Not a unit test: it talks to the real
 * service, and bills the key a few core-minutes.
 * <ol>
 * <li>What a request gets without a key, with a key in force, and with one that is not: the licence the
 * environment reports, the solvers, and whether a model above each size limit is refused.</li>
 * <li>That a stream cut on purpose can be followed again by key, on the same instance (session affinity), and
 * that a licensed solve can be aborted.</li>
 * <li>That cancelling the {@link Future} from {@link OptModel} aborts the solve on the server.</li>
 * <li>That the deprecated {@link OptClientV1#abortAll()} throws.</li>
 * </ol>
 * It ends with what the licensed solves should have put on the meter, to compare with the events in Polar.
 * <p>
 * The licence key is the first argument, or {@code OPTIMATIKA_LICENCE_KEY}. A second argument is a key that
 * is not in force, such as a revoked one; without it a made-up key is used. {@code SERVICE_HOST} picks
 * another server than https://service-api.optimatika.se. Run it from the IDE, or:
 *
 * <pre>
 * mvn -q test-compile
 * java -cp target/classes:target/test-classes se.optimatika.optimisation.service.client.SharedInstanceCheck &lt;key&gt; [revoked key]
 * </pre>
 *
 * With {@code --load N} it checks nothing, and puts load on the service instead: one hard MIP more every
 * {@code --step} minutes (3 by default) until N run, then all N for one more step, then aborts them. Each MIP
 * is submitted as a separate user, with cookies of its own, unless {@code --one-client} makes them one. It
 * prints a timeline to compare with the service's CPU utilisation and instance count in the Cloud Run console.
 *
 * <pre>
 * java -cp target/classes:target/test-classes se.optimatika.optimisation.service.client.SharedInstanceCheck &lt;key&gt; --load 3 [--step 3] [--one-client]
 * </pre>
 *
 * Written for Java 11, so that it can also be run on the oldest Java the client supports.
 */
public final class SharedInstanceCheck {

    /** A licensed solve, for the summary of what Polar should have recorded. */
    private static final class Billed {

        final String name;
        final double seconds;

        Billed(final String name, final double seconds) {
            this.name = name;
            this.seconds = seconds;
        }

    }

    /** One MIP of the load, followed over its stream until it is aborted. */
    private static final class Running {

        final HttpClient http;
        final String key;
        volatile String latest;
        final long started = System.nanoTime();
        final BufferedReader stream;
        volatile boolean open = true;
        volatile long finished = 0L;
        boolean told = false;

        Running(final HttpClient http, final String key, final String first, final BufferedReader stream) {
            this.http = http;
            this.key = key;
            this.latest = first;
            this.stream = stream;
        }

        /** Keeps the latest event until the stream ends. */
        void follow() {
            try {
                for (String event = SharedInstanceCheck.nextEvent(stream); event != null; event = SharedInstanceCheck.nextEvent(stream)) {
                    if (finished == 0L && "DONE".equals(SharedInstanceCheck.field(event, "status"))) {
                        finished = System.nanoTime();
                    }
                    latest = event;
                }
            } catch (IOException cause) {
                // Closed, by us or by the connection.
            } finally {
                open = false;
            }
        }

    }

    private static final String ABORT = "/optimisation/v1/abort/";
    private static final String ENVIRONMENT = "/optimisation/v1/environment";
    private static final String EVENT_STREAM = "text/event-stream";
    private static final String HOST = AbstractTest.HOST;
    private static final int LICENSED_MAX_SIZE = 10_000;
    private static final String POLL_RESULT = "/optimisation/v1/poll-result/";
    private static final String PUT_ON_QUEUE = "/optimisation/v1/put-on-queue/";
    private static final Duration SOLVE_TIMEOUT = Duration.ofMinutes(2);
    private static final int UNLICENSED_MAX_SIZE = 1_000;

    private static final List<Billed> BILLED = new ArrayList<>();
    /** The HTTP client, with its cookies, that started each hard solve – the abort at the end goes through it. */
    private static final java.util.Map<String, HttpClient> CLIENTS = new java.util.HashMap<>();
    private static boolean BUILD_SHOWN = false;
    private static int FAILURES = 0;
    /** Hard solves started here, aborted at the end whatever happens, so that none runs on for an hour. */
    private static final List<String> STARTED = new ArrayList<>();

    public static void main(final String[] args) throws Exception {

        List<String> positional = new ArrayList<>();
        int load = 0;
        int step = 3;
        boolean oneClient = false;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
            case "--load":
                load = Integer.parseInt(args[++i]);
                break;
            case "--step":
                step = Integer.parseInt(args[++i]);
                break;
            case "--one-client":
                oneClient = true;
                break;
            default:
                positional.add(args[i]);
            }
        }

        String key = positional.size() > 0 ? positional.get(0) : System.getenv("OPTIMATIKA_LICENCE_KEY");
        String notInForce = positional.size() > 1 ? positional.get(1) : "OPTIMATIKA-00000000-0000-0000-0000-000000000000";

        if (key == null || key.isBlank()) {
            System.err.println("Usage: SharedInstanceCheck <licence key> [key not in force] [--load N [--step minutes] [--one-client]]");
            System.err.println("       (or set OPTIMATIKA_LICENCE_KEY)");
            System.exit(2);
            return;
        }

        if (load > 0) {
            System.out.println("Server:       " + HOST);
            System.out.println("Licence key:  " + SharedInstanceCheck.masked(key));
            System.out.println();
            SharedInstanceCheck.load(key, load, step, oneClient);
            System.exit(0);
            return;
        }

        System.out.println("Server:       " + HOST);
        System.out.println("Licence key:  " + SharedInstanceCheck.masked(key));
        System.out.println("Not in force: " + SharedInstanceCheck.masked(notInForce) + (positional.size() > 1 ? "" : " (made up)"));
        System.out.println("Java:         " + System.getProperty("java.version"));
        System.out.println();

        if (!HOST.startsWith("https://")) {
            SharedInstanceCheck.info("Not HTTPS – the key goes over the wire unencrypted");
        }

        OptClientV1 anonymous = new OptClientV1(URI.create(HOST));
        OptClientV1 licensed = new OptClientV1(URI.create(HOST), key);
        OptClientV1 revoked = new OptClientV1(URI.create(HOST), notInForce);

        int threads = 0;

        try {

            System.out.println("== Without a key");
            SharedInstanceCheck.environment(anonymous, "no licence key", false);
            SharedInstanceCheck.lp(anonymous, "small LP", 100, true, false);
            SharedInstanceCheck.lp(anonymous, "LP above " + UNLICENSED_MAX_SIZE + " variables", UNLICENSED_MAX_SIZE + 500, false, false);

            System.out.println();
            System.out.println("== With the key");
            threads = SharedInstanceCheck.environment(licensed, "(licensed", true);
            SharedInstanceCheck.lp(licensed, "LP above " + UNLICENSED_MAX_SIZE + " variables", UNLICENSED_MAX_SIZE + 500, true, true);
            SharedInstanceCheck.lp(licensed, "LP above " + LICENSED_MAX_SIZE + " variables", LICENSED_MAX_SIZE + 500, false, true);

            System.out.println();
            System.out.println("== With a key not in force");
            SharedInstanceCheck.environment(revoked, null, false);
            SharedInstanceCheck.lp(revoked, "LP above " + UNLICENSED_MAX_SIZE + " variables", UNLICENSED_MAX_SIZE + 500, false, false);

            System.out.println();
            System.out.println("== A stream cut, followed again by key, then aborted");
            SharedInstanceCheck.cutAndReconnect(key);

            System.out.println();
            System.out.println("== Cancelling the Future from OptModel");
            SharedInstanceCheck.cancel(licensed);

            System.out.println();
            System.out.println("== Deprecated abortAll()");
            SharedInstanceCheck.abortAll(anonymous);

        } finally {
            SharedInstanceCheck.abortStarted(key);
        }

        System.out.println();
        System.out.println("== What Polar should have recorded");
        SharedInstanceCheck.billing(threads);

        System.out.println();
        if (FAILURES == 0) {
            System.out.println("All checks passed.");
        } else {
            System.out.println(FAILURES + " check(s) FAILED.");
        }

        System.exit(FAILURES == 0 ? 0 : 1);
    }

    @SuppressWarnings("removal")
    private static void abortAll(final OptClientV1 client) {
        try {
            client.abortAll();
            SharedInstanceCheck.check("abortAll() throws", false, "it returned");
        } catch (UnsupportedOperationException expected) {
            SharedInstanceCheck.check("abortAll() throws UnsupportedOperationException", true, expected.getMessage());
        }
    }

    /**
     * Aborts the hard solves this run started, so that none of them occupies a worker, or is billed, until
     * its time limit.
     */
    private static void abortStarted(final String key) {

        if (STARTED.isEmpty()) {
            return;
        }

        System.out.println();
        System.out.println("== Aborting the hard solves started here");

        for (String solve : STARTED) {

            HttpClient http = CLIENTS.containsKey(solve) ? CLIENTS.get(solve)
                    : HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

            String outcome;
            try {
                HttpRequest request = SharedInstanceCheck.request(ABORT + solve, key).POST(HttpRequest.BodyPublishers.noBody()).build();
                HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
                outcome = response.statusCode() == 200 ? SharedInstanceCheck.field(response.body(), "status") : "HTTP " + response.statusCode();
            } catch (Exception cause) {
                outcome = cause.getMessage();
            }

            if ("DONE".equals(outcome)) {
                System.out.println("      " + solve + " – DONE");
            } else {
                System.out.println("WARN  " + solve + " – " + outcome
                        + ": not reached, so it runs on to its time limit, holding a worker, and is billed for that");
            }
        }
    }

    private static String awaitKey(final OptModel.RemoteSolve solve) throws InterruptedException {
        for (int attempt = 0; attempt < 600 && solve.key() == null; attempt++) {
            Thread.sleep(100L);
        }
        return solve.key();
    }

    private static void billing(final int threads) {

        if (BILLED.isEmpty()) {
            SharedInstanceCheck.info("No licensed solve ran");
            return;
        }

        int per = threads > 0 ? threads : 3;
        double total = 0.0;

        for (Billed billed : BILLED) {
            if (billed.seconds < 0.0) {
                System.out.println(String.format("      %-40s unknown – until its abort, or its time limit if that was not reached", billed.name));
                continue;
            }
            double most = billed.seconds / 60.0 * per;
            total += most;
            System.out.println(String.format("      %-40s at most %.3f core-minutes (%.1f s × %d threads)", billed.name, most, billed.seconds, per));
        }

        System.out.println(String.format("      In Polar: %d usage events, channel shared, together at most %.3f core-minutes, plus any unknown above.",
                BILLED.size(), total));
        System.out.println("      The server measures from when a solve leaves the queue, so each event is somewhat below its figure here.");
    }

    /**
     * Cancels an {@link OptModel} solve with {@code Future.cancel(true)}, and checks that the server has it
     * as done, without a result.
     */
    private static void cancel(final OptClientV1 licensed) throws Exception {

        Future<OptResult> future = SharedInstanceCheck.marketSplit(licensed).minimise();

        if (!(future instanceof OptModel.RemoteSolve)) {
            SharedInstanceCheck.check("cancel", false, "the Future is not OptModel's own, so its queue key can't be read");
            return;
        }

        OptModel.RemoteSolve solve = (OptModel.RemoteSolve) future;

        long started = System.nanoTime();
        String key = SharedInstanceCheck.awaitKey(solve);

        if (key == null) {
            SharedInstanceCheck.check("submitted", false, "no queue key within 60 s");
            future.cancel(true);
            return;
        }
        STARTED.add(key);

        Thread.sleep(3_000L);

        if (future.isDone()) {
            SharedInstanceCheck.info("The hard model finished before it could be cancelled – make it harder");
            return;
        }

        boolean cancelled = future.cancel(true);
        SharedInstanceCheck.check("Future.cancel(true)", cancelled, "key " + key);

        String last = null;
        for (int attempt = 0; attempt < 15; attempt++) {
            Thread.sleep(1_000L);
            try {
                last = licensed.pollResult(key);
            } catch (IOException cause) {
                last = "IOException: " + cause.getMessage();
                break;
            }
            if ("DONE".equals(SharedInstanceCheck.field(last, "status"))) {
                break;
            }
        }

        BILLED.add(new Billed("cancelled MIP", (System.nanoTime() - started) / 1e9));

        boolean done = last != null && "DONE".equals(SharedInstanceCheck.field(last, "status"));
        SharedInstanceCheck.check("aborted on the server: DONE, without a result", done && !last.contains("\"result\""), last);
    }

    private static void check(final String name, final boolean passed, final String detail) {
        if (!passed) {
            FAILURES++;
        }
        System.out.println((passed ? "PASS  " : "FAIL  ") + name + (detail != null ? " – " + detail : ""));
    }

    /**
     * Submits a hard model over a stream, cuts the stream after the first event, follows the solve again by
     * key with the same cookies, and aborts it. Raw HTTP, so that the cut is certain to happen.
     */
    private static void cutAndReconnect(final String key) throws Exception {

        CookieManager cookies = new CookieManager();
        HttpClient http = HttpClient.newBuilder().cookieHandler(cookies).connectTimeout(Duration.ofSeconds(10)).build();

        byte[] model = SharedInstanceCheck.marketSplit(new OptClientV1(URI.create(HOST))).exportModel("EBM").readAllBytes();

        long started = System.nanoTime();

        HttpRequest submit = SharedInstanceCheck.request(PUT_ON_QUEUE + "EBM/MIN", key).header("Accept", EVENT_STREAM)
                .POST(HttpRequest.BodyPublishers.ofByteArray(model)).build();

        HttpResponse<InputStream> response = http.send(submit, HttpResponse.BodyHandlers.ofInputStream());

        String first;
        try (BufferedReader events = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            boolean streamed = response.statusCode() == 200
                    && response.headers().firstValue("Content-Type").map(type -> type.startsWith(EVENT_STREAM)).orElse(false);
            SharedInstanceCheck.check("submitted, and streamed", streamed, "HTTP " + response.statusCode());
            if (!streamed) {
                return;
            }
            first = SharedInstanceCheck.nextEvent(events);
        } // Closing it here is the cut.

        String solve = SharedInstanceCheck.field(first, "key");
        if (solve == null) {
            SharedInstanceCheck.check("first event has the key", false, first);
            return;
        }
        STARTED.add(solve);
        CLIENTS.put(solve, http);
        BILLED.add(new Billed("MIP cut, followed and aborted", -1.0));
        SharedInstanceCheck.info("Cut the stream after: " + first);

        List<HttpCookie> held = cookies.getCookieStore().getCookies();
        StringBuilder names = new StringBuilder();
        for (HttpCookie cookie : held) {
            names.append(names.length() > 0 ? ", " : "").append(cookie.getName());
        }
        SharedInstanceCheck.check("session-affinity cookie", !held.isEmpty(), held.isEmpty() ? "none set" : names.toString());

        Thread.sleep(3_000L);

        HttpRequest follow = SharedInstanceCheck.request(POLL_RESULT + solve, key).header("Accept", EVENT_STREAM).GET().build();
        HttpResponse<InputStream> again = http.send(follow, HttpResponse.BodyHandlers.ofInputStream());

        try (BufferedReader events = new BufferedReader(new InputStreamReader(again.body(), StandardCharsets.UTF_8))) {
            if (again.statusCode() == 404) {
                SharedInstanceCheck.check("followed again by key", false, "404 – the reconnect reached an instance that doesn't know the solve");
                return;
            }
            String event = again.statusCode() == 200 ? SharedInstanceCheck.nextEvent(events) : null;
            String status = event != null ? SharedInstanceCheck.field(event, "status") : null;
            SharedInstanceCheck.check("followed again by key, same instance", "PENDING".equals(status) || "DONE".equals(status),
                    "HTTP " + again.statusCode() + ", " + event);
            if ("DONE".equals(status)) {
                SharedInstanceCheck.info("The hard model was already done – the abort below has nothing to stop");
            }
        }

        HttpRequest abort = SharedInstanceCheck.request(ABORT + solve, key).POST(HttpRequest.BodyPublishers.noBody()).build();
        HttpResponse<String> aborted = http.send(abort, HttpResponse.BodyHandlers.ofString());

        BILLED.set(BILLED.size() - 1, new Billed("MIP cut, followed and aborted", (System.nanoTime() - started) / 1e9));

        boolean done = aborted.statusCode() == 200 && "DONE".equals(SharedInstanceCheck.field(aborted.body(), "status"))
                && !aborted.body().contains("\"result\"");
        SharedInstanceCheck.check("aborted: DONE, without a result", done, "HTTP " + aborted.statusCode() + ", " + aborted.body());
    }

    /**
     * Reads the environment, as the client sees it with its key or without, and checks the licence it
     * reports.
     *
     * @param expected text the licence description should contain, or null for a key not in force
     * @return the threads each solve is given, or 0 if not reported
     */
    private static int environment(final OptClientV1 client, final String expected, final boolean nativeSolvers) {

        String json = client.getServiceEnvironment();

        if (json == null) {
            SharedInstanceCheck.check("environment", false, "no answer");
            return 0;
        }

        if (!BUILD_SHOWN) {
            BUILD_SHOWN = true;
            SharedInstanceCheck.info("Build " + SharedInstanceCheck.field(json, "commit") + ", built " + SharedInstanceCheck.field(json, "built")
                    + (Boolean.parseBoolean(SharedInstanceCheck.field(json, "dirty")) ? ", dirty" : ""));
        }

        String licence = SharedInstanceCheck.field(json, "licence");

        if (expected != null) {
            SharedInstanceCheck.check("licence", licence != null && licence.contains(expected), licence);
        } else {
            boolean restricted = licence != null && (licence.contains("not in force") || licence.contains("not confirmed"));
            SharedInstanceCheck.check("licence", restricted, licence);
        }

        int solvers = OptClientV1.parseAvailableSolvers(json);
        SharedInstanceCheck.check(nativeSolvers ? "native solvers available" : "ojAlgo's own solvers only", nativeSolvers == (solvers > 4),
                solvers + " solvers");

        String workers = SharedInstanceCheck.field(json, "workers");
        String threads = SharedInstanceCheck.field(json, "threadsPerWorker");
        boolean reported = workers != null && threads != null && Integer.parseInt(workers) > 0 && Integer.parseInt(threads) > 0;
        SharedInstanceCheck.check("capacity reported, workers × threads", reported, workers + " × " + threads + ", running "
                + SharedInstanceCheck.field(json, "running") + ", queued " + SharedInstanceCheck.field(json, "queued"));

        return threads != null ? Integer.parseInt(threads) : 0;
    }

    /**
     * A string or number field of a flat JSON object, or null.
     */
    private static String field(final String json, final String name) {

        if (json == null) {
            return null;
        }

        Matcher matcher = Pattern.compile("\"" + Pattern.quote(name) + "\"\\s*:\\s*(?:\"((?:[^\"\\\\]|\\\\.)*)\"|(-?[0-9.]+))").matcher(json);

        if (!matcher.find()) {
            return null;
        }

        return matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
    }

    private static void info(final String message) {
        System.out.println("INFO  " + message);
    }

    /**
     * Maximises the sum of {@code n} variables in [0, 1] whose sum is at most {@code n / 2}, and checks
     * whether it was solved, or refused for its size.
     */
    private static void lp(final OptClientV1 client, final String name, final int n, final boolean solvable, final boolean billed) throws Exception {

        OptModel model = client.newModel();
        OptConstraint half = model.newConstraint("half").upper(n / 2);

        for (int i = 0; i < n; i++) {
            OptVariable x = model.newRealVariable("x" + i).lower(0.0).upper(1.0);
            half.set(x, 1L);
            model.objective().set(x, 1L);
        }

        long started = System.nanoTime();
        OptResult result = model.maximise().get(SOLVE_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        double seconds = (System.nanoTime() - started) / 1e9;

        if (billed) {
            BILLED.add(new Billed(name, seconds));
        }

        if (solvable) {
            boolean right = result.isOptimal() && result.getValue() != null
                    && result.getValue().subtract(BigDecimal.valueOf(n / 2)).abs().compareTo(new BigDecimal("1e-6")) <= 0;
            SharedInstanceCheck.check(name + ": solved", right, "value " + result.getValue() + String.format(", %.1f s", seconds));
        } else {
            SharedInstanceCheck.check(name + ": refused", !result.isFeasible(), result.isFeasible() ? "solved, value " + result.getValue() : "no solution");
        }
    }

    /**
     * A market split problem (Cornuéjols and Dawande): 4 equations in 30 binary variables, with slack, the
     * total slack minimised. Small, and very hard for branch and bound, so it is still running when the
     * checks cut, follow and abort it.
     */
    private static OptModel marketSplit(final OptClientV1 client) {
        return SharedInstanceCheck.marketSplit(client, 1L, 4, 30);
    }

    /**
     * A market split problem (Cornuéjols and Dawande): m rows, n binary variables, and 2m slack variables. The
     * server gives a MILP of fewer than 50 variables to ojAlgo's own solver, which uses all the threads it is
     * given, and a larger one to HiGHS or SCIP, which use one.
     */
    private static OptModel marketSplit(final OptClientV1 client, final long seed, final int m, final int n) {

        Random random = new Random(seed);

        OptModel model = client.newModel();

        List<OptVariable> x = new ArrayList<>();
        for (int j = 0; j < n; j++) {
            x.add(model.newBinaryVariable("x" + j));
        }

        for (int i = 0; i < m; i++) {

            OptConstraint row = model.newConstraint("split" + i);
            long sum = 0L;

            for (int j = 0; j < n; j++) {
                long a = random.nextInt(100);
                row.set(x.get(j), a);
                sum += a;
            }

            OptVariable over = model.newRealVariable("over" + i).lower(0.0);
            OptVariable under = model.newRealVariable("under" + i).lower(0.0);

            row.set(over, -1L).set(under, 1L).level(sum / 2);
            model.objective().set(over, 1L).set(under, 1L);
        }

        return model;
    }

    /**
     * One hard MIP more every step until there are {@code n}, all of them for one more step, then aborts them.
     */
    private static void load(final String key, final int n, final int step, final boolean oneClient) throws Exception {

        String threads = SharedInstanceCheck.field(new OptClientV1(URI.create(HOST)).getServiceEnvironment(), "threadsPerWorker");
        int per = threads != null ? Integer.parseInt(threads) : 3;
        double most = per * step * n * (n + 1) / 2.0;

        System.out.println("== Load: " + n + " hard MIPs, one more every " + step + " min, all " + n + " for the last " + step + " min, then aborted – "
                + n * step + " min in all");
        System.out.println("   " + (oneClient ? "One client, sharing its cookies." : "Each MIP a separate user, with cookies of its own."));
        System.out.println(String.format("   Billed while they run: at most about %.0f core-minutes (EUR %.2f) in all.", most, most * 0.015));
        System.out.println("   Compare with CPU utilisation and instance count in the Cloud Run console, by the clock times below.");
        System.out.println();

        HttpClient shared = oneClient ? SharedInstanceCheck.newHttp() : null;
        List<Running> running = new ArrayList<>();
        long begun = System.nanoTime();

        try {

            for (int i = 0; i < n; i++) {

                Running mip = SharedInstanceCheck.submitMip(oneClient ? shared : SharedInstanceCheck.newHttp(), key, i + 1L);

                if (mip != null) {
                    running.add(mip);
                    SharedInstanceCheck.say(begun, "MIP " + (i + 1) + " submitted, key " + mip.key);
                } else {
                    SharedInstanceCheck.say(begun, "MIP " + (i + 1) + " could not be submitted");
                }

                SharedInstanceCheck.watch(begun, running, step);
            }

        } finally {

            SharedInstanceCheck.say(begun, "Aborting " + running.size() + " MIPs");

            double billed = 0.0;

            for (Running mip : running) {

                String outcome;
                try {
                    HttpRequest request = SharedInstanceCheck.request(ABORT + mip.key, key).POST(HttpRequest.BodyPublishers.noBody()).build();
                    HttpResponse<String> response = mip.http.send(request, HttpResponse.BodyHandlers.ofString());
                    outcome = response.statusCode() == 200 ? SharedInstanceCheck.field(response.body(), "status") : "HTTP " + response.statusCode();
                } catch (Exception cause) {
                    outcome = cause.getMessage();
                }

                double minutes = (System.nanoTime() - mip.started) / 60e9;
                billed += per * ((mip.finished != 0L ? mip.finished : System.nanoTime()) - mip.started) / 60e9;

                String was = mip.told ? "had already finished" : "DONE".equals(outcome) ? "aborted" : "abort missed (" + outcome + ")";
                System.out.println(String.format("      %s – %s, %.1f min after it was submitted%s", mip.key, was, minutes,
                        mip.told || "DONE".equals(outcome) ? "" : " – its stream closes when this program ends, and it should stop 60 s later"));
            }

            // The streams still open close when the program ends: closing a reader that blocks in readLine() would wait for it.
            System.out.println(String.format("      Billed: at most about %.1f core-minutes in all.", billed));
        }
    }

    private static HttpClient newHttp() {
        return HttpClient.newBuilder().cookieHandler(new CookieManager()).connectTimeout(Duration.ofSeconds(10)).build();
    }

    private static void say(final long begun, final String message) {
        long seconds = (System.nanoTime() - begun) / 1_000_000_000L;
        System.out.println(String.format("%s  +%02d:%02d  %s", java.time.LocalTime.now().withNano(0), seconds / 60, seconds % 60, message));
    }

    /**
     * Submits one hard MIP over a stream, and keeps following it on a thread of its own.
     */
    private static Running submitMip(final HttpClient http, final String key, final long seed) throws Exception {

        byte[] model = SharedInstanceCheck.marketSplit(new OptClientV1(URI.create(HOST)), seed, 5, 36).exportModel("EBM").readAllBytes();

        HttpRequest submit = SharedInstanceCheck.request(PUT_ON_QUEUE + "EBM/MIN", key).header("Accept", EVENT_STREAM)
                .POST(HttpRequest.BodyPublishers.ofByteArray(model)).build();

        HttpResponse<InputStream> response = http.send(submit, HttpResponse.BodyHandlers.ofInputStream());

        BufferedReader events = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8));
        String first = response.statusCode() == 200 ? SharedInstanceCheck.nextEvent(events) : null;
        String solve = SharedInstanceCheck.field(first, "key");

        if (solve == null) {
            events.close();
            return null;
        }

        Running mip = new Running(http, solve, first, events);
        Thread follower = new Thread(mip::follow, "follow-" + solve);
        follower.setDaemon(true);
        follower.start();

        return mip;
    }

    /**
     * Prints, every 30 s for {@code minutes}, how many of the MIPs are pending, and what one instance – whichever
     * Cloud Run picks for a request without cookies – says it is running.
     */
    private static void watch(final long begun, final List<Running> running, final int minutes) throws InterruptedException {

        for (int tick = 0; tick < minutes * 2; tick++) {

            Thread.sleep(30_000L);

            int pending = 0;
            int done = 0;
            int lost = 0;
            for (Running mip : running) {
                if ("DONE".equals(SharedInstanceCheck.field(mip.latest, "status"))) {
                    done++;
                    if (!mip.told) {
                        mip.told = true;
                        String result = String.valueOf(SharedInstanceCheck.field(mip.latest, "result"));
                        SharedInstanceCheck.say(begun, String.format("%s done after %.1f min: %s", mip.key, (mip.finished - mip.started) / 60e9,
                                result.length() > 60 ? result.substring(0, 60) + "…" : result));
                    }
                } else if (mip.open) {
                    pending++;
                } else {
                    lost++;
                }
            }

            String json = new OptClientV1(URI.create(HOST)).getServiceEnvironment();
            String busy = SharedInstanceCheck.field(json, "running");
            String instance = busy != null ? "an instance says: running " + busy + ", queued " + SharedInstanceCheck.field(json, "queued")
                    : "no environment answer";

            SharedInstanceCheck.say(begun, running.size() + " MIPs: " + pending + " pending" + (done > 0 ? ", " + done + " done" : "")
                    + (lost > 0 ? ", " + lost + " streams cut" : "") + " – " + instance);
        }
    }

    private static String masked(final String key) {
        return key.length() <= 12 ? "****" : key.substring(0, 12) + "…";
    }

    /**
     * The data of the next server-sent event, or null when the stream ends.
     */
    private static String nextEvent(final BufferedReader events) throws IOException {

        StringBuilder data = null;

        for (String line = events.readLine(); line != null; line = events.readLine()) {
            if (line.isEmpty()) {
                if (data != null) {
                    return data.toString();
                }
            } else if (line.startsWith("data:")) {
                String value = line.substring(line.startsWith("data: ") ? 6 : 5);
                if (data == null) {
                    data = new StringBuilder(value);
                } else {
                    data.append('\n').append(value);
                }
            }
        }

        return data != null ? data.toString() : null;
    }

    private static HttpRequest.Builder request(final String path, final String key) {
        return HttpRequest.newBuilder(URI.create(HOST + path)).header("Authorization", "Bearer " + key).timeout(SOLVE_TIMEOUT);
    }

    private SharedInstanceCheck() {}

}
