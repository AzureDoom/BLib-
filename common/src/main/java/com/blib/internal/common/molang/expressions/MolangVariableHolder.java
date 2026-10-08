package com.blib.internal.common.molang.expressions;

import com.blib.internal.common.molang.math.IValue;
import com.blib.internal.common.molang.math.Variable;

public class MolangVariableHolder extends MolangValue {

    public Variable variable;

    public MolangVariableHolder(Variable variable, IValue value) {
        super(value);

        this.variable = variable;
    }

    /**
     * !!! THE VARIABLE IS ALLOWED TO BE NULL, AND DEREFERENCING IT CRASHED THE RENDER THREAD.
     * <p>
     * {@code MolangParser.ZERO} and {@code MolangParser.ONE} are both built as
     * {@code new MolangVariableHolder(null, new Constant(...))}, and ZERO is what the parser RETURNS WHENEVER AN
     * EXPRESSION FAILS TO PARSE - see the two catch blocks in {@code MolangParser.process}. So a single unparseable
     * expression turned a logged "Defaulted to 0" into a hard NullPointerException the moment that value was evaluated.
     * </p>
     * <p>
     * ⚠⚠ REPORTED FROM THE FIELD as a client crash when a nether empress is spawned:
     * {@code NullPointerException: Cannot invoke "Variable.set(double)" because "this.variable" is null}, thrown from
     * {@code AzKeyframeTransitioner.transitionRotation} while RENDERING THE ENTITY - so it takes the game down rather
     * than dropping one animation.
     * </p>
     * <p>
     * ⭐ A holder with no variable simply has nowhere to store the result. Returning the value and skipping the
     * assignment is exactly what ZERO and ONE are for, and restores the graceful default the parser intended.
     * </p>
     */
    @Override
    public double get() {
        double value = super.get();

        if (this.variable != null) {
            this.variable.set(value);
        }

        return value;
    }

    /** ⚠ Also null-safe: this is used in log messages, and a crash inside error reporting hides the real fault. */
    @Override
    public String toString() {
        return (this.variable == null ? "<constant>" : this.variable.getName()) + " = " + super.toString();
    }
}
