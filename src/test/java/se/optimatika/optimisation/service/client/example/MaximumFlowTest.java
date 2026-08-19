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
public class MaximumFlowTest {

    private static final String HOST = System.getenv("SERVICE_HOST") != null ? System.getenv("SERVICE_HOST")
            : "https://optimatika-boot-services-969062758986.europe-north1.run.app";

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

        OptClientV1 client = new OptClientV1(URI.create(HOST));
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

}
