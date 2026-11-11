package se.optimatika.optimisation.service.client;

import java.net.URI;

/**
 * Where the tests and examples find an Optimisation Service: Optimatika's public shared instance,
 * https://service-api.optimatika.se, unless the environment variable {@code SERVICE_HOST} names another – for
 * example {@code http://localhost:8080}, for a container of your own. No licence key is sent, so on the
 * shared instance they run with its limits for requests without one.
 */
public abstract class AbstractTest {

    /** The service the tests use: {@code SERVICE_HOST}, or Optimatika's shared instance. */
    static final String HOST = System.getenv("SERVICE_HOST") != null ? System.getenv("SERVICE_HOST") : "https://service-api.optimatika.se";

    public static OptClientV1 newOptClientV1() {
        return new OptClientV1(URI.create(HOST));
    }

}
