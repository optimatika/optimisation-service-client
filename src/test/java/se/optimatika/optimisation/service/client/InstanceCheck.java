package se.optimatika.optimisation.service.client;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks an Optimisation Service instance – your own deployment, or Optimatika's shared instance – the way
 * this client uses it. A program to run by hand, not a unit test: it talks to the live service, and on a
 * licensed one its solves count as usage – typically well under one core-minute in all.
 * <ol>
 * <li>What the instance says about itself: its build, the licence it runs with, its solvers, and how many
 * solves it runs at once with how many threads each.</li>
 * <li>That it solves an LP and a small MIP correctly, and refuses a model above the size limit it reports, if
 * it reports one.</li>
 * <li>That it streams a solve's status, and that the first status arrives at once. Something in between that
 * holds the stream back – a buffering proxy, say – would keep a client waiting for the whole solve before it
 * even gets the solve's key.</li>
 * <li>That a solve can be aborted, both directly by its key and by cancelling the {@code Future} from
 * {@link OptModel}.</li>
 * <li>With a licence key: whether the instance goes by it, as Optimatika's shared instance does, or ignores
 * it, as a deployment with its own licence does – and what the key gets.</li>
 * </ol>
 * Without an instance URL it checks Optimatika's shared instance, https://service-api.optimatika.se, or the
 * one {@code SERVICE_HOST} names. The licence key is optional: give it after the URL, or set
 * {@code OPTIMATIKA_LICENCE_KEY}, which keeps it out of your shell's history. Run it from the IDE, or:
 *
 * <pre>
 * mvn -q test-compile
 * java -cp target/classes:target/test-classes se.optimatika.optimisation.service.client.InstanceCheck [instance URL] [licence key]
 * </pre>
 *
 * Exits with 0 when every check passed, 1 when one failed.
 */
public final class InstanceCheck {

    /** What the instance says about itself, as one client sees it. */
    private static final class Environment {

        final String json;
        /** The licence's own description, without the name of the licence service in front of it. */
        final String licence;
        final String licenceText;
        final int limit;
        final boolean nativeSolvers;
        final int solvers;
        final int threads;

        Environment(final String json) {
            this.json = json;
            String text = InstanceCheck.field(json, "licence");
            licenceText = text;
            int open = text != null ? text.indexOf('(') : -1;
            int close = text != null ? text.lastIndexOf(')') : -1;
            licence = open >= 0 && close > open ? text.substring(open + 1, close) : text;
            // An instance that reports its limits says so in "limits"; an older one only in the licence text.
            String reported = InstanceCheck.field(json, "maxProblemSize");
            Matcher size = Pattern.compile("max (?:size: )?([0-9]+)(?= variables|,|\\))").matcher(text != null ? text : "");
            limit = reported != null ? Integer.parseInt(reported) : size.find() ? Integer.parseInt(size.group(1)) : 0;
            List<String> available = InstanceCheck.solvers(json);
            solvers = available.size();
            nativeSolvers = available.stream().anyMatch(solver -> !solver.endsWith("-JVM"));
            String perWorker = InstanceCheck.field(json, "threadsPerWorker");
            threads = perWorker != null ? Integer.parseInt(perWorker) : 0;
        }

        boolean isLicensed() {
            return nativeSolvers;
        }

    }

    private static final String EVENT_STREAM = "text/event-stream";
    private static int FAILURES = 0;
    /** How long the first status of a streamed solve may take before something is taken to hold it back. */
    private static final double FIRST_EVENT_SECONDS = 5.0;
    private static final long SOLVE_TIMEOUT_SECONDS = 120L;

