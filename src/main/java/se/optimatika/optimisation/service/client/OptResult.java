package se.optimatika.optimisation.service.client;

import java.math.BigDecimal;
import java.util.List;

/**
 * The result of an optimisation run. Reports whether a feasible/optimal solution was found, provides the
 * objective function value, and gives access to the solution vector.
 * <p>
 * Read as in ojAlgo's {@code Optimisation.Result}: the objective function's value without an index –
 * {@link #doubleValue()}, {@link #get()} – and the variables' values with one – {@link #doubleValue(long)},
 * {@link #get(long)} – in the order the variables were created. {@link #count()} is the number of values, 0
 * without a solution.
 */
public final class OptResult {

    private static boolean isFeasible(final String state) {
        switch (state) {
            case "FEASIBLE":
            case "OPTIMAL":
            case "DISTINCT":
            case "UNBOUNDED":
                return true;
            default:
                return false;
        }
    }

    private static boolean isOptimal(final String state) {
        switch (state) {
            case "OPTIMAL":
            case "DISTINCT":
                return true;
            default:
                return false;
        }
    }

    private final boolean myFeasible;
    private final boolean myOptimal;
    private final List<BigDecimal> mySolution;
    private final BigDecimal myValue;

    private OptResult(final boolean feasible, final boolean optimal, final BigDecimal value, final List<BigDecimal> solution) {
        super();
        myFeasible = feasible;
        myOptimal = optimal;
        myValue = value;
        mySolution = solution;
    }

    OptResult(final String state, final BigDecimal value, final List<BigDecimal> solution) {
        super();
        myFeasible = OptResult.isFeasible(state);
        myOptimal = OptResult.isOptimal(state);
        myValue = value;
        mySolution = solution;
    }

    /**
     * The objective function's value as a {@code boolean}: {@code true} unless it rounds to 0.
     */
    public boolean booleanValue() {
        return this.intValue() != 0;
    }

    /**
     * The objective function's value, rounded and cast to a {@code byte}.
     */
    public byte byteValue() {
        return (byte) this.intValue();
    }

    /**
     * A variable's value, rounded and cast to a {@code byte}.
     */
    public byte byteValue(final long index) {
        return (byte) this.shortValue(index);
    }

    /**
     * The number of values in the solution, one per variable – 0 without a solution.
     */
    public long count() {
        return mySolution != null ? mySolution.size() : 0L;
    }

    /**
     * The objective function's value, {@code NaN} if there is none.
     */
    public double doubleValue() {
        return myValue != null ? myValue.doubleValue() : Double.NaN;
    }

    /**
     * A variable's value, by the order the variables were created in.
     *
     * @throws IndexOutOfBoundsException if there is no such value
     */
    public double doubleValue(final long index) {
        return this.get(index).doubleValue();
    }

    /**
     * The objective function's value, as a {@code float}.
     */
    public float floatValue() {
        return (float) this.doubleValue();
    }

    /**
     * A variable's value, as a {@code float}.
     */
    public float floatValue(final long index) {
        return (float) this.doubleValue(index);
    }

    /**
     * The objective function's value, the same as {@link #getValue()}.
     */
    public BigDecimal get() {
        return myValue;
    }

    /**
     * A variable's value, by the order the variables were created in.
     *
     * @throws IndexOutOfBoundsException if there is no such value
     */
    public BigDecimal get(final long index) {
        if (index < 0L || index >= this.count()) {
            throw new IndexOutOfBoundsException("No value " + index + " of " + this.count());
        }
        return mySolution.get((int) index);
    }

    /**
     * Returns the solution vector, one value per variable in declaration order. May be {@code null} if no
     * feasible solution was found.
     */
    public List<BigDecimal> getSolution() {
        return mySolution;
    }

    /**
     * Returns the objective function value at the solution point. Does not include any constant term added on
     * the client side; see {@link OptModel} which adjusts this automatically.
     */
    public BigDecimal getValue() {
        return myValue;
    }

    /**
     * The objective function's value, rounded to an {@code int}.
     */
    public int intValue() {
        return Math.round(this.floatValue());
    }

    /**
     * A variable's value, rounded to an {@code int}.
     */
    public int intValue(final long index) {
        return (int) this.longValue(index);
    }

    /**
     * Returns {@code true} if the solver found a feasible solution (the solution satisfies all constraints).
     * This is true for states FEASIBLE, OPTIMAL, DISTINCT, and UNBOUNDED.
     */
    public boolean isFeasible() {
        return myFeasible;
    }

    /**
     * Returns {@code true} if the solver proved that the solution is optimal (best possible). An optimal
     * result is always feasible.
     */
    public boolean isOptimal() {
        return myFeasible && myOptimal;
    }

    /**
     * The objective function's value, rounded to a {@code long}.
     */
    public long longValue() {
        return Math.round(this.doubleValue());
    }

    /**
     * A variable's value, rounded to a {@code long}.
     */
    public long longValue(final long index) {
        return Math.round(this.doubleValue(index));
    }

    /**
     * The objective function's value, rounded and cast to a {@code short}.
     */
    public short shortValue() {
        return (short) this.intValue();
    }

    /**
     * A variable's value, rounded and cast to a {@code short}.
     */
    public short shortValue(final long index) {
        return (short) this.intValue(index);
    }

    /**
     * The number of values in the solution, one per variable – 0 without a solution.
     */
    public int size() {
        return (int) this.count();
    }

    OptResult withAdjustedValue(final BigDecimal adjustment) {
        BigDecimal adjusted = myValue != null && adjustment != null ? myValue.add(adjustment) : myValue;
        return new OptResult(myFeasible, myOptimal, adjusted, mySolution);
    }

}
