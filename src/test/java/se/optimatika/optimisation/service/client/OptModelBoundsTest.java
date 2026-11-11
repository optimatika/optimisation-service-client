package se.optimatika.optimisation.service.client;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Variable and constraint bounds, as they are written into the model sent to the service: real and integer
 * variables are free when created, as in ojAlgo's {@code ExpressionsBasedModel}, and a bound that is
 * {@code null} is no bound. Needs no service – exporting as EBM is done here.
 */
class OptModelBoundsTest {

    /**
     * The lower and upper bound written for an entity, "" for none: the third and fourth field of its line.
     */
    private static String[] bounds(final OptModel model, final String type, final String name) throws IOException {
        String ebm = new String(model.exportModel("EBM").readAllBytes(), StandardCharsets.UTF_8);
        for (String line : ebm.split("\n")) {
            String[] fields = line.split("\t", -1);
            if (fields[0].equals(type) && fields[1].equals(name)) {
                return new String[] { fields[2], fields[3] };
            }
        }
        throw new AssertionError("No " + type + " line for " + name + " in:\n" + ebm);
    }

    private static OptModel newModel() {
        return new OptClientV1(URI.create("http://localhost:1")).newModel();
    }

    @Test
    void testBinaryIsZeroToOne() throws IOException {
        OptModel model = OptModelBoundsTest.newModel();
        model.newBinaryVariable("b");
        Assertions.assertArrayEquals(new String[] { "0", "1" }, OptModelBoundsTest.bounds(model, "V", "b"));
    }

    @Test
    void testConstraintNullIsNoBound() throws IOException {
        OptModel model = OptModelBoundsTest.newModel();
        model.newConstraint("c").lower(1.0).upper(2.0).lower((BigDecimal) null).upper((BigDecimal) null);
        Assertions.assertArrayEquals(new String[] { "", "" }, OptModelBoundsTest.bounds(model, "E", "c"));
    }

    @Test
    void testIntegerIsFreeByDefault() throws IOException {
        OptModel model = OptModelBoundsTest.newModel();
        model.newIntegerVariable("i");
        Assertions.assertArrayEquals(new String[] { "", "" }, OptModelBoundsTest.bounds(model, "V", "i"));
    }

    @Test
    void testIntegerNullIsNoBound() throws IOException {
        OptModel model = OptModelBoundsTest.newModel();
        model.newIntegerVariable("i").lower(-3).upper(7).lower(null).upper(null);
        Assertions.assertArrayEquals(new String[] { "", "" }, OptModelBoundsTest.bounds(model, "V", "i"));
    }

    @Test
    void testRealNullIsNoBound() throws IOException {
        OptModel model = OptModelBoundsTest.newModel();
        model.newRealVariable("x").lower(0).upper(5.5).lower(null).upper(null);
        Assertions.assertArrayEquals(new String[] { "", "" }, OptModelBoundsTest.bounds(model, "V", "x"));
    }

    @Test
    void testRealIsFreeByDefault() throws IOException {
        OptModel model = OptModelBoundsTest.newModel();
        model.newRealVariable("x");
        Assertions.assertArrayEquals(new String[] { "", "" }, OptModelBoundsTest.bounds(model, "V", "x"));
    }

    @Test
    void testSetBoundsAreWritten() throws IOException {
        OptModel model = OptModelBoundsTest.newModel();
        model.newRealVariable("x").lower(0).upper(5.5);
        model.newIntegerVariable("i").lower(-3).upper(7);
        Assertions.assertArrayEquals(new String[] { "0", "5.5" }, OptModelBoundsTest.bounds(model, "V", "x"));
        Assertions.assertArrayEquals(new String[] { "-3", "7" }, OptModelBoundsTest.bounds(model, "V", "i"));
    }

}