    public static void main(final String[] args) throws Exception {

        String host = AbstractTest.HOST;
        String key = System.getenv("OPTIMATIKA_LICENCE_KEY");

        for (String arg : args) {
            if (arg.startsWith("http://") || arg.startsWith("https://")) {
                host = arg.endsWith("/") ? arg.substring(0, arg.length() - 1) : arg;
            } else {
                key = arg;
            }
        }

        if (key != null && key.isBlank()) {
            key = null;
        }

        System.out.println("Instance:     " + host);
        System.out.println("Licence key:  " + (key != null ? InstanceCheck.masked(key) : "none"));
        System.out.println("Java:         " + System.getProperty("java.version"));
        System.out.println();

        if (key != null && !host.startsWith("https://")) {
            InstanceCheck.info("Not HTTPS – the key goes over the wire unencrypted");
        }

        OptClientV1 anonymous = new OptClientV1(URI.create(host));

        System.out.println("== The instance");
        if (!anonymous.isServiceAvailable()) {
            InstanceCheck.check("available", false, anonymous.getServiceEnvironment());
            InstanceCheck.finish();
            return;
        }
        Environment without = new Environment(anonymous.getServiceEnvironment());
        InstanceCheck.describe(without, true);

        OptClientV1 client = anonymous;
        Environment used = without;

        if (key != null) {
            System.out.println();
            System.out.println("== Your licence key");
            OptClientV1 licensed = new OptClientV1(URI.create(host), key);
            Environment with = new Environment(licensed.getServiceEnvironment());
            // The licence and the solvers, not the whole environment: its counts of running solves change between asks.
            if (Objects.equals(with.licenceText, without.licenceText) && with.solvers == without.solvers) {
                InstanceCheck.info("The instance ignores the key: it has a licence of its own, which applies to every request");
            } else {
                InstanceCheck.info("The instance goes by the key, request by request");
                InstanceCheck.describe(with, false);
                InstanceCheck.check("the key is in force", with.isLicensed(), with.licence);
                client = licensed;
                used = with;
            }
        }

        System.out.println();
        System.out.println("== Solving" + (client != anonymous ? ", with your key" : ""));
        InstanceCheck.lp(client, "LP, 100 variables", 100, true);
        InstanceCheck.knapsack(client);
        if (client != anonymous && without.limit > 0) {
            InstanceCheck.lp(client, "LP above the limit without a key, " + (without.limit + 500) + " variables", without.limit + 500, true);
        }
        if (used.limit > 0) {
            InstanceCheck.lp(client, "LP above the size limit, " + (used.limit + 500) + " variables", used.limit + 500, false);
        } else {
            InstanceCheck.info("No size limit reported, so none to exceed");
        }

        System.out.println();
        System.out.println("== Streaming and aborting");
        InstanceCheck.stream(host, client != anonymous ? key : null);
        InstanceCheck.cancel(client);

        InstanceCheck.finish();
    }

    /**
     * Starts a hard model through {@link OptModel}, cancels its {@code Future} with {@code cancel(true)}, and
     * checks that the instance then has the solve as done, without a result.
     */
    private static void cancel(final OptClientV1 client) throws Exception {

        OptModel.RemoteSolve solve = (OptModel.RemoteSolve) InstanceCheck.marketSplit(client).minimise();

        // OptModel does not hand out the key the instance gave the solve; this package can read it, to check.
        for (int attempt = 0; attempt < 600 && solve.key() == null && !solve.isDone(); attempt++) {
            Thread.sleep(100L);
        }
        String key = solve.key();

        if (key == null) {
            InstanceCheck.check("cancel: submitted", false, "no key from the instance within 60 s");
            solve.cancel(true);
            return;
        }

        Thread.sleep(3_000L);

        if (solve.isDone()) {
            InstanceCheck.info("The hard MIP was solved before it could be cancelled, so there was nothing to abort");
            return;
        }

        InstanceCheck.check("a solve can be cancelled through its Future – a hard MIP, cancel(true) after 3 s", solve.cancel(true), null);

        String last = null;
        for (int attempt = 0; attempt < 15; attempt++) {
            Thread.sleep(1_000L);
            try {
                last = client.pollResult(key);
            } catch (IOException cause) {
                last = cause.getMessage();
            }
            if ("DONE".equals(InstanceCheck.field(last, "status"))) {
                break;
            }
        }

        boolean aborted = "DONE".equals(InstanceCheck.field(last, "status")) && !last.contains("\"result\"");
        InstanceCheck.check("the cancelled solve ended on the instance, without a result", aborted, aborted ? null : last);

        if (!aborted) {
            InstanceCheck.info(
                    "An instance that runs as several can route the abort to another of them when busy. This model ends" + " by itself within a minute or so.");
        }
    }

    private static void check(final String name, final boolean passed, final String detail) {
        if (!passed) {
            FAILURES++;
        }
        System.out.println((passed ? "PASS  " : "FAIL  ") + name + (detail != null ? " – " + detail : ""));
    }

