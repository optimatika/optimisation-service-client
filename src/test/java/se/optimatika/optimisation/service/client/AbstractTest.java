package se.optimatika.optimisation.service.client;

import java.net.URI;

public abstract class AbstractTest {

    static final String HOST = System.getenv("SERVICE_HOST") != null ? System.getenv("SERVICE_HOST") : "https://service-api.optimatika.se";

    public static OptClientV1 newOptClientV1() {
        return new OptClientV1(URI.create(HOST));
    }

}
