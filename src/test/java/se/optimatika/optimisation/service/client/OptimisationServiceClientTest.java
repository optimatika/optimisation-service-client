package se.optimatika.optimisation.service.client;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.ojalgo.TestUtils;
import org.ojalgo.netio.BasicLogger;
import org.ojalgo.optimisation.ExpressionsBasedModel;

public class OptimisationServiceClientTest {


    // private static final String HOST = "http://optimatika-service-296829693201.europe-north1.run.app";
    // private static final String HOST = "http://16.16.99.66:8080";
    // private static final String HOST = "http://localhost:8080";
    private static final String HOST = "https://optimatika-boot-services-969062758986.europe-north1.run.app";

    static final boolean DEBUG = false;

    @AfterEach
    public void clearIntegrations() {
        ExpressionsBasedModel.clearIntegrations();
    }

    /**
     * 1s -> 30-40s
     */
    @Test
    public void testFlugpl() {

        OptModel.configure(URI.create(HOST));

        try (InputStream input = TestUtils.getResource("optimisation", "miplib", "flugpl.mps")) {

            OptModel model = OptModel.parse(input, ExpressionsBasedModel.FileFormat.MPS);

            Future<OptResult> minimise = model.minimise();

            OptResult result = minimise.get();

            TestUtils.assertEquals(true, result.isFeasible());
            TestUtils.assertEquals(true, result.isOptimal());
            TestUtils.assertEquals(new BigDecimal("1201500"), result.getValue());

        } catch (IOException | InterruptedException | ExecutionException cause) {
            TestUtils.fail(cause);
        }
    }

    /**
     * 1s -> 2-12s
     */
    @Test
    public void testGr4x6() {

        OptModel.configure(URI.create(HOST));

        try (InputStream input = TestUtils.getResource("optimisation", "miplib", "gr4x6.mps")) {

            OptModel model = OptModel.parse(input, ExpressionsBasedModel.FileFormat.MPS);

            Future<OptResult> minimise = model.minimise();

            OptResult result = minimise.get();

            TestUtils.assertEquals(true, result.isFeasible());
            TestUtils.assertEquals(true, result.isOptimal());
            TestUtils.assertEquals(new BigDecimal("202.35"), result.getValue());

        } catch (IOException | InterruptedException | ExecutionException cause) {
            TestUtils.fail(cause);
        }
    }

    /**
     * 0s -> 0s
     */
    @Test
    public void testIsServiceAvailable() {

        OptModel.configure(URI.create(HOST));

        TestUtils.assertTrue(OptModel.isServiceAvailable());

        BasicLogger.debug(OptModel.getServiceEnvironment());
    }

    /**
     * 20s ->
     */
    @Disabled
    @Test
    public void testMarkshare4() {

        OptModel.configure(URI.create(HOST));

        try (InputStream input = TestUtils.getResource("optimisation", "miplib", "markshare_4_0.mps")) {

            OptModel model = OptModel.parse(input, ExpressionsBasedModel.FileFormat.MPS);

            Future<OptResult> minimise = model.minimise();

            OptResult result = minimise.get();

            TestUtils.assertEquals(true, result.isFeasible());
            TestUtils.assertEquals(true, result.isOptimal());
            TestUtils.assertEquals(BigDecimal.ONE, result.getValue());

        } catch (IOException | InterruptedException | ExecutionException cause) {
            TestUtils.fail(cause);
        }
    }

    /**
     * 1min+ ->
     */
    @Disabled
    @Test
    public void testPK1() {

        OptModel.configure(URI.create(HOST));

        try (InputStream input = TestUtils.getResource("optimisation", "miplib", "pk1.mps")) {

            OptModel model = OptModel.parse(input, ExpressionsBasedModel.FileFormat.MPS);

            Future<OptResult> minimise = model.minimise();

            OptResult result = minimise.get();

            TestUtils.assertEquals(true, result.isFeasible());
            TestUtils.assertEquals(true, result.isOptimal());
            TestUtils.assertEquals(BigDecimal.TEN.add(BigDecimal.ONE), result.getValue());

        } catch (IOException | InterruptedException | ExecutionException cause) {
            TestUtils.fail(cause);
        }
    }

    /**
     * 0s -> 0s
     */
    @Test
    public void testVeryBasicModel() throws InterruptedException, ExecutionException {

        OptModel.configure(URI.create(HOST));

        OptModel model = new OptModel();

        OptVariable varA = model.newRealVariable("A").lower(0);
        OptVariable varB = model.newRealVariable("B").lower(0);

        model.newConstraint("UM2").set(varA, 1).set(varB, 1).level(2);

        model.objective().set(varA, 10).set(varB, -10);

        TestUtils.assertTrue(model.maximise().get().isOptimal());
        TestUtils.assertEquals(2.0, varA.doubleValue());
        TestUtils.assertEquals(0.0, varB.doubleValue());

        TestUtils.assertTrue(model.minimise().get().isOptimal());
        TestUtils.assertEquals(0.0, varA.doubleValue());
        TestUtils.assertEquals(2.0, varB.doubleValue());
    }

}
