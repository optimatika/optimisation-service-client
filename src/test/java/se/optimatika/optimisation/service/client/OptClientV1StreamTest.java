package se.optimatika.optimisation.service.client;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * How {@link OptClientV1} follows a solve over a stream, against a stand-in for the service that does
 * exactly what each test tells it to – stream or not, cut the connection or not, know the key or not. That
 * the real service streams as expected is tested in the server repository.
 */
@Timeout(30)
class OptClientV1StreamTest {

    /**
     * Answers {@code put-on-queue} and {@code poll-result} for one solve, {@link #KEY}, and counts the
     * polls it gets.
     */
    private static final class Service {

        /** Opened to let a held submit stream send the result. */
        final CountDownLatch finish = new CountDownLatch(1);
        final AtomicInteger polls = new AtomicInteger();

        private final boolean myCut;
        private final ExecutorService myExecutor = Executors.newCachedThreadPool();
        private final boolean myKnown;
        private final HttpServer myServer;
        private final boolean myStreams;

        /**
         * @param streams Whether it answers {@code Accept: text/event-stream} with a stream – false is a
         *                service from before streaming
         * @param cut     Whether a submit stream is ended after its first event, as a timeout would
         * @param known   Whether {@code poll-result} knows the key, or answers 404
         */
        Service(final boolean streams, final boolean cut, final boolean known) throws IOException {

            myStreams = streams;
            myCut = cut;
            myKnown = known;

            myServer = HttpServer.create(new InetSocketAddress(0), 0);
            myServer.setExecutor(myExecutor);
            myServer.createContext("/optimisation/v1/put-on-queue/", this::submit);
            myServer.createContext("/optimisation/v1/poll-result/", this::poll);
            myServer.start();
        }

        OptClientV1 client() {
            return new OptClientV1(URI.create("http://localhost:" + myServer.getAddress().getPort()));
        }

        void stop() {
            finish.countDown();
            myServer.stop(0);
            myExecutor.shutdownNow();
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
                    if (!myCut && finish.await(20, TimeUnit.SECONDS)) {
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

    }

    private static final String KEY = "K1";
    private static final String DONE = "{\"key\":\"" + KEY + "\",\"status\":\"DONE\",\"result\":\"OPTIMAL 1.0 @ { 1.0 }\"}";
    private static final String PENDING = "{\"key\":\"" + KEY + "\",\"status\":\"PENDING\"}";
    private static final byte[] MODEL = "model".getBytes(StandardCharsets.UTF_8);

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
