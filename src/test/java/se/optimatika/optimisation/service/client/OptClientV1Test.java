package se.optimatika.optimisation.service.client;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class OptClientV1Test {

    private static final String HOST = "https://optimatika-boot-services-969062758986.europe-north1.run.app";

    private static byte[] readResource(final String path) throws IOException {
        try (InputStream is = ClassLoader.getSystemResourceAsStream(path)) {
            return is.readAllBytes();
        }
    }

    private static Map<String, Object> solveViaMps(final OptClientV1 client, final String resourcePath, final boolean maximize) throws Exception {

        byte[] mpsData = OptClientV1Test.readResource(resourcePath);

        Map<String, Object> response = client.putOnQueueParsed(mpsData, "MPS", maximize);
        String key = (String) response.get(OptClientV1.KEY);
        String status = (String) response.get(OptClientV1.STATUS);

        int counter = 0;
        while ("PENDING".equals(status)) {
            Thread.sleep(Math.min(10_000L, 100L * ++counter));
            response = client.pollResultParsed(key);
            status = (String) response.get(OptClientV1.STATUS);
        }

        return response;
    }

    @Test
    public void testClientPutOnQueueAndPollResult() throws Exception {

        OptClientV1 client = new OptClientV1(URI.create(HOST));

        OptModel model = new OptModel(client);

        OptVariable varA = model.newRealVariable("A").lower(0);
        OptVariable varB = model.newRealVariable("B").lower(0);

        model.newConstraint("UM2").set(varA, 1).set(varB, 1).level(2);

        model.objective().set(varA, 10).set(varB, -10);

        Map<String, Object> response = client.putOnQueueParsed(model.exportModel("EBM").readAllBytes(), "EBM", true);
        Assertions.assertTrue(response.containsKey(OptClientV1.KEY));
    }

    @Test
    public void testExportFormats() throws Exception {

        OptClientV1 client = new OptClientV1(URI.create(HOST));

        OptModel model = new OptModel(client);

        OptVariable varA = model.newRealVariable("A").lower(0);
        OptVariable varB = model.newRealVariable("B").lower(0);

        model.newConstraint("UM2").set(varA, 1).set(varB, 1).level(2);

        model.objective().set(varA, 10).set(varB, -10);

        byte[] ebm = model.exportModel("EBM").readAllBytes();
        Assertions.assertTrue(ebm.length > 0);

        byte[] lp = model.exportModel("LP").readAllBytes();
        String lpStr = new String(lp, java.nio.charset.StandardCharsets.UTF_8);
        Assertions.assertTrue(lpStr.contains("obj"));

        byte[] mps = model.exportModel("MPS").readAllBytes();
        String mpsStr = new String(mps, java.nio.charset.StandardCharsets.UTF_8);
        Assertions.assertTrue(mpsStr.contains("ROWS"));
        Assertions.assertTrue(mpsStr.contains("COLUMNS"));
    }

    /**
     * Airline scheduling model (flugpl). 18 variables (6 periods, each with STM/ANM/UE), 18 constraints.
     * Known optimal: 1201500
     */
    @Test
    public void testFlugpl() throws InterruptedException, ExecutionException {

        OptClientV1 client = new OptClientV1(URI.create(HOST));

        OptModel model = new OptModel(client);

        // Period 1: STM1 is real (not integer)
        OptVariable.RealVariable stm1 = model.newRealVariable("STM1");
        OptVariable.IntegerVariable anm1 = model.newIntegerVariable("ANM1").upper(18);
        OptVariable.RealVariable ue1 = model.newRealVariable("UE1");

        // Periods 2-6: STM is integer with bounds [57,75]
        OptVariable.IntegerVariable stm2 = model.newIntegerVariable("STM2").lower(57).upper(75);
        OptVariable.IntegerVariable anm2 = model.newIntegerVariable("ANM2").upper(18);
        OptVariable.RealVariable ue2 = model.newRealVariable("UE2");

        OptVariable.IntegerVariable stm3 = model.newIntegerVariable("STM3").lower(57).upper(75);
        OptVariable.IntegerVariable anm3 = model.newIntegerVariable("ANM3").upper(18);
        OptVariable.RealVariable ue3 = model.newRealVariable("UE3");

        OptVariable.IntegerVariable stm4 = model.newIntegerVariable("STM4").lower(57).upper(75);
        OptVariable.IntegerVariable anm4 = model.newIntegerVariable("ANM4").upper(18);
        OptVariable.RealVariable ue4 = model.newRealVariable("UE4");

        OptVariable.IntegerVariable stm5 = model.newIntegerVariable("STM5").lower(57).upper(75);
        OptVariable.IntegerVariable anm5 = model.newIntegerVariable("ANM5").upper(18);
        OptVariable.RealVariable ue5 = model.newRealVariable("UE5");

        OptVariable.IntegerVariable stm6 = model.newIntegerVariable("STM6").lower(57).upper(75);
        OptVariable.IntegerVariable anm6 = model.newIntegerVariable("ANM6").upper(18);
        OptVariable.RealVariable ue6 = model.newRealVariable("UE6");

        OptVariable[] stm = { stm1, stm2, stm3, stm4, stm5, stm6 };
        OptVariable[] anm = { anm1, anm2, anm3, anm4, anm5, anm6 };
        OptVariable[] ue = { ue1, ue2, ue3, ue4, ue5, ue6 };

        // Objective: minimize cost
        OptObjective obj = model.objective();
        for (int i = 0; i < 6; i++) {
            obj.set(stm[i], 2700);
            obj.set(anm[i], 1500);
            obj.set(ue[i], 30);
        }

        int[] stdRhs = { 8000, 9000, 8000, 10000, 9000, 12000 };

        // ANZ1: STM1 = 60
        model.newConstraint("ANZ1").set(stm1, 1).level(60);

        // ANZ2-ANZ6: 0.9*STM[i-1] + ANM[i-1] - STM[i] = 0
        for (int i = 1; i < 6; i++) {
            model.newConstraint("ANZ" + (i + 1)).set(stm[i - 1], new BigDecimal("0.9")).set(anm[i - 1], 1).set(stm[i], -1).level(0);
        }

        // STD1-STD6: 150*STM[i] - 100*ANM[i] + UE[i] >= stdRhs[i]
        for (int i = 0; i < 6; i++) {
            model.newConstraint("STD" + (i + 1)).set(stm[i], 150).set(anm[i], -100).set(ue[i], 1).lower(stdRhs[i]);
        }

        // UEB1-UEB6: -20*STM[i] + UE[i] <= 0
        for (int i = 0; i < 6; i++) {
            model.newConstraint("UEB" + (i + 1)).set(stm[i], -20).set(ue[i], 1).upper(0);
        }

        Future<OptResult> minimise = model.minimise();
        OptResult result = minimise.get();

        Assertions.assertTrue(result.isFeasible());
        Assertions.assertTrue(result.isOptimal());
        Assertions.assertTrue(new BigDecimal("1201500").compareTo(result.getValue()) == 0);
    }

    /**
     * Submit flugpl.mps directly via putOnQueue and verify the objective value matches the programmatic model
     * in {@link #testFlugpl()}.
     */
    @Test
    public void testFlugplMps() throws Exception {

        OptClientV1 client = new OptClientV1(URI.create(HOST));

        Map<String, Object> response = OptClientV1Test.solveViaMps(client, "optimisation/MIPLIB/flugpl.mps", false);
        OptResult result = (OptResult) response.get(OptClientV1.RESULT);

        Assertions.assertEquals(0, new BigDecimal("1201500").compareTo(result.getValue()));
        Assertions.assertNotNull(result.getSolution());
    }

    /**
     * Transportation problem: 4 sources, 6 destinations. 24 real variables (quantities), 24 binary variables
     * (route open/closed). Known optimal: 202.35
     */
    @Test
    public void testGr4x6() throws InterruptedException, ExecutionException {

        OptClientV1 client = new OptClientV1(URI.create(HOST));

        OptModel model = new OptModel(client);

        double[] costs = { 0.69, 0.64, 0.71, 0.79, 1.70, 2.83, 1.01, 0.75, 0.88, 0.59, 1.50, 2.63, 1.05, 1.06, 1.08, 0.64, 1.22, 2.37, 1.94, 1.50, 1.56, 1.22,
                1.98, 1.98 };

        int[] fixedCosts = { 11, 16, 18, 17, 10, 20, 14, 17, 17, 13, 15, 13, 12, 13, 20, 17, 13, 15, 16, 19, 15, 11, 15, 12 };

        int[] capacities = { 35, 30, 25, 15, 5, 5, 35, 30, 25, 15, 5, 5, 20, 20, 20, 15, 5, 5, 15, 15, 15, 15, 5, 5 };

        int[] supply = { 45, 35, 20, 15 };
        int[] demand = { 35, 30, 25, 15, 5, 5 };

        OptVariable.RealVariable[] x = new OptVariable.RealVariable[24];
        OptVariable.BinaryVariable[] y = new OptVariable.BinaryVariable[24];

        for (int i = 0; i < 24; i++) {
            x[i] = model.newRealVariable("X" + i);
            // real variables default to lower=0, no upper
        }
        for (int i = 0; i < 24; i++) {
            y[i] = model.newBinaryVariable("Y" + i);
        }

        OptObjective obj = model.objective();
        for (int i = 0; i < 24; i++) {
            obj.set(x[i], costs[i]);
            obj.set(y[i], fixedCosts[i]);
        }

        // Supply constraints: each source ships exactly its supply
        for (int s = 0; s < 4; s++) {
            OptConstraint c = model.newConstraint("A" + s);
            for (int d = 0; d < 6; d++) {
                c.set(x[s * 6 + d], 1);
            }
            c.level(supply[s]);
        }

        // Demand constraints: each destination receives exactly its demand
        for (int d = 0; d < 6; d++) {
            OptConstraint c = model.newConstraint("B" + d);
            for (int s = 0; s < 4; s++) {
                c.set(x[s * 6 + d], 1);
            }
            c.level(demand[d]);
        }

        // Linking constraints: X[i] <= capacity[i] * Y[i]
        for (int i = 0; i < 24; i++) {
            model.newConstraint("G" + i).set(x[i], 1).set(y[i], -capacities[i]).upper(0);
        }

        Future<OptResult> minimise = model.minimise();
        OptResult result = minimise.get();

        Assertions.assertTrue(result.isFeasible());
        Assertions.assertTrue(result.isOptimal());
        Assertions.assertTrue(new BigDecimal("202.35").compareTo(result.getValue()) == 0);
    }

    /**
     * Submit gr4x6.mps directly via putOnQueue and verify the objective value matches the programmatic model
     * in {@link #testGr4x6()}.
     */
    @Test
    public void testGr4x6Mps() throws Exception {

        OptClientV1 client = new OptClientV1(URI.create(HOST));

        Map<String, Object> response = OptClientV1Test.solveViaMps(client, "optimisation/MIPLIB/gr4x6.mps", false);
        OptResult result = (OptResult) response.get(OptClientV1.RESULT);

        Assertions.assertEquals(0, new BigDecimal("202.35").compareTo(result.getValue()));
        Assertions.assertNotNull(result.getSolution());
    }

    @Test
    public void testIsServiceAvailable() {

        OptClientV1 client = new OptClientV1(URI.create(HOST));

        Assertions.assertTrue(client.isServiceAvailable());

        System.out.println(client.getServiceEnvironment());
    }

    @Test
    public void testOptimisationClient() {

        OptClientV1 client = new OptClientV1(URI.create(HOST));

        Assertions.assertTrue(client.isServiceAvailable());

        System.out.println(client.getServiceEnvironment());
    }

    /**
     * Prints which build the deployed service is running – version, variant, when it was packaged, and the
     * commit it came from. Run this to see what is actually deployed; nothing else the service exposes
     * differs between builds.
     */
    @Test
    public void testServiceVersion() {

        OptClientV1 client = new OptClientV1(URI.create(HOST));

        String version = client.getServiceVersion();

        System.out.println(HOST);
        System.out.println(version);

        Assertions.assertNotEquals("?", version, "the service is unreachable");
    }

    @Test
    public void testVeryBasicModel() throws InterruptedException, ExecutionException {

        OptClientV1 client = new OptClientV1(URI.create(HOST));

        OptModel model = new OptModel(client);

        OptVariable varA = model.newRealVariable("A").lower(0);
        OptVariable varB = model.newRealVariable("B").lower(0);

        model.newConstraint("UM2").set(varA, 1).set(varB, 1).level(2);

        model.objective().set(varA, 10).set(varB, -10);

        Assertions.assertTrue(model.maximise().get().isOptimal());
        Assertions.assertEquals(2.0, varA.doubleValue());
        Assertions.assertEquals(0.0, varB.doubleValue());

        Assertions.assertTrue(model.minimise().get().isOptimal());
        Assertions.assertEquals(0.0, varA.doubleValue());
        Assertions.assertEquals(2.0, varB.doubleValue());
    }

}
