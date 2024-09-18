package se.optimatika.optimisation.service.client;

import java.math.BigDecimal;

public final class OptResult {

    private final boolean myFeasible;
    private final boolean myOptimal;
    private final BigDecimal myValue;

    OptResult(final boolean feasible, final boolean optimal, final BigDecimal value) {
        super();
        myFeasible = feasible;
        myOptimal = optimal;
        myValue = value;
    }

    public BigDecimal getValue() {
        return myValue;
    }

    public boolean isFeasible() {
        return myFeasible;
    }

    public boolean isOptimal() {
        return myFeasible && myOptimal;
    }

}
