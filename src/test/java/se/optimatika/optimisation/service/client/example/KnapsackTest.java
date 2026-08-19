package se.optimatika.optimisation.service.client.example;

import java.net.URI;
import java.util.concurrent.ExecutionException;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import se.optimatika.optimisation.service.client.OptClientV1;
import se.optimatika.optimisation.service.client.OptConstraint;
import se.optimatika.optimisation.service.client.OptModel;
import se.optimatika.optimisation.service.client.OptObjective;
import se.optimatika.optimisation.service.client.OptResult;
import se.optimatika.optimisation.service.client.OptVariable;

@Tag("integration")
public class KnapsackTest {

    private static final String HOST = System.getenv("SERVICE_HOST") != null ? System.getenv("SERVICE_HOST")
            : "https://optimatika-boot-services-969062758986.europe-north1.run.app";

    /**
     * Knapsack Problem.
     * <p>
     * Select items to maximise total value without exceeding a weight capacity of 12.
     * <p>
     * Items (weight, value): (2,6), (3,8), (6,12), (7,14), (5,11), (4,9)
     * <p>
     * Optimal value = 28 (e.g. items {1,4,5} with weights 3+5+4=12)
     */
    @Test
    public void testKnapsackProblem() throws InterruptedException, ExecutionException {

        OptClientV1 client = new OptClientV1(URI.create(HOST));
        OptModel model = client.newModel();

        int[] weights = { 2, 3, 6, 7, 5, 4 };
        int[] values = { 6, 8, 12, 14, 11, 9 };
        int capacity = 12;

        OptVariable.BinaryVariable[] items = new OptVariable.BinaryVariable[6];
        for (int i = 0; i < 6; i++) {
            items[i] = model.newBinaryVariable("item" + i);
        }

        OptConstraint weightLimit = model.newConstraint("capacity");
        for (int i = 0; i < 6; i++) {
            weightLimit.set(items[i], weights[i]);
        }
        weightLimit.upper(capacity);

        OptObjective obj = model.objective();
        for (int i = 0; i < 6; i++) {
            obj.set(items[i], values[i]);
        }

        OptResult result = model.maximise().get();

        Assertions.assertTrue(result.isOptimal());
        Assertions.assertEquals(28.0, result.getValue().doubleValue(), 1E-6);
    }

}