    /**
     * Prints what the instance says about itself.
     */
    private static void describe(final Environment environment, final boolean all) {

        if (all) {
            InstanceCheck.info("Build " + InstanceCheck.field(environment.json, "commit") + ", built " + InstanceCheck.field(environment.json, "built"));
            InstanceCheck.info("Runs on " + InstanceCheck.field(environment.json, "runtime"));
        }

        InstanceCheck.info("Licence: " + environment.licence);
        InstanceCheck.info(environment.solvers + " solvers – " + (environment.nativeSolvers ? "the full suite" : "ojAlgo's own only"));

        if ("false".equals(InstanceCheck.field(environment.json, "probed"))) {
            InstanceCheck.info("It has only just started, and is still finding out which solvers work – run this again in a minute");
        }

        if (all) {
            InstanceCheck.info("Solves " + InstanceCheck.field(environment.json, "workers") + " models at a time, with " + environment.threads
                    + " threads each – now running " + InstanceCheck.field(environment.json, "running") + ", queued "
                    + InstanceCheck.field(environment.json, "queued"));
        }
    }

    /**
     * A string, number or boolean field of a JSON object, or null.
     */
    private static String field(final String json, final String name) {

        if (json == null) {
            return null;
        }

        Matcher matcher = Pattern.compile("\"" + Pattern.quote(name) + "\"\\s*:\\s*(?:\"((?:[^\"\\\\]|\\\\.)*)\"|(-?[0-9.]+|true|false))").matcher(json);

        if (!matcher.find()) {
            return null;
        }

        return matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
    }

    private static void finish() {

        System.out.println();
        if (FAILURES == 0) {
            System.out.println("All checks passed.");
        } else {
            System.out.println(FAILURES + " check(s) FAILED.");
        }

        System.exit(FAILURES == 0 ? 0 : 1);
    }

    private static void info(final String message) {
        System.out.println("INFO  " + message);
    }

    /**
     * Six items, one knapsack of capacity 12: the most it can carry is worth 28.
     */
    private static void knapsack(final OptClientV1 client) throws Exception {

        int[] weights = { 2, 3, 6, 7, 5, 4 };
        int[] values = { 6, 8, 12, 14, 11, 9 };

        OptModel model = client.newModel();
        OptConstraint capacity = model.newConstraint("capacity").upper(12);

        for (int i = 0; i < weights.length; i++) {
            OptVariable item = model.newBinaryVariable("item" + i);
            capacity.set(item, weights[i]);
            model.getObjective().set(item, values[i]);
        }

        InstanceCheck.solve(model, "MIP, knapsack", true, BigDecimal.valueOf(28));
    }

    /**
     * Maximises the sum of {@code n} variables in [0, 1] whose sum is at most {@code n / 2}, and checks
     * whether it was solved, or refused for its size.
     */
    private static void lp(final OptClientV1 client, final String name, final int n, final boolean solvable) throws Exception {

        OptModel model = client.newModel();
        OptConstraint half = model.newConstraint("half").upper(n / 2);

        for (int i = 0; i < n; i++) {
            OptVariable x = model.newRealVariable("x" + i).lower(0.0).upper(1.0);
            half.set(x, 1L);
            model.getObjective().set(x, 1L);
        }

        InstanceCheck.solve(model, name, solvable, BigDecimal.valueOf(n / 2));
    }

