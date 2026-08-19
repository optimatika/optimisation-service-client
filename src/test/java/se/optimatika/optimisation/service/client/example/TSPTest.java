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
public class TSPTest {

    private static final String HOST = System.getenv("SERVICE_HOST") != null ? System.getenv("SERVICE_HOST")
            : "https://optimatika-boot-services-969062758986.europe-north1.run.app";

    /**
     * Travelling Salesman Problem (TSP).
     * <p>
     * Find the shortest Hamiltonian cycle through 5 cities using the Miller-Tucker-Zemlin (MTZ) subtour
     * elimination formulation.
     * <p>
     * Distance matrix:
     *
     * <pre>
     *       0   1   2   3   4
     *  0:   -  10  15  20  25
     *  1:  10   -  35  25  30
     *  2:  15  35   -  30  20
     *  3:  20  25  30   -  15
     *  4:  25  30  20  15   -
     * </pre>
     *
     * Optimal tour: 0 -> 1 -> 3 -> 4 -> 2 -> 0, distance = 85
     */
    @Test
    public void testTSP() throws InterruptedException, ExecutionException {

        OptClientV1 client = new OptClientV1(URI.create(HOST));
        OptModel model = client.newModel();

        int n = 5;
        int[][] dist = { { 0, 10, 15, 20, 25 }, { 10, 0, 35, 25, 30 }, { 15, 35, 0, 30, 20 }, { 20, 25, 30, 0, 15 }, { 25, 30, 20, 15, 0 } };

        // x[i][j] = 1 if the tour goes directly from city i to city j
        OptVariable.BinaryVariable[][] x = new OptVariable.BinaryVariable[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                if (i != j) {
                    x[i][j] = model.newBinaryVariable("x" + i + "_" + j);
                }
            }
        }

        // u[i] = position of city i in the tour (MTZ variables, 1..n-1)
        OptVariable.RealVariable[] u = new OptVariable.RealVariable[n];
        for (int i = 1; i < n; i++) {
            u[i] = model.newRealVariable("u" + i).lower(1).upper(n - 1);
        }

        // Each city has exactly one outgoing arc
        for (int i = 0; i < n; i++) {
            OptConstraint c = model.newConstraint("out" + i);
            for (int j = 0; j < n; j++) {
                if (i != j) {
                    c.set(x[i][j], 1);
                }
            }
            c.level(1);
        }

        // Each city has exactly one incoming arc
        for (int j = 0; j < n; j++) {
            OptConstraint c = model.newConstraint("in" + j);
            for (int i = 0; i < n; i++) {
                if (i != j) {
                    c.set(x[i][j], 1);
                }
            }
            c.level(1);
        }

        // MTZ subtour elimination: u[i] - u[j] + n * x[i][j] <= n - 1
        for (int i = 1; i < n; i++) {
            for (int j = 1; j < n; j++) {
                if (i != j) {
                    model.newConstraint("mtz" + i + "_" + j).set(u[i], 1).set(u[j], -1).set(x[i][j], n).upper(n - 1);
                }
            }
        }

        OptObjective obj = model.objective();
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                if (i != j) {
                    obj.set(x[i][j], dist[i][j]);
                }
            }
        }

        OptResult result = model.minimise().get();

        Assertions.assertTrue(result.isOptimal());
        Assertions.assertEquals(85.0, result.getValue().doubleValue(), 1E-6);
    }

}
