package se.optimatika.optimisation.service.client;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

/**
 * A client made with a licence key sends it with every request, as {@code Authorization: Bearer <key>}, and
 * one made without sends no such header. Against a stand-in server that answers every request the same.
 */
class LicenceKeyTest {

    private static final String DONE = "{\"key\":\"k1\",\"status\":\"DONE\",\"result\":\"OPTIMAL 1.0 @ { 1.0 }\"}";
    private static final String KEY = "OPTIMATIKA-11111111-1111-4111-8111-111111111111";

    private final List<String> myAuthorizations = new CopyOnWriteArrayList<>();
    private HttpServer myServer;

    private URI host() {
        return URI.create("http://127.0.0.1:" + myServer.getAddress().getPort());
    }

    /** One request of each kind the client makes. */
    private void useEveryEndpoint(final OptClientV1 client) throws IOException, InterruptedException {
        client.putOnQueue(new byte[] { 1 }, "EBM", false);
        client.pollResult("k1");
        client.abort("k1");
        client.getServiceEnvironment();
        client.isServiceAvailable();
        client.translate(new byte[] { 1 }, "EBM", "MPS");
    }

    @BeforeEach
    void startServer() throws IOException {

        myServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        myServer.createContext("/", exchange -> {
            String authorization = exchange.getRequestHeaders().getFirst("Authorization");
            myAuthorizations.add(authorization != null ? authorization : "none");
            byte[] body = DONE.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        myServer.start();
    }

    @AfterEach
    void stopServer() {
        myServer.stop(0);
    }

    @Test
    void testKeySentWithEveryRequest() throws IOException, InterruptedException {

        this.useEveryEndpoint(new OptClientV1(this.host(), KEY));

        Assertions.assertEquals(6, myAuthorizations.size(), myAuthorizations.toString());
        Assertions.assertTrue(myAuthorizations.stream().allMatch(("Bearer " + KEY)::equals), myAuthorizations.toString());
    }

    @Test
    void testNoKeyNoHeader() throws IOException, InterruptedException {

        this.useEveryEndpoint(OptClientV1.newInstance(this.host().toString()));
        this.useEveryEndpoint(OptClientV1.newInstance(this.host().toString(), " "));

        Assertions.assertEquals(12, myAuthorizations.size(), myAuthorizations.toString());
        Assertions.assertTrue(myAuthorizations.stream().allMatch("none"::equals), myAuthorizations.toString());
    }

}
