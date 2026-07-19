package se.optimatika.optimisation.service.client;

import java.net.URI;

public abstract class AbstractTest {

    static final String HOST = System.getenv("SERVICE_HOST") != null ? System.getenv("SERVICE_HOST")
            : "https://optimisation-test-service-840974723912.europe-north2.run.app";

    public static OptClientV1 newOptClientV1() {
        return new OptClientV1(URI.create(HOST));
    }

}