    /**
     * A market split problem (Cornuéjols and Dawande): 4 equations in 30 binary variables, with slack, the
     * total slack minimised. Small, but hard for branch and bound: it runs for a few seconds or more, long
     * enough to be aborted, and ends by itself within a minute or so.
     */
    private static OptModel marketSplit(final OptClientV1 client) {

        Random random = new Random(1L);

        OptModel model = client.newModel();

        List<OptVariable> x = new ArrayList<>();
        for (int j = 0; j < 30; j++) {
            x.add(model.newBinaryVariable("x" + j));
        }

        for (int i = 0; i < 4; i++) {

            OptConstraint row = model.newConstraint("split" + i);
            long sum = 0L;

            for (OptVariable variable : x) {
                long a = random.nextInt(100);
                row.set(variable, a);
                sum += a;
            }

            OptVariable over = model.newRealVariable("over" + i).lower(0.0);
            OptVariable under = model.newRealVariable("under" + i).lower(0.0);

            row.set(over, -1L).set(under, 1L).level(sum / 2);
            model.getObjective().set(over, 1L).set(under, 1L);
        }

        return model;
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
                data = data == null ? new StringBuilder(value) : data.append('\n').append(value);
            }
        }

        return data != null ? data.toString() : null;
    }

    private static HttpRequest.Builder request(final String url, final String key) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(SOLVE_TIMEOUT_SECONDS));
        return key != null ? builder.header("Authorization", "Bearer " + key) : builder;
    }

    private static void solve(final OptModel model, final String name, final boolean solvable, final BigDecimal optimum)
            throws Exception {

        long started = System.nanoTime();
        OptResult result;
        try {
            result = model.maximise().get(SOLVE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (ExecutionException cause) {
            InstanceCheck.check(name, false, cause.getCause().toString());
            return;
        }
        double seconds = (System.nanoTime() - started) / 1e9;

        if (solvable) {
            boolean right = result.isOptimal() && result.getValue() != null && result.getValue().subtract(optimum).abs().compareTo(new BigDecimal("1e-6")) <= 0;
            InstanceCheck.check(name + ": solved", right, "value " + result.getValue() + String.format(", %.1f s", seconds));
        } else {
            InstanceCheck.check(name + ": refused", !result.isFeasible(), result.isFeasible() ? "solved, value " + result.getValue() : "no solution");
        }
    }

    /**
     * The solvers listed in the environment.
     */
    private static List<String> solvers(final String json) {

        List<String> retVal = new ArrayList<>();

        Matcher array = Pattern.compile("\"available\"\\s*:\\s*\\[([^\\]]*)\\]").matcher(json != null ? json : "");

        if (array.find()) {
            Matcher entry = Pattern.compile("\"([^\"]+)\"").matcher(array.group(1));
            while (entry.find()) {
                retVal.add(entry.group(1));
            }
        }

        return retVal;
    }

    /**
     * Submits a hard model asking for a stream, as this client does, and times the first status. Then aborts
     * it by its key, with the same cookies – the way to reach the same instance of a service that runs as
     * several.
     */
    private static void stream(final String host, final String key) throws Exception {

        HttpClient http = HttpClient.newBuilder().cookieHandler(new CookieManager()).connectTimeout(Duration.ofSeconds(10L)).build();

        byte[] model = InstanceCheck.marketSplit(new OptClientV1(URI.create(host))).exportModel("EBM").readAllBytes();

        HttpRequest submit = InstanceCheck.request(host + "/optimisation/v1/put-on-queue/EBM/MIN", key).header("Accept", EVENT_STREAM)
                .POST(HttpRequest.BodyPublishers.ofByteArray(model)).build();

        long started = System.nanoTime();
        HttpResponse<InputStream> response = http.send(submit, HttpResponse.BodyHandlers.ofInputStream());

        boolean streamed = response.statusCode() == 200
                && response.headers().firstValue("Content-Type").map(type -> type.startsWith(EVENT_STREAM)).orElse(false);

        String first;
        try (BufferedReader body = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            if (streamed) {
                first = InstanceCheck.nextEvent(body);
            } else {
                StringBuilder all = new StringBuilder();
                for (String line = body.readLine(); line != null; line = body.readLine()) {
                    all.append(line);
                }
                first = all.toString();
            }
        } // Closed: from here the solve is followed by nobody, and aborted below.

        double seconds = (System.nanoTime() - started) / 1e9;
        String solve = InstanceCheck.field(first, "key");

        if (solve == null) {
            InstanceCheck.check("submitted", false, "HTTP " + response.statusCode() + ", " + first);
            return;
        }

        if (streamed) {
            InstanceCheck.check(String.format("streams the status: the first came after %.1f s", seconds), seconds < FIRST_EVENT_SECONDS,
                    seconds < FIRST_EVENT_SECONDS ? null
                            : "something between you and the instance holds the stream back, a buffering proxy say, so a client waits for the"
                                    + " whole solve before it gets the key");
        } else {
            InstanceCheck.info("The instance answers once rather than streaming, so the client polls instead – after " + String.format("%.1f s", seconds));
        }

        HttpRequest abort = InstanceCheck.request(host + "/optimisation/v1/abort/" + solve, key).POST(HttpRequest.BodyPublishers.noBody()).build();
        HttpResponse<String> aborted = http.send(abort, HttpResponse.BodyHandlers.ofString());

        boolean done = aborted.statusCode() == 200 && "DONE".equals(InstanceCheck.field(aborted.body(), "status"));
        InstanceCheck.check("a solve can be aborted by its key – a hard MIP, aborted as soon as it was queued", done, done ? null : "HTTP " + aborted.statusCode() + " " + aborted.body());
    }

    private InstanceCheck() {}

}
