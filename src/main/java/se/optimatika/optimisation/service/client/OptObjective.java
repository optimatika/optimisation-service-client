package se.optimatika.optimisation.service.client;

import java.math.BigDecimal;
import java.util.Map;

/**
 * The objective function of the optimisation model. Set linear coefficients with
 * {@link #set(OptVariable, BigDecimal)}, quadratic coefficients with
 * {@link #set(OptVariable, OptVariable, BigDecimal)}, and an optional constant term with {@link #constant}.
 * Each model has exactly one objective, obtained via {@link OptModel#objective()}.
 */
public final class OptObjective extends OptExpression {

    private final OptModel myModel;
    private boolean myRegistered = false;

    OptObjective(final OptModel model) {
        super("Objective");
        myModel = model;
    }

    @Override
    public OptObjective constant(final BigDecimal constant) {
        if (!myRegistered) {
            myModel.addExpression(this);
            myRegistered = true;
        }
        super.constant(constant);
        return this;
    }

    public OptObjective constant(final double constant) {
        return this.constant(OptModel.value(constant));
    }

    public OptObjective constant(final long constant) {
        return this.constant(OptModel.value(constant));
    }

    /**
     * Evaluates the objective function using the current variable values and returns the result. This is
     * typically called after optimisation to retrieve the objective value including the constant term.
     */
    public BigDecimal getValue() {

        BigDecimal constant = this.getConstant();
        BigDecimal result = constant != null ? constant : BigDecimal.ZERO;

        for (Map.Entry<Integer, BigDecimal> entry : this.getLinearEntrySet()) {
            BigDecimal xi = myModel.getVariable(entry.getKey()).getValue();
            if (xi != null) {
                result = result.add(entry.getValue().multiply(xi));
            }
        }

        for (Map.Entry<Long, BigDecimal> entry : this.getQuadraticEntrySet()) {
            int row = OptExpression.decodeRow(entry.getKey());
            int col = OptExpression.decodeCol(entry.getKey());
            BigDecimal xi = myModel.getVariable(row).getValue();
            BigDecimal xj = myModel.getVariable(col).getValue();
            if (xi != null && xj != null) {
                result = result.add(entry.getValue().multiply(xi).multiply(xj));
            }
        }

        return result;
    }

    @Override
    public OptObjective set(final OptVariable variable, final BigDecimal weight) {
        if (!myRegistered) {
            myModel.addExpression(this);
            myRegistered = true;
        }
        super.set(variable, weight);
        return this;
    }

    public OptObjective set(final OptVariable variable, final boolean weight) {
        return this.set(variable, OptModel.value(weight));
    }

    public OptObjective set(final OptVariable variable, final double weight) {
        return this.set(variable, OptModel.value(weight));
    }

    public OptObjective set(final OptVariable variable, final long weight) {
        return this.set(variable, OptModel.value(weight));
    }

    @Override
    public OptObjective set(final OptVariable row, final OptVariable col, final BigDecimal weight) {
        if (!myRegistered) {
            myModel.addExpression(this);
            myRegistered = true;
        }
        super.set(row, col, weight);
        return this;
    }

    public OptObjective set(final OptVariable row, final OptVariable col, final boolean weight) {
        return this.set(row, col, OptModel.value(weight));
    }

    public OptObjective set(final OptVariable row, final OptVariable col, final double weight) {
        return this.set(row, col, OptModel.value(weight));
    }

    public OptObjective set(final OptVariable row, final OptVariable col, final long weight) {
        return this.set(row, col, OptModel.value(weight));
    }

}
