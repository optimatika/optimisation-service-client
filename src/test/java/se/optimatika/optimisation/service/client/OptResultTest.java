package se.optimatika.optimisation.service.client;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * {@link OptResult} is read as ojAlgo's {@code Optimisation.Result} is: the objective function's value
 * without an index, the variables' values with one. Needs no service.
 */
class OptResultTest {

    private static final OptResult OPTIMAL = new OptResult("OPTIMAL", new BigDecimal("8.6"), List.of(new BigDecimal("3"), new BigDecimal("2.5")));

    @Test
    void testObjectiveValue() {
        Assertions.assertEquals(new BigDecimal("8.6"), OPTIMAL.get());
        Assertions.assertEquals(8.6, OPTIMAL.doubleValue());
        Assertions.assertEquals(8.6F, OPTIMAL.floatValue());
        Assertions.assertEquals(9, OPTIMAL.intValue());
        Assertions.assertEquals(9L, OPTIMAL.longValue());
        Assertions.assertEquals((short) 9, OPTIMAL.shortValue());
        Assertions.assertEquals((byte) 9, OPTIMAL.byteValue());
        Assertions.assertTrue(OPTIMAL.booleanValue());
    }

    @Test
    void testStates() {
        Assertions.assertTrue(OPTIMAL.isOptimal());
        Assertions.assertTrue(OPTIMAL.isFeasible());
        Assertions.assertFalse(new OptResult("FEASIBLE", BigDecimal.ONE, List.of()).isOptimal());
        Assertions.assertFalse(new OptResult("INFEASIBLE", BigDecimal.ONE, List.of()).isFeasible());
    }

    @Test
    void testVariableValues() {
        Assertions.assertEquals(2, OPTIMAL.size());
        Assertions.assertEquals(2L, OPTIMAL.count());
        Assertions.assertEquals(new BigDecimal("2.5"), OPTIMAL.get(1));
        Assertions.assertEquals(2.5, OPTIMAL.doubleValue(1));
        Assertions.assertEquals(3, OPTIMAL.intValue(0));
        Assertions.assertEquals(3L, OPTIMAL.longValue(1)); // 2.5 rounds up, as in ojAlgo
        Assertions.assertThrows(IndexOutOfBoundsException.class, () -> OPTIMAL.get(2));
    }

    @Test
    void testWithoutSolution() {
        OptResult failed = new OptResult("FAILED", null, null);
        Assertions.assertEquals(0, failed.size());
        Assertions.assertTrue(Double.isNaN(failed.doubleValue()));
        Assertions.assertThrows(IndexOutOfBoundsException.class, () -> failed.get(0));
    }

}
