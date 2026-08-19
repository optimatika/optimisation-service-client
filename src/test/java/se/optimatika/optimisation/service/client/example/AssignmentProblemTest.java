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
public class AssignmentProblemTest {

    private static final String HOST = System.getenv("SERVICE_HOST") != null ? System.getenv("SERVICE_HOST")
            : "https://optimatika-boot-services-969062758986.europe-north1.run.app";

    /**
     * Assignment Problem.
     * <p>
     * Assign 4 workers to 4 tasks at minimum total cost, one task per worker.
     * <p>
     * Cost matrix:
     *
     * <pre>
     *      T0  T1  T2  T3
     * W0:   9   2   7   8
     * W1:   6   4   3   7
     * W2:   5   8   1   8
     * W3:   7   6   9   4
     * </pre>
     *
     * Optimal assignment: W0->T1, W1->T0, W2->T2, W3->T3, cost = 13
     */
    @Test
    public void testAssignmentProblem() throws InterruptedException, ExecutionException {

        OptClientV1 client = new OptClientV1(URI.create(HOST));
        OptModel model = client.newModel();

        int[][] cost = { { 9, 2, 7, 8 }, { 6, 4, 3, 7 }, { 5, 8, 1, 8 }, { 7, 6, 9, 4 } };

        OptVariable.BinaryVariable[][] x = new OptVariable.BinaryVariable[4][4];
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 4; j++) {
                x[i][j] = model.newBinaryVariable("x" + i + j);
            }
        }

        // Each worker assigned to exactly one task
        for (int i = 0; i < 4; i++) {
            OptConstraint c = model.newConstraint("worker" + i);
            for (int j = 0; j < 4; j++) {
                c.set(x[i][j], 1);
            }
            c.level(1);
        }

        // Each task assigned to exactly one worker
        for (int j = 0; j < 4; j++) {
            OptConstraint c = model.newConstraint("task" + j);
            for (int i = 0; i < 4; i++) {
                c.set(x[i][j], 1);
            }
            c.level(1);
        }

        OptObjective obj = model.objective();
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 4; j++) {
                obj.set(x[i][j], cost[i][j]);
            }
        }

        OptResult result = model.minimise().get();

        Assertions.assertTrue(result.isOptimal());
        Assertions.assertEquals(13.0, result.getValue().doubleValue(), 1E-6);
    }

}
