package se.optimatika.optimisation.service.client;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * How {@link OptClientV1} follows a solve over a stream, against a stand-in for the service that does exactly
 * what each test tells it to – stream or not, cut the connection or not, know the key or not. These run
 * without a service; the examples and {@link OptClientV1Test} run against a real one.
 */
@Timeout(30)
class OptClientV1StreamTest {

    /**
     * Answers {@code put-on-queue} and {@code poll-result} for one solve, {@link #KEY}, and counts the polls
     * it gets.
     */
    private static final class Service {

        private static void respondJson(final HttpExchange exchange, final String json) throws IOException {
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
        }

        private static void sendEvent(final OutputStream out, final String json) throws IOException {
            out.write(("data: " + json + "\n\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
        }

        private static OutputStream startStream(final HttpExchange exchange) throws IOException {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            return exchange.getResponseBody();
        }

        private final boolean myCut;
        private final ExecutorService myExecutor = Executors.newCachedThreadPool();
        private final boolean myHeartbeats;
        private final boolean myKnown;
        private final HttpServer myServer;
        private final boolean myStreams;

        /** Opened to let a held submit stream send the result. */
        final CountDownLatch finish = new CountDownLatch(1);

        final AtomicInteger polls = new AtomicInteger();

        /** How many abort requests it got. */
        final AtomicInteger aborts = new AtomicInteger();

        /** How many aborts answer 404 – as from another instance – before one is answered. */
        volatile int abortMisses = 0;

        /** Opened when a write to a submit stream with heartbeats fails: the client has closed it. */
        final CountDownLatch streamClosed = new CountDownLatch(1);

        Service(final boolean streams, final boolean cut, final boolean known) throws IOException {
            this(streams, cut, known, false);
        }

        /**
         * @param streams    Whether it answers {@code Accept: text/event-stream} with a stream – false is a
         *                   service from before streaming
         * @param cut        Whether a submit stream is ended after its first event, as a timeout would
         * @param known      Whether {@code poll-result} and {@code abort} know the key, or answer 404
         * @param heartbeats Whether a submit stream sends {@code PENDING} every 50 ms until the client closes
         *                   it, instead of waiting for {@link #finish}
         */
        Service(final boolean streams, final boolean cut, final boolean known, final boolean heartbeats) throws IOException {

            myStreams = streams;
            myCut = cut;
            myKnown = known;
            myHeartbeats = heartbeats;

            myServer = HttpServer.create(new InetSocketAddress(0), 0);
            myServer.setExecutor(myExecutor);
            myServer.createContext("/optimisation/v1/put-on-queue/", this::submit);
            myServer.createContext("/optimisation/v1/poll-result/", this::poll);
            myServer.createContext("/optimisation/v1/abort/", this::abort);
            myServer.start();
        }

        private void abort(final HttpExchange exchange) throws IOException {
            try {
                exchange.getRequestBody().readAllBytes();
                if (myKnown && aborts.incrementAndGet() > abortMisses) {
                    Service.respondJson(exchange, "{\"key\":\"" + KEY + "\",\"status\":\"DONE\"}");
                } else {
                    exchange.sendResponseHeaders(404, -1);
                }
            } finally {
                exchange.close();
            }
        }

        private boolean isStreamRequested(final HttpExchange exchange) {
            List<String> accept = exchange.getRequestHeaders().get("Accept");
            return myStreams && accept != null && accept.stream().anyMatch(value -> value.contains("text/event-stream"));
        }

        private void poll(final HttpExchange exchange) throws IOException {

            int count = polls.incrementAndGet();

            try {
                if (!myKnown) {
                    exchange.sendResponseHeaders(404, -1);
                } else if (this.isStreamRequested(exchange)) {
                    OutputStream out = Service.startStream(exchange);
                    Service.sendEvent(out, DONE);
                } else {
                    Service.respondJson(exchange, count < 3 ? PENDING : DONE);
                }
            } finally {
                exchange.close();
            }
        }

        private void submit(final HttpExchange exchange) throws IOException {

            exchange.getRequestBody().readAllBytes();

            try {
                if (this.isStreamRequested(exchange)) {
                    OutputStream out = Service.startStream(exchange);
                    Service.sendEvent(out, PENDING);
                    if (myHeartbeats) {
                        try {
                            while (!finish.await(50, TimeUnit.MILLISECONDS)) {
                                Service.sendEvent(out, PENDING);
                            }
                        } catch (IOException cause) {
                            streamClosed.countDown();
                        }
                    } else if (!myCut && finish.await(20, TimeUnit.SECONDS)) {
                        Service.sendEvent(out, DONE);
                    }
                } else {
                    Service.respondJson(exchange, PENDING);
                }
            } catch (InterruptedException cause) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        }

        OptClientV1 client() {
            return new OptClientV1(URI.create("http://localhost:" + myServer.getAddress().getPort()));
        }

        void stop() {
            finish.countDown();
            myServer.stop(0);
            myExecutor.shutdownNow();
        }

    }

    private static final String KEY = "K1";
    private static final String DONE = "{\"key\":\"" + KEY + "\",\"status\":\"DONE\",\"result\":\"OPTIMAL 1.0 @ { 1.0 }\"}";
    private static final byte[] MODEL = "model".getBytes(StandardCharsets.UTF_8);
    private static final String PENDING = "{\"key\":\"" + KEY + "\",\"status\":\"PENDING\"}";

    /**
     * Polls the way ojAlgo's submit loop does, only faster, until the solve is done.
     */
    private static String pollToDone(final OptClientV1 client) throws IOException, InterruptedException {

        String response = client.pollResult(KEY);

        while (response.contains("\"status\":\"PENDING\"")) {
            Thread.sleep(10L);
            response = client.pollResult(KEY);
        }

        return response;
    }

    /**
     * Whether the thread that reads the solve's stream is still running, waiting at most 5 s for it to end.
     */
    private static boolean isReaderRunning() throws InterruptedException {

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5L);

        boolean running = true;

        while (running && System.nanoTime() < deadline) {
            running = Thread.getAllStackTraces().keySet().stream().anyMatch(thread -> thread.getName().equals("optimisation-stream-" + KEY));
            if (running) {
                Thread.sleep(10L);
            }
        }

        return running;
    }

