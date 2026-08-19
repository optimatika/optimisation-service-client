package se.optimatika.optimisation.service.client.example;

import java.net.URI;
import java.util.concurrent.ExecutionException;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import se.optimatika.optimisation.service.client.OptClientV1;
import se.optimatika.optimisation.service.client.OptModel;
import se.optimatika.optimisation.service.client.OptObjective;
import se.optimatika.optimisation.service.client.OptResult;
import se.optimatika.optimisation.service.client.OptVariable;

@Tag("integration")
public class PortfolioOptimisationTest {

    private static final String HOST = System.getenv("SERVICE_HOST") != null ? System.getenv("SERVICE_HOST")
            : "https://optimatika-boot-services-969062758986.europe-north1.run.app";

    /**
     * Portfolio Optimisation (Markowitz mean-variance, QP).
     * <p>
     * Minimise portfolio variance subject to a minimum expected return target and a fully-invested
     * constraint. Three assets with known expected returns and covariance matrix.
     * <p>
     * Returns: r1=0.08, r2=0.12, r3=0.05.
     * <p>
     * Covariance matrix:
     *
     * <pre>
     *        A1     A2     A3
     * A1:  0.04   0.01  -0.005
     * A2:  0.01   0.09   0.015
     * A3: -0.005  0.015  0.01
     * </pre>
     *
     * Target return >= 0.09. Weights in [0, 1], sum = 1.
     * <p>
     * Optimal variance approximately 0.0196, achieved with w1~0.345, w2~0.345, w3~0.310.
     */
    @Test
    public void testPortfolioOptimisation() throws InterruptedException, ExecutionException {

        OptClientV1 client = new OptClientV1(URI.create(HOST));
        OptModel model = client.newModel();

        OptVariable w1 = model.newRealVariable("w1").lower(0).upper(1);
        OptVariable w2 = model.newRealVariable("w2").lower(0).upper(1);
        OptVariable w3 = model.newRealVariable("w3").lower(0).upper(1);

        // Objective: minimise portfolio variance (quadratic)
        OptObjective obj = model.objective();
        obj.set(w1, w1, 0.04);
        obj.set(w2, w2, 0.09);
        obj.set(w3, w3, 0.01);
        obj.set(w1, w2, 0.02); // 2 * cov(1,2) = 2 * 0.01
        obj.set(w1, w3, -0.01); // 2 * cov(1,3) = 2 * -0.005
        obj.set(w2, w3, 0.03); // 2 * cov(2,3) = 2 * 0.015

        // Fully invested
        model.newConstraint("budget").set(w1, 1).set(w2, 1).set(w3, 1).level(1);

        // Minimum return target
        model.newConstraint("return").set(w1, 0.08).set(w2, 0.12).set(w3, 0.05).lower(0.09);

        OptResult result = model.minimise().get();

        Assertions.assertTrue(result.isOptimal());
        Assertions.assertTrue(result.getValue().doubleValue() < 0.03);
        Assertions.assertTrue(result.getValue().doubleValue() > 0.01);
    }

    /**
     * Portfolio Optimisation with whole shares (MIQP).
     * <p>
     * Same as the Markowitz QP, but allocate a budget of 100 units in integer increments (whole shares).
     * Three assets, quadratic variance objective, integer variables.
     * <p>
     * Units in [0, 100], sum = 100. Minimum return target: 0.09 per unit on average.
     */
    @Test
    public void testPortfolioWholeShares() throws InterruptedException, ExecutionException {

        OptClientV1 client = new OptClientV1(URI.create(HOST));
        OptModel model = client.newModel();

        OptVariable.IntegerVariable u1 = model.newIntegerVariable("u1").lower(0).upper(100);
        OptVariable.IntegerVariable u2 = model.newIntegerVariable("u2").lower(0).upper(100);
        OptVariable.IntegerVariable u3 = model.newIntegerVariable("u3").lower(0).upper(100);

        // Objective: minimise portfolio variance (scaled by 1/10000 since units are 100x weights)
        OptObjective obj = model.objective();
        obj.set(u1, u1, 0.04);
        obj.set(u2, u2, 0.09);
        obj.set(u3, u3, 0.01);
        obj.set(u1, u2, 0.02);
        obj.set(u1, u3, -0.01);
        obj.set(u2, u3, 0.03);

        // All units allocated
        model.newConstraint("budget").set(u1, 1).set(u2, 1).set(u3, 1).level(100);

        // Minimum return target (per unit)
        model.newConstraint("return").set(u1, 0.08).set(u2, 0.12).set(u3, 0.05).lower(9.0);

        OptResult result = model.minimise().get();

        Assertions.assertTrue(result.isOptimal());

        // Verify integer solution
        int alloc1 = u1.intValue();
        int alloc2 = u2.intValue();
        int alloc3 = u3.intValue();
        Assertions.assertEquals(100, alloc1 + alloc2 + alloc3);
    }

}
