package se.optimatika.optimisation.service.client;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * HTTP client wrapper for the Optimisation Service REST API (version {@code v1}).
 * <p>
 * The service uses an asynchronous queue-based protocol:
 * <ol>
 * <li>{@link #putOnQueue(byte[], String, boolean) putOnQueue} — submit a serialised model and receive a queue
 * key</li>
 * <li>{@link #pollResult(String) pollResult} — poll with that key until the status changes from
 * {@code "PENDING"} to {@code "DONE"}</li>
 * </ol>
 * Both methods return raw JSON. The {@code *Parsed} variants ({@link #putOnQueueParsed},
 * {@link #pollResultParsed}) additionally parse the JSON into a {@code Map} and convert the result string to
 * an {@link OptResult}.
 * <p>
 * A submitted model can be abandoned again before it finishes – one at a time with {@link #abort(String)}, or
 * all of them at once with {@link #abortAll()}. Either way the solve ends up {@code "DONE"} without a result.
 * <p>
 * This class can be used standalone for direct HTTP access, or indirectly through {@link OptModel} which adds
 * model building, serialisation, and result polling on top.
 * <p>
 * The method signatures of {@link #putOnQueue} and {@link #pollResult} are compatible with ojAlgo's
 * {@code Optimisation.ModelSubmitter} and {@code Optimisation.ResultPoller} functional interfaces. Use them
 * as method references with {@code Optimisation.Environment.setRemoteSolver(client::putOnQueue,
 * client::pollResult)} to enable {@code ExpressionsBasedModel.submit} to solve via the service.
 * <p>
 * Thread-safe: the underlying {@link java.net.http.HttpClient} is shared and reused for all requests.
 *
 * @see OptModel
 */
public final class OptClientV1 {

    /**
     * Map key for the queue identifier ({@link String}). Present in every response from both
     * {@link #putOnQueueParsed} and {@link #pollResultParsed}. Pass this value to {@link #pollResult} or
     * {@link #pollResultParsed} to check progress and retrieve the solution.
     */
    public static final String KEY = "key";
    /**
     * Map key for the number of already running solves that {@link #abortAll()} stopped ({@link Integer}).
     * Present only in the map returned by {@link #abortAll()}.
     */
    public static final String ONGOING = "ongoing";
    /**
     * Map key for the number of not yet started solves that {@link #abortAll()} took off the queue
     * ({@link Integer}). Present only in the map returned by {@link #abortAll()}.
     */
    public static final String QUEUED = "queued";
    /**
     * Map key for the parsed solver result ({@link OptResult}). Present only in maps returned by
     * {@link #pollResultParsed} (or {@link #putOnQueueParsed} if the solver completes immediately) when the
     * status is {@code "DONE"} and the server included a result string.
     */
    public static final String RESULT = "result";
    /**
     * Map key for the solver status ({@link String}). Always present. The value is {@code "PENDING"} while
     * the solver is still working, or {@code "DONE"} when the solution is ready.
     */
    public static final String STATUS = "status";

    private static final String ABORT = "/optimisation/v1/abort/";

    private static final String ABORT_ALL = "/optimisation/v1/abort-all";
    private static final HttpResponse.BodyHandler<String> BODY_HANDLER = HttpResponse.BodyHandlers.ofString();
    private static final String PATH_ENVIRONMENT = "/optimisation/v1/environment";
    /** Not under {@code /optimisation/v1/} – liveness is the server's, not this API's. */
    private static final String PATH_HEALTH = "/health";
    private static final String POLL_RESULT = "/optimisation/v1/poll-result/";
    private static final String PUT_ON_QUEUE = "/optimisation/v1/put-on-queue/";
    private static final String TRANSLATE = "/optimisation/v1/translate/";

    /**
     * Convenience factory that parses the given string as a {@link URI}.
     *
     * @param uri base URI of the optimisation service (scheme + host + port, no path)
     * @see #OptClientV1(URI)
     */
    public static OptClientV1 newInstance(final String uri) {
        return new OptClientV1(URI.create(uri));
    }

    private static void interpretResult(final Map<String, Object> map) {

        Object result = map.get(RESULT);

        if (result != null) {
            OptClientV1.parseResult(result.toString(), map);
        }
    }

    /**
     * Reads one unquoted integer field out of a flat JSON object. {@link #parseResponse} cannot be used for
     * the abort-all response because it assumes every value is a quoted string.
     */
    private static int parseCount(final String json, final String field) {

        int start = json.indexOf('"' + field + "\":");

        if (start < 0) {
            throw new RuntimeException("No '" + field + "' in: " + json);
        }

        int pos = start + field.length() + 3;
        int end = pos;
        while (end < json.length() && Character.isDigit(json.charAt(end))) {
            end++;
        }

        return Integer.parseInt(json.substring(pos, end));
    }

    private static Map<String, Object> parseResponse(final String json) {

        Map<String, Object> map = new LinkedHashMap<>();

        int pos = 0;
        while (true) {
            int keyStart = json.indexOf('"', pos);
            if (keyStart < 0) {
                break;
            }
            int keyEnd = json.indexOf('"', keyStart + 1);
            String fieldName = json.substring(keyStart + 1, keyEnd);
            int valStart = json.indexOf('"', keyEnd + 1);
            int valEnd = json.indexOf('"', valStart + 1);
            map.put(fieldName, json.substring(valStart + 1, valEnd));
            pos = valEnd + 1;
        }
        return map;
    }

    private static void parseResult(final String result, final Map<String, Object> map) {

        int firstSpace = result.indexOf(' ');
        int atMark = result.indexOf(" @ ");

        String state = result.substring(0, firstSpace);
        BigDecimal value = new BigDecimal(result.substring(firstSpace + 1, atMark));

        String solutionPart = result.substring(atMark + 5, result.length() - 2);
        String[] parts = solutionPart.split(", ");
        List<BigDecimal> solution = new ArrayList<>(parts.length);
        for (String part : parts) {
            solution.add(new BigDecimal(part));
        }

        map.put(RESULT, new OptResult(state, value, solution));
    }

    private final HttpClient myClient;
    private final String myHost;

    /**
     * @param host base URI of the optimisation service (scheme + host + port, no path), e.g.
     *             {@code URI.create("https://example.com")}
     * @throws IllegalArgumentException if the URI contains a path
     */
    public OptClientV1(final URI host) {

        Objects.requireNonNull(host);

        if (host.getPath().length() > 0) {
            throw new IllegalArgumentException("The URI path must be empty!");
        }

        myHost = host.toASCIIString();
        myClient = HttpClient.newBuilder().cookieHandler(new CookieManager()).build();
    }

    /**
     * Abandons one queued or ongoing solve. Returns the raw JSON response body, which has the same shape as
     * {@link #pollResult(String)} and reports the solve as {@code "DONE"} without a {@code "result"} – the
     * partial solution the solver had reached is discarded.
     * <p>
     * Only the named solve is affected. Other solves, whether already running or still waiting in the queue,
     * are left alone.
     * <p>
     * Aborting a solve that has already finished is a no-op: it stays {@code "DONE"} and keeps its result.
     *
     * @param key the queue identifier returned by {@link #putOnQueue}
     * @return the raw JSON response body, or {@code null} if the server does not know this key, which
     *         includes keys whose results have expired
     * @throws IOException          if the HTTP request fails
     * @throws InterruptedException if the thread is interrupted while waiting for the response
     * @see #abortParsed(String)
     */
    public String abort(final String key) throws IOException, InterruptedException {

        URI uri = URI.create(myHost + ABORT + key);

        HttpRequest request = HttpRequest.newBuilder().uri(uri).POST(HttpRequest.BodyPublishers.noBody()).build();

        HttpResponse<String> response = myClient.send(request, BODY_HANDLER);

        if (response.statusCode() == 404) {
            return null;
        }

        return response.body();
    }

    /**
     * Purges the queue and aborts every ongoing solve – {@link #abort(String)} applied to everything the
     * server currently holds. Solves that have already finished keep their results.
     * <p>
     * The returned map has two entries, both {@link Integer}:
     * <ul>
     * <li>{@link #QUEUED} — how many solves were taken off the queue without ever starting</li>
     * <li>{@link #ONGOING} — how many solves were already running and were interrupted</li>
     * </ul>
     * This affects every client of the server, not just this one.
     *
     * @return the queued and ongoing counts abandoned
     * @throws RuntimeException if the request fails or the response cannot be read
     */
    public Map<String, Object> abortAll() {

        try {

            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(myHost + ABORT_ALL)).POST(HttpRequest.BodyPublishers.noBody()).build();

            HttpResponse<String> response = myClient.send(request, BODY_HANDLER);

            if (response.statusCode() != 200) {
                throw new RuntimeException("Abort-all failed with status " + response.statusCode());
            }

            String body = response.body();

            Map<String, Object> retVal = new LinkedHashMap<>();
            retVal.put(QUEUED, Integer.valueOf(OptClientV1.parseCount(body, QUEUED)));
            retVal.put(ONGOING, Integer.valueOf(OptClientV1.parseCount(body, ONGOING)));

            return retVal;

        } catch (IOException | InterruptedException cause) {
            throw new RuntimeException(cause);
        }
    }

    /**
     * Calls {@link #abort(String)} and parses the JSON response into the same map that
     * {@link #pollResultParsed} returns – {@link #KEY} and {@link #STATUS}, and {@link #RESULT} only if the
     * solve had already finished with one before the abort arrived.
     * <p>
     * Returns an empty map if the server does not know the key, or on a network or interruption error.
     *
     * @param key the queue identifier returned by {@link #putOnQueue}
     * @return a parsed map of the server response, or an empty map
     * @see #abort(String)
     */
    public Map<String, Object> abortParsed(final String key) {

        try {

            String body = this.abort(key);

            if (body == null) {
                return Map.of();
            }

            Map<String, Object> retVal = OptClientV1.parseResponse(body);

            OptClientV1.interpretResult(retVal);

            return retVal;

        } catch (IOException | InterruptedException cause) {

            return Map.of();
        }
    }

    /**
     * Queries the server's {@code /environment} endpoint and returns its description of itself, as raw JSON:
     * the build it was packaged from, the runtime it is on (memory, threads, architecture), what its licence
     * permits, and which solvers it has.
     * <p>
     * The solver list is what that deployment can actually do – it is deliberately not fixed across
     * deployments, and {@code solvers.probed} is false while the server is still working it out.
     * <p>
     * Returns {@code "?"} if the server is unreachable.
     */
    public String getServiceEnvironment() {

        try {

            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(myHost + PATH_ENVIRONMENT)).GET().build();

            HttpResponse<String> response = myClient.send(request, BODY_HANDLER);

            return response.body();

        } catch (Exception cause) {
            return "?";
        }
    }


    /**
     * Checks that the service is reachable and does not consider itself in need of a restart, by calling its
     * {@code /health} endpoint. That endpoint answers with a status code and no body.
     * <p>
     * Note what this does not tell you. Health reports only what restarting the server would repair, so a
     * deployment with no native solvers, without a licence key, or still probing its solvers is reported
     * available – it is working, just not with everything. Ask {@link #getServiceEnvironment()} what it
     * actually has.
     * <p>
     * Returns {@code false} on any error or unexpected response.
     */
    public boolean isServiceAvailable() {

        try {

            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(myHost + PATH_HEALTH)).GET().build();

            HttpResponse<String> response = myClient.send(request, BODY_HANDLER);

            int status = response.statusCode();

            return status >= 200 && status < 300;

        } catch (Exception cause) {
            return false;
        }
    }

    /**
     * Creates a new {@link OptModel} backed by this client. The model provides a builder API for defining
     * variables, constraints, and an objective, then submitting to the service via
     * {@link OptModel#minimise()} or {@link OptModel#maximise()}.
     */
    public OptModel newModel() {
        return new OptModel(this);
    }

    /**
     * Polls for the result of a previously submitted model. Returns the raw JSON response body.
     * <p>
     * While the solver is still working the response looks like:
     *
     * <pre>
     * {
     *   "key":"PmkvX3SNQ0gjRtCD",
     *   "status":"PENDING"
     * }
     * </pre>
     *
     * When done:
     *
     * <pre>
     * {
     *   "key":"PmkvX3SNQ0gjRtCD",
     *   "status":"DONE",
     *   "result":"OPTIMAL 13.0 @ { 0, 1, 0, 0, 1, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1 }"
     * }
     * </pre>
     *
     * The {@code "result"} string follows the format produced by {@code Optimisation.Result.toString()} and
     * can be parsed with {@code Optimisation.Result.parse(String)}.
     * <p>
     * This method's signature is compatible with ojAlgo's {@code Optimisation.ResultPoller} functional
     * interface.
     *
     * @param key the queue identifier returned by {@link #putOnQueue}
     * @return the raw JSON response body
     * @throws IOException          if the HTTP request fails
     * @throws InterruptedException if the thread is interrupted while waiting for the response
     * @see #pollResultParsed(String)
     */
    public String pollResult(final String key) throws IOException, InterruptedException {

        String uri = myHost + POLL_RESULT + key;

        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(uri)).GET().build();

        HttpResponse<String> response = myClient.send(request, BODY_HANDLER);

        return response.body();
    }

    /**
     * Calls {@link #pollResult(String)} and parses the JSON response into a map with the following entries:
     * <ul>
     * <li>{@link #KEY} ({@link String}) — the queue identifier (always present)</li>
     * <li>{@link #STATUS} ({@link String}) — {@code "PENDING"} or {@code "DONE"} (always present)</li>
     * <li>{@link #RESULT} ({@link OptResult}) — the parsed solver result (present only when status is
     * {@code "DONE"} and the server included a result string)</li>
     * </ul>
     * On network or interruption errors, returns a map with just {@link #KEY} and {@link #STATUS}
     * ({@code "DONE"}) and no {@link #RESULT}.
     *
     * @param key the queue identifier returned by {@link #putOnQueue}
     * @return a parsed map of the server response
     * @see #pollResult(String)
     */
    public Map<String, Object> pollResultParsed(final String key) {

        try {

            String body = this.pollResult(key);

            Map<String, Object> retVal = OptClientV1.parseResponse(body);

            OptClientV1.interpretResult(retVal);

            return retVal;

        } catch (IOException | InterruptedException cause) {

            return Map.of(KEY, key, STATUS, "DONE");
        }
    }

    /**
     * Submits a serialised model to the solver queue. Returns the raw JSON response body.
     * <p>
     * A typical response:
     *
     * <pre>
     * {
     *   "key":"PmkvX3SNQ0gjRtCD",
     *   "status":"PENDING"
     * }
     * </pre>
     *
     * The {@code "key"} value is the queue identifier to pass to {@link #pollResult(String)}. The
     * {@code "status"} is normally {@code "PENDING"} but may be {@code "DONE"} if the solver completes
     * immediately (in which case a {@code "result"} field is also present).
     * <p>
     * This method's signature is compatible with ojAlgo's {@code Optimisation.ModelSubmitter} functional
     * interface.
     *
     * @param data     the serialised model bytes (EBM or MPS format)
     * @param format   the model format: {@code "EBM"} or {@code "MPS"}
     * @param maximize {@code true} to maximise, {@code false} to minimise
     * @return the raw JSON response body
     * @throws IOException          if the HTTP request fails
     * @throws InterruptedException if the thread is interrupted while waiting for the response
     * @see #putOnQueueParsed(byte[], String, boolean)
     */
    public String putOnQueue(final byte[] data, final String format, final boolean maximize) throws IOException, InterruptedException {

        URI uri = URI.create(myHost + PUT_ON_QUEUE + format + "/" + (maximize ? "MAX" : "MIN"));

        HttpRequest request = HttpRequest.newBuilder().uri(uri).POST(HttpRequest.BodyPublishers.ofByteArray(data)).build();

        HttpResponse<String> response = myClient.send(request, BODY_HANDLER);

        return response.body();
    }

    /**
     * Calls {@link #putOnQueue(byte[], String, boolean)} and parses the JSON response into a map with the
     * following entries:
     * <ul>
     * <li>{@link #KEY} ({@link String}) — the queue identifier to pass to {@link #pollResultParsed} (always
     * present)</li>
     * <li>{@link #STATUS} ({@link String}) — {@code "PENDING"} or {@code "DONE"} (always present)</li>
     * <li>{@link #RESULT} ({@link OptResult}) — present only if the solver completed immediately</li>
     * </ul>
     * On network or interruption errors, returns a map with just {@link #STATUS} ({@code "DONE"}) and no
     * {@link #KEY} or {@link #RESULT}.
     *
     * @param data     the serialised model bytes (EBM or MPS format)
     * @param format   the model format: {@code "EBM"} or {@code "MPS"}
     * @param maximize {@code true} to maximise, {@code false} to minimise
     * @return a parsed map of the server response
     * @see #putOnQueue(byte[], String, boolean)
     */
    public Map<String, Object> putOnQueueParsed(final byte[] data, final String format, final boolean maximize) {

        try {

            String body = this.putOnQueue(data, format, maximize);

            Map<String, Object> retVal = OptClientV1.parseResponse(body);

            OptClientV1.interpretResult(retVal);

            return retVal;

        } catch (IOException | InterruptedException cause) {

            return Map.of(STATUS, "DONE");
        }
    }

    /**
     * Translates a model from one file format to another.
     *
     * @param data         the serialised model bytes in the input format
     * @param inputFormat  the format of the input data: {@code "EBM"}, {@code "LP"}, or {@code "MPS"}
     * @param outputFormat the desired output format: {@code "EBM"}, {@code "LP"}, or {@code "MPS"}
     * @return the model serialised in the output format
     */
    public byte[] translate(final byte[] data, final String inputFormat, final String outputFormat) {

        try {

            URI uri = URI.create(myHost + TRANSLATE + inputFormat.toUpperCase() + "/" + outputFormat.toUpperCase());

            HttpRequest request = HttpRequest.newBuilder().uri(uri).POST(HttpRequest.BodyPublishers.ofByteArray(data)).build();

            HttpResponse<byte[]> response = myClient.send(request, HttpResponse.BodyHandlers.ofByteArray());

            if (response.statusCode() != 200) {
                throw new RuntimeException("Translation failed with status " + response.statusCode());
            }

            return response.body();

        } catch (IOException | InterruptedException cause) {
            throw new RuntimeException(cause);
        }
    }

}