    private Service myService;

    @AfterEach
    void stopService() {
        if (myService != null) {
            myService.stop();
        }
    }

    /**
     * A service from before streaming ignores the header. Every call is then one request, and gets what it
     * always got.
     */
    @Test
    void testServiceWithoutStreams() throws Exception {

        myService = new Service(false, false, true);
        OptClientV1 client = myService.client();

        Assertions.assertEquals(PENDING, client.putOnQueue(MODEL, "EBM", false));

        Assertions.assertEquals(PENDING, client.pollResult(KEY));
        Assertions.assertEquals(PENDING, client.pollResult(KEY));
        Assertions.assertEquals(DONE, client.pollResult(KEY));

        Assertions.assertEquals(3, myService.polls.get());
    }

    /**
     * Submitting returns the key while the solve is still running, and from then on polls are answered from
     * the stream – the service is never polled.
     */
    @Test
    void testStreamAnswersPolls() throws Exception {

        myService = new Service(true, false, true);
        OptClientV1 client = myService.client();

        Assertions.assertEquals(PENDING, client.putOnQueue(MODEL, "EBM", false));

        Assertions.assertEquals(PENDING, client.pollResult(KEY));
        Assertions.assertEquals(PENDING, client.pollResult(KEY));

        myService.finish.countDown();

        Assertions.assertEquals(DONE, OptClientV1StreamTest.pollToDone(client));
        Assertions.assertEquals(0, myService.polls.get());

        // Handed out once. A poll after that asks the service, which is the only one that still knows.
        Assertions.assertEquals(DONE, client.pollResult(KEY));
        Assertions.assertEquals(1, myService.polls.get());
    }

