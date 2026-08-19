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
public class NewsvendorTest {

    private static final String HOST = System.getenv("SERVICE_HOST") != null ? System.getenv("SERVICE_HOST")
            : "https://optimatika-boot-services-969062758986.europe-north1.run.app";

    /**
     * Newsvendor Problem (stochastic).
     * <p>
     * Determine the optimal order quantity for a perishable product under demand uncertainty, maximising
     * expected profit across 5 equally likely demand scenarios.
     * <p>
     * Unit cost = 10, selling price = 25, salvage value = 5. Demand scenarios: {100, 150, 200, 250, 300},
     * each with probability 0.2.
     * <p>
     * The LP uses per-scenario excess and shortage variables linked by q - excess + shortage = demand for
     * each scenario.
     * <p>
     * Critical fractile = (25-10)/(25-5) = 0.75, optimal order quantity = 250. Maximum expected profit =
     * 2550.
     */
    @Test
    public void testNewsvendorProblem() throws InterruptedException, ExecutionException {

        OptClientV1 client = new OptClientV1(URI.create(HOST));
        OptModel model = client.newModel();

        int sellingPrice = 25;
        int unitCost = 10;
        int salvageValue = 5;
        double probability = 0.2;
        int[] demands = { 100, 150, 200, 250, 300 };

        OptVariable q = model.newRealVariable("q");

        OptVariable[] excess = new OptVariable[5];
        OptVariable[] shortage = new OptVariable[5];
        for (int k = 0; k < 5; k++) {
            excess[k] = model.newRealVariable("excess" + k);
            shortage[k] = model.newRealVariable("shortage" + k);
        }

        for (int k = 0; k < 5; k++) {
            model.newConstraint("scenario" + k).set(q, 1).set(excess[k], -1).set(shortage[k], 1).level(demands[k]);
        }

        // Maximize: (price - cost) * q - (price - salvage) * prob * sum(excess)
        OptObjective obj = model.objective();
        obj.set(q, sellingPrice - unitCost);
        for (int k = 0; k < 5; k++) {
            obj.set(excess[k], -(sellingPrice - salvageValue) * probability);
        }

        OptResult result = model.maximise().get();

        Assertions.assertTrue(result.isOptimal());
        Assertions.assertEquals(2550.0, result.getValue().doubleValue(), 1E-6);
        Assertions.assertEquals(250.0, q.doubleValue(), 1E-6);
    }

}
