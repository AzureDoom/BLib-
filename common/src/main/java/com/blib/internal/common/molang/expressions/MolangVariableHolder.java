/**
 * This class is a fork of the matching class found in the Geckolib repository. Original source:
 * https://github.com/bernie-g/geckolib Copyright © 2024 Bernie-G. Licensed under the MIT License.
 * https://github.com/bernie-g/geckolib/blob/main/LICENSE
 */
package com.blib.internal.common.molang.expressions;

import com.blib.mod.BLib;
import com.blib.internal.common.molang.math.IValue;
import com.blib.internal.common.molang.math.Variable;

/**
 * Extension of {@link MolangValue} that additionally sets the value of a provided {@link Variable} when being called.
 */
public class MolangVariableHolder extends MolangValue {

    public Variable variable;

    public MolangVariableHolder(Variable variable, IValue value) {
        super(value);

        this.variable = variable;
    }

    @Override
    public double get() {
        var value = super.get();

        if (this.variable == null) {
            BLib.LOGGER.error("Variable is null, defaulting to 0");
            return 0;
        }

        this.variable.set(value);
        return value;
    }

    @Override
    public String toString() {
        return this.variable.getName() + " = " + super.toString();
    }
}
