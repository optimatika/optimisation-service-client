package se.optimatika.optimisation.service.client;

import java.math.BigDecimal;

/**
 * A constraint in the optimisation model. Set coefficients with {@link #set(OptVariable, BigDecimal)}, an
 * optional constant term with {@link #constant(BigDecimal)}, and define bounds with
 * {@link #lower(BigDecimal)}, {@link #upper(BigDecimal)}, or {@link #level(BigDecimal)} (equality).
 * <p>
 * All setters return {@code this} for method chaining.
 */
public final class OptConstraint extends OptExpression {

    OptConstraint(final String name) {
        super(name);
    }

    @Override
    public OptConstraint constant(final BigDecimal constant) {
        super.constant(constant);
        return this;
    }

    public OptConstraint constant(final double constant) {
        return this.constant(OptModel.value(constant));
    }

    public OptConstraint constant(final long constant) {
        return this.constant(OptModel.value(constant));
    }

    @Override
    public OptConstraint level(final BigDecimal level) {
        super.level(level);
        return this;
    }

    public OptConstraint level(final boolean level) {
        return this.level(OptModel.value(level));
    }

    public OptConstraint level(final double level) {
        return this.level(OptModel.value(level));
    }

    public OptConstraint level(final long level) {
        return this.level(OptModel.value(level));
    }

    @Override
    public OptConstraint lower(final BigDecimal lower) {
        super.lower(lower);
        return this;
    }

    public OptConstraint lower(final boolean lower) {
        return this.lower(OptModel.value(lower));
    }

    public OptConstraint lower(final double lower) {
        return this.lower(OptModel.value(lower));
    }

    public OptConstraint lower(final long lower) {
        return this.lower(OptModel.value(lower));
    }

    @Override
    public OptConstraint set(final OptVariable variable, final BigDecimal coefficient) {
        super.set(variable, coefficient);
        return this;
    }

    public OptConstraint set(final OptVariable variable, final boolean coefficient) {
        return this.set(variable, OptModel.value(coefficient));
    }

    public OptConstraint set(final OptVariable variable, final double coefficient) {
        return this.set(variable, OptModel.value(coefficient));
    }

    public OptConstraint set(final OptVariable variable, final long coefficient) {
        return this.set(variable, OptModel.value(coefficient));
    }

    @Override
    public OptConstraint upper(final BigDecimal upper) {
        super.upper(upper);
        return this;
    }

    public OptConstraint upper(final boolean upper) {
        return this.upper(OptModel.value(upper));
    }

    public OptConstraint upper(final double upper) {
        return this.upper(OptModel.value(upper));
    }

    public OptConstraint upper(final long upper) {
        return this.upper(OptModel.value(upper));
    }

}
