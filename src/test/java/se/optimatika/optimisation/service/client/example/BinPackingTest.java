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
public class BinPackingTest {

    private static final String HOST = System.getenv("SERVICE_HOST") != null ? System.getenv("SERVICE_HOST")
            : "https://optimatika-boot-services-969062758986.europe-north1.run.app";

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

        OptClientV1 client = new OptClientV1(URI.create(HOST));
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

}
