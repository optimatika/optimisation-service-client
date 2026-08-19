package se.optimatika.optimisation.service.client.example;

import java.net.URI;
import java.util.concurrent.ExecutionException;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import se.optimatika.optimisation.service.client.OptClientV1;
import se.optimatika.optimisation.service.client.OptModel;
import se.optimatika.optimisation.service.client.OptResult;
import se.optimatika.optimisation.service.client.OptVariable;

@Tag("integration")
public class ShortestPathTest {

    private static final String HOST = System.getenv("SERVICE_HOST") != null ? System.getenv("SERVICE_HOST")
            : "https://optimatika-boot-services-969062758986.europe-north1.run.app";

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

        OptClientV1 client = new OptClientV1(URI.create(HOST));
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

}