    /**
     * The stream ends before the result – a request timeout, say. The next poll reconnects, and once.
     */
    @Test
    void testStreamCutReconnects() throws Exception {

        myService = new Service(true, true, true);
        OptClientV1 client = myService.client();

        Assertions.assertEquals(PENDING, client.putOnQueue(MODEL, "EBM", false));

        Assertions.assertEquals(DONE, OptClientV1StreamTest.pollToDone(client));
        Assertions.assertEquals(1, myService.polls.get());
    }

    /**
     * An abort that reaches a service instance not solving the model gets 404, and aborts nothing there. The
     * client closes the stream anyway: that is what tells the instance solving it that nobody follows it any
     * longer.
     */
    @Test
    void testAbortMissedClosesStream() throws Exception {

        myService = new Service(true, false, false, true);
        OptClientV1 client = myService.client();

        Assertions.assertEquals(PENDING, client.putOnQueue(MODEL, "EBM", false));

        Assertions.assertNull(client.abort(KEY));

        Assertions.assertTrue(myService.streamClosed.await(5, TimeUnit.SECONDS), "The service never saw the stream closed");
        Assertions.assertFalse(OptClientV1StreamTest.isReaderRunning(), "The stream's reader is still running");
    }

    /**
     * {@code cancel(true)} on {@link OptModel}'s {@link Future} sends the abort again when
     * it misses – here twice, after 1 s and 2 s more – until the instance solving the model answers. It closes
     * the stream at once.
     */
    @Test
    void testCancelRetriesMissedAbort() throws Exception {

        myService = new Service(true, false, true, true);
        myService.abortMisses = 2;
        OptClientV1 client = myService.client();

        OptModel model = client.newModel();
        model.objective().set(model.newRealVariable("x").lower(0.0), 1L);

        OptModel.RemoteSolve solve = (OptModel.RemoteSolve) model.minimise();

        for (int attempt = 0; attempt < 500 && solve.key() == null; attempt++) {
            Thread.sleep(10L);
        }
        Assertions.assertEquals(KEY, solve.key());

        Assertions.assertTrue(solve.cancel(true), "Not cancelled");

        Assertions.assertTrue(myService.streamClosed.await(5, TimeUnit.SECONDS), "The service never saw the stream closed");

        for (int attempt = 0; attempt < 1_000 && myService.aborts.get() < 3; attempt++) {
            Thread.sleep(10L);
        }
        Thread.sleep(OptModel.RemoteSolve.ABORT_FIRST_GAP_MILLIS * 5L);
        Assertions.assertEquals(3, myService.aborts.get());
    }

    /**
     * Through {@link OptModel}, a reconnect that reaches a service that does not know the solve ends the
     * {@link Future} exceptionally, with the {@link IOException}, rather than with a result
     * that looks like a failed solve. Resubmitting is the caller's decision.
     */
    @Test
    void testLostSolveFailsTheFuture() throws Exception {

        myService = new Service(true, true, false);
        OptClientV1 client = myService.client();

        OptModel model = client.newModel();
        model.objective().set(model.newRealVariable("x").lower(0.0), 1L);

        Future<OptResult> future = model.minimise();

        ExecutionException failure = Assertions.assertThrows(ExecutionException.class,
                () -> future.get(10, TimeUnit.SECONDS));
        Assertions.assertTrue(failure.getCause() instanceof IOException, failure.getCause().toString());
    }

    /**
     * Reconnecting reached a service that does not know the solve. That is the caller's to handle.
     */
    @Test
    void testStreamCutUnknownKey() throws Exception {

        myService = new Service(true, true, false);
        OptClientV1 client = myService.client();

        Assertions.assertEquals(PENDING, client.putOnQueue(MODEL, "EBM", false));

        Assertions.assertThrows(IOException.class, () -> OptClientV1StreamTest.pollToDone(client));
    }

}
