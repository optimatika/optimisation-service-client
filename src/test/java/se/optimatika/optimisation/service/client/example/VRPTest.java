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
public class VRPTest {

    private static final String HOST = System.getenv("SERVICE_HOST") != null ? System.getenv("SERVICE_HOST")
            : "https://optimatika-boot-services-969062758986.europe-north1.run.app";

    /**
     * Vehicle Routing Problem (VRP).
     * <p>
     * Route 2 vehicles of capacity 7 from a depot (node 0) to serve 4 customers, minimising total travel
     * distance. Subtours are eliminated via commodity flow variables that track the remaining load on each
     * arc.
     * <p>
     * Customer demands: d1=3, d2=4, d3=2, d4=3.
     * <p>
     * Distance matrix:
     *
     * <pre>
     *       0   1   2   3   4
     *  0:   -   5   8   6   7
     *  1:   5   -   4   9  10
     *  2:   8   4   -   7   6
     *  3:   6   9   7   -   3
     *  4:   7  10   6   3   -
     * </pre>
     *
     * Optimal routes: {0->1->2->0} and {0->3->4->0}, distance = 33
     */
    @Test
    public void testVRP() throws InterruptedException, ExecutionException {

        OptClientV1 client = new OptClientV1(URI.create(HOST));
        OptModel model = client.newModel();

        int n = 5;
        int vehicles = 2;
        int vehicleCapacity = 7;
        int[] demand = { 0, 3, 4, 2, 3 };
        int[][] dist = { { 0, 5, 8, 6, 7 }, { 5, 0, 4, 9, 10 }, { 8, 4, 0, 7, 6 }, { 6, 9, 7, 0, 3 }, { 7, 10, 6, 3, 0 } };

        // x[i][j] = 1 if a vehicle travels directly from node i to node j
        OptVariable.BinaryVariable[][] x = new OptVariable.BinaryVariable[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                if (i != j) {
                    x[i][j] = model.newBinaryVariable("x" + i + "_" + j);
                }
            }
        }

        // f[i][j] = commodity flow on arc (i,j), representing load carried
        OptVariable.RealVariable[][] f = new OptVariable.RealVariable[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                if (i != j) {
                    f[i][j] = model.newRealVariable("f" + i + "_" + j);
                }
            }
        }

        // Each customer left exactly once
        for (int i = 1; i < n; i++) {
            OptConstraint c = model.newConstraint("out" + i);
            for (int j = 0; j < n; j++) {
                if (i != j) {
                    c.set(x[i][j], 1);
                }
            }
            c.level(1);
        }

        // Each customer entered exactly once
        for (int j = 1; j < n; j++) {
            OptConstraint c = model.newConstraint("in" + j);
            for (int i = 0; i < n; i++) {
                if (i != j) {
                    c.set(x[i][j], 1);
                }
            }
            c.level(1);
        }

        // Depot: exactly K vehicles depart and return
        OptConstraint depotOut = model.newConstraint("depotOut");
        for (int j = 1; j < n; j++) {
            depotOut.set(x[0][j], 1);
        }
        depotOut.level(vehicles);

        OptConstraint depotIn = model.newConstraint("depotIn");
        for (int i = 1; i < n; i++) {
            depotIn.set(x[i][0], 1);
        }
        depotIn.level(vehicles);

        // Flow conservation at each customer: inflow - outflow = demand
        for (int i = 1; i < n; i++) {
            OptConstraint c = model.newConstraint("flow" + i);
            for (int j = 0; j < n; j++) {
                if (j != i) {
                    c.set(f[j][i], 1);
                    c.set(f[i][j], -1);
                }
            }
            c.level(demand[i]);
        }

        // Flow-routing link: f[i][j] <= capacity * x[i][j]
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                if (i != j) {
                    model.newConstraint("link" + i + "_" + j).set(f[i][j], 1).set(x[i][j], -vehicleCapacity).upper(0);
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
        Assertions.assertEquals(33.0, result.getValue().doubleValue(), 1E-6);
    }

}
