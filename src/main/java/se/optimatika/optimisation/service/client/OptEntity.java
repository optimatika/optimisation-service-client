package se.optimatika.optimisation.service.client;

import java.math.BigDecimal;

/**
 * Base class for named optimisation model entities with optional lower and upper bounds.
 */
abstract class OptEntity {

    private BigDecimal myLower;
    private final String myName;
    private BigDecimal myUpper;

    OptEntity(final String name) {
        super();
        myName = name;
    }

    BigDecimal getLower() {
        return myLower;
    }

    String getName() {
        return myName;
    }

    BigDecimal getUpper() {
        return myUpper;
    }

    OptEntity level(final BigDecimal level) {
        myLower = level;
        myUpper = level;
        return this;
    }

    OptEntity lower(final BigDecimal lower) {
        myLower = lower;
        return this;
    }

    OptEntity upper(final BigDecimal upper) {
        myUpper = upper;
        return this;
    }

}
