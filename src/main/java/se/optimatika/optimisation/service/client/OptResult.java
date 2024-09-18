package se.optimatika.optimisation.service.client;

import java.math.BigDecimal;
import java.util.List;

/**
 * The result of an optimisation run. Reports whether a feasible/optimal solution was found, provides the
 * objective function value, and gives access to the solution vector.
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

    OptResult withAdjustedValue(final BigDecimal adjustment) {
        BigDecimal adjusted = myValue != null && adjustment != null ? myValue.add(adjustment) : myValue;
        return new OptResult(myFeasible, myOptimal, adjusted, mySolution);
    }

}
