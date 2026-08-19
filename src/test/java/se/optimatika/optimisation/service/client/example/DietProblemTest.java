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
public class DietProblemTest {

    private static final String HOST = System.getenv("SERVICE_HOST") != null ? System.getenv("SERVICE_HOST")
            : "https://optimatika-boot-services-969062758986.europe-north1.run.app";

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

        OptClientV1 client = new OptClientV1(URI.create(HOST));
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

}
