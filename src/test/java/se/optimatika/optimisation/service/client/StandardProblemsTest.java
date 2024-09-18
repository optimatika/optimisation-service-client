package se.optimatika.optimisation.service.client;

import java.net.URI;
import java.util.concurrent.ExecutionException;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Demonstrates how to formulate and solve standard optimisation problems using {@link OptModel}, ordered from
 * computationally easiest to hardest. Covers LP, QP, MILP and MIQP problem types.
 * <p>
 * These tests require a running optimisation-service-server. Set the {@code SERVICE_HOST} environment
 * variable to the server URL, or it defaults to the GCP Cloud Run deployment.
 */
@Tag("integration")
public class StandardProblemsTest {

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

        OptClientV01 client = new OptClientV01(URI.create(HOST));
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

    /**
     * Bin Packing Problem.
     * <p>
     * Pack items into the fewest bins of capacity 10.
     * <p>
     * Item sizes: {7, 5, 4, 3, 3, 2, 2} (total = 26, lower bound = 3 bins)
     * <p>
     * Optimal: 3 bins (e.g. {7,3}, {5,3,2}, {4,2})
     */
    @Test
    public void testBinPackingProblem() throws InterruptedException, ExecutionException {

        OptClientV01 client = new OptClientV01(URI.create(HOST));
        OptModel model = client.newModel();

        int[] sizes = { 7, 5, 4, 3, 3, 2, 2 };
        int binCapacity = 10;
        int nItems = sizes.length;
        int nBins = 4;

        // y[j] = 1 if bin j is used
        OptVariable.BinaryVariable[] y = new OptVariable.BinaryVariable[nBins];
        for (int j = 0; j < nBins; j++) {
            y[j] = model.newBinaryVariable("bin" + j);
        }

        // x[i][j] = 1 if item i is placed in bin j
        OptVariable.BinaryVariable[][] x = new OptVariable.BinaryVariable[nItems][nBins];
        for (int i = 0; i < nItems; i++) {
            for (int j = 0; j < nBins; j++) {
                x[i][j] = model.newBinaryVariable("x" + i + "_" + j);
            }
        }

        // Each item in exactly one bin
        for (int i = 0; i < nItems; i++) {
            OptConstraint c = model.newConstraint("item" + i);
            for (int j = 0; j < nBins; j++) {
                c.set(x[i][j], 1);
            }
            c.level(1);
        }

        // Bin capacity: sum(size[i] * x[i][j]) <= capacity * y[j]
        for (int j = 0; j < nBins; j++) {
            OptConstraint c = model.newConstraint("cap" + j);
            for (int i = 0; i < nItems; i++) {
                c.set(x[i][j], sizes[i]);
            }
            c.set(y[j], -binCapacity);
            c.upper(0);
        }

        // Symmetry breaking: y[0] >= y[1] >= ... >= y[nBins-1]
        for (int j = 0; j < nBins - 1; j++) {
            model.newConstraint("sym" + j).set(y[j], 1).set(y[j + 1], -1).lower(0);
        }

        OptObjective obj = model.objective();
        for (int j = 0; j < nBins; j++) {
            obj.set(y[j], 1);
        }

        OptResult result = model.minimise().get();

        Assertions.assertTrue(result.isOptimal());
        Assertions.assertEquals(3.0, result.getValue().doubleValue(), 1E-6);
    }

    /**
     * Diet Problem.
     * <p>
     * Find the cheapest combination of foods that satisfies all nutritional requirements.
     * <p>
     * Foods (cost per serving, protein, calories): Oatmeal: $2, 1g, 10 cal — Chicken: $3, 2g, 5 cal
     * <p>
     * Requirements: protein >= 12g, calories >= 60
     * <p>
     * Optimal cost: $20 (4 servings of each)
     */
    @Test
    public void testDietProblem() throws InterruptedException, ExecutionException {

        OptClientV01 client = new OptClientV01(URI.create(HOST));
        OptModel model = client.newModel();

        OptVariable oatmeal = model.newRealVariable("oatmeal");
        OptVariable chicken = model.newRealVariable("chicken");

        model.newConstraint("protein").set(oatmeal, 1).set(chicken, 2).lower(12);
        model.newConstraint("calories").set(oatmeal, 10).set(chicken, 5).lower(60);

        model.objective().set(oatmeal, 2).set(chicken, 3);

        OptResult result = model.minimise().get();

        Assertions.assertTrue(result.isOptimal());
        Assertions.assertEquals(20.0, result.getValue().doubleValue(), 1E-6);
    }

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

