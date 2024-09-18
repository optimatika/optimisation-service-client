package se.optimatika.optimisation.service.client;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

/**
 * A linear and/or quadratic expression over the model's variables. Serves as the base for both constraints
 * and the objective function.
 */
abstract class OptExpression extends OptEntity {

    static int decodeCol(final long key) {
        return (int) key;
    }

    static int decodeRow(final long key) {
        return (int) (key >> 32);
    }

    private BigDecimal myConstant = null;
    private final Map<Integer, BigDecimal> myLinear = new LinkedHashMap<>();
    private Map<Long, BigDecimal> myQuadratic = null;

    OptExpression(final String name) {
        super(name);
    }

    OptExpression constant(final BigDecimal constant) {
        myConstant = constant;
        return this;
    }

    BigDecimal getConstant() {
        return myConstant;
    }

    Set<Entry<Integer, BigDecimal>> getLinearEntrySet() {
        return myLinear.entrySet();
    }

    Set<Entry<Long, BigDecimal>> getQuadraticEntrySet() {
        Map<Long, BigDecimal> tmpQuadratic = myQuadratic != null ? myQuadratic : Collections.emptyMap();
        return tmpQuadratic.entrySet();
    }

    OptExpression set(final OptVariable variable, final BigDecimal coefficient) {
        myLinear.put(variable.getIndex(), coefficient);
        return this;
    }

    OptExpression set(final OptVariable row, final OptVariable col, final BigDecimal coefficient) {
        long key = ((long) row.getIndex() << 32) | (col.getIndex() & 0xFFFFFFFFL);
        if (myQuadratic == null) {
            myQuadratic = new LinkedHashMap<>();
        }
        myQuadratic.put(key, coefficient);
        return this;
    }

}
