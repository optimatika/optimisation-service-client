package se.optimatika.optimisation.service.client;

import java.net.URI;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.ojalgo.TestUtils;
import org.ojalgo.optimisation.ExpressionsBasedModel;

public class OptimisationServiceClientTest {

    private static final String HOST = "http://13.60.238.124:8080";
    // private static final String HOST = "http://localhost:8080";
    // private static final String HOST = "http://test-service.optimatika.se:8080";

    static final boolean DEBUG = false;

    @AfterEach
    public void clearIntegrations() {
        ExpressionsBasedModel.clearIntegrations();
    }

    @Test
    public void testIsServiceAvailable() {

        OptModel.configure(URI.create(HOST));

        TestUtils.assertTrue(OptModel.isServiceAvailable());
    }

    @Test
    public void testVeryBasicModel() {

        OptModel.configure(URI.create(HOST));

        OptModel model = new OptModel();

        OptVariable varA = model.newRealVariable("A").lower(0);
        OptVariable varB = model.newRealVariable("B").lower(0);

        model.newConstraint("UM2").set(varA, 1).set(varB, 1).level(2);

        model.objective().set(varA, 10).set(varB, -10);

        TestUtils.assertTrue(model.maximise().isOptimal());
        TestUtils.assertEquals(2.0, varA.doubleValue());
        TestUtils.assertEquals(0.0, varB.doubleValue());

        TestUtils.assertTrue(model.minimise().isOptimal());
        TestUtils.assertEquals(0.0, varA.doubleValue());
        TestUtils.assertEquals(2.0, varB.doubleValue());
    }

}