        OptClientV01 client = new OptClientV01(URI.create(HOST));
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

    /**
     * Maximum Flow Problem.
     * <p>
     * Find the maximum flow from source (s) to sink (t) in a capacitated network. Arc capacities are modelled
     * as upper bounds on the flow variables.
     * <p>
     * Network (arc: capacity): s->a: 10, s->b: 8, a->t: 5, a->b: 3, b->t: 7
     * <p>
     * Maximum flow = 12
     */
    @Test
    public void testMaximumFlow() throws InterruptedException, ExecutionException {

        OptClientV01 client = new OptClientV01(URI.create(HOST));
        OptModel model = client.newModel();

        OptVariable fSA = model.newRealVariable("sa").upper(10);
        OptVariable fSB = model.newRealVariable("sb").upper(8);
        OptVariable fAT = model.newRealVariable("at").upper(5);
        OptVariable fAB = model.newRealVariable("ab").upper(3);
        OptVariable fBT = model.newRealVariable("bt").upper(7);

        // Flow conservation at interior nodes
        model.newConstraint("nodeA").set(fSA, 1).set(fAT, -1).set(fAB, -1).level(0);
        model.newConstraint("nodeB").set(fSB, 1).set(fAB, 1).set(fBT, -1).level(0);

        // Maximize total flow leaving the source
        model.objective().set(fSA, 1).set(fSB, 1);

        OptResult result = model.maximise().get();

        Assertions.assertTrue(result.isOptimal());
        Assertions.assertEquals(12.0, result.getValue().doubleValue(), 1E-6);
    }

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

        OptClientV01 client = new OptClientV01(URI.create(HOST));
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

        OptClientV01 client = new OptClientV01(URI.create(HOST));
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

        OptClientV01 client = new OptClientV01(URI.create(HOST));
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

    /**
     * Shortest Path Problem.
     * <p>
     * Find the minimum-cost path from source (0) to sink (4) in a directed graph, formulated as a
     * minimum-cost network flow with one unit from source to sink.
     * <p>
     * Graph (node -> node: cost): 0->1: 4, 0->2: 2, 1->3: 3, 1->4: 7, 2->3: 1, 2->4: 8, 3->4: 2
     * <p>
     * Optimal path: 0 -> 2 -> 3 -> 4, cost = 5
     */
    @Test
    public void testShortestPath() throws InterruptedException, ExecutionException {

        OptClientV01 client = new OptClientV01(URI.create(HOST));
        OptModel model = client.newModel();

        OptVariable f01 = model.newRealVariable("f01");
        OptVariable f02 = model.newRealVariable("f02");
        OptVariable f13 = model.newRealVariable("f13");
        OptVariable f14 = model.newRealVariable("f14");
        OptVariable f23 = model.newRealVariable("f23");
        OptVariable f24 = model.newRealVariable("f24");
        OptVariable f34 = model.newRealVariable("f34");

        // Source emits 1 unit of flow
        model.newConstraint("source").set(f01, 1).set(f02, 1).level(1);

        // Flow conservation at intermediate nodes
        model.newConstraint("node1").set(f01, 1).set(f13, -1).set(f14, -1).level(0);
        model.newConstraint("node2").set(f02, 1).set(f23, -1).set(f24, -1).level(0);
        model.newConstraint("node3").set(f13, 1).set(f23, 1).set(f34, -1).level(0);

        model.objective().set(f01, 4).set(f02, 2).set(f13, 3).set(f14, 7).set(f23, 1).set(f24, 8).set(f34, 2);

        OptResult result = model.minimise().get();

        Assertions.assertTrue(result.isOptimal());
        Assertions.assertEquals(5.0, result.getValue().doubleValue(), 1E-6);
    }

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

        OptClientV01 client = new OptClientV01(URI.create(HOST));
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

        OptClientV01 client = new OptClientV01(URI.create(HOST));
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
