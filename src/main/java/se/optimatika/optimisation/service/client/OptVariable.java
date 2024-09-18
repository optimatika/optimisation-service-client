package se.optimatika.optimisation.service.client;

import java.math.BigDecimal;

/**
 * A decision variable in the optimisation model. Variables are created via the factory methods on
 * {@link OptModel} ({@link OptModel#newRealVariable()}, {@link OptModel#newIntegerVariable()},
 * {@link OptModel#newBinaryVariable()}). After optimisation, the solution value is available through
 * {@link #getValue()} and the typed accessors ({@link #doubleValue()}, {@link #intValue()}, etc.).
 */
public abstract class OptVariable extends OptEntity {

    /** A binary (0/1) decision variable. */
    public static final class BinaryVariable extends OptVariable {

        BinaryVariable(final String name, final int index) {
            super(name, index, true);
            this.lower(BigDecimal.ZERO);
            this.upper(BigDecimal.ONE);
        }

        public BinaryVariable value(final BigDecimal value) {
            return this.value(OptModel.booleanValue(value));
        }

        public BinaryVariable value(final boolean value) {
            this.setValue(OptModel.value(value));
            return this;
        }

        public BinaryVariable value(final long value) {
            return this.value(OptModel.booleanValue(value));
        }

    }

    /** An integer decision variable. */
    public static final class IntegerVariable extends OptVariable {

        IntegerVariable(final String name, final int index) {
            super(name, index, true);
            this.lower(BigDecimal.ZERO);
        }

        @Override
        public IntegerVariable lower(final BigDecimal lower) {
            return this.lower(OptModel.longValue(lower));
        }

        public IntegerVariable lower(final long lower) {
            super.lower(OptModel.value(lower));
            return this;
        }

        @Override
        public IntegerVariable upper(final BigDecimal upper) {
            return this.upper(OptModel.longValue(upper));
        }

        public IntegerVariable upper(final long upper) {
            super.upper(OptModel.value(upper));
            return this;
        }

        public IntegerVariable value(final BigDecimal value) {
            return this.value(OptModel.longValue(value));
        }

        public IntegerVariable value(final long value) {
            this.setValue(OptModel.value(value));
            return this;
        }

    }

    /** A continuous (real-valued) decision variable. */
    public static final class RealVariable extends OptVariable {

        RealVariable(final String name, final int index) {
            super(name, index, false);
            this.lower(BigDecimal.ZERO);
        }

        @Override
        public RealVariable lower(final BigDecimal lower) {
            return (RealVariable) super.lower(lower);
        }

        public RealVariable lower(final double lower) {
            return this.lower(OptModel.value(lower));
        }

        @Override
        public RealVariable upper(final BigDecimal upper) {
            return (RealVariable) super.upper(upper);
        }

        public RealVariable upper(final double upper) {
            return this.upper(OptModel.value(upper));
        }

        public RealVariable value(final BigDecimal value) {
            this.setValue(value);
            return this;
        }

        public RealVariable value(final double value) {
            return this.value(OptModel.value(value));
        }

    }

    private final int myIndex;
    private final boolean myInteger;
    private BigDecimal myValue;

    OptVariable(final String name, final int index, final boolean integer) {
        super(name);
        myIndex = index;
        myInteger = integer;
    }

    public boolean booleanValue() {
        return OptModel.booleanValue(myValue);
    }

    public double doubleValue() {
        return OptModel.doubleValue(myValue);
    }

    public float floatValue() {
        return OptModel.floatValue(myValue);
    }

    public BigDecimal getValue() {
        return myValue;
    }

    public int intValue() {
        return OptModel.intValue(myValue);
    }

    public long longValue() {
        return OptModel.longValue(myValue);
    }

    public short shortValue() {
        return OptModel.shortValue(myValue);
    }

    int getIndex() {
        return myIndex;
    }

    boolean isInteger() {
        return myInteger;
    }

    void setValue(final BigDecimal value) {
        myValue = value;
    }

}
