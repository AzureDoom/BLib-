package com.blib.internal.common.molang.functions;

import com.blib.internal.common.molang.math.IValue;
import com.blib.internal.common.molang.math.functions.Function;

/**
 * {@code query.in_range(value, min, max)}: 1 if {@code value} is between {@code min} and {@code max} (inclusive), else
 * 0.
 */
public class InRange extends Function {

    public InRange(IValue[] values, String name) throws Exception {
        super(values, name);
    }

    @Override
    public int getRequiredArguments() {
        return 3;
    }

    @Override
    public double get() {
        double value = this.getArg(0);

        return value >= this.getArg(1) && value <= this.getArg(2) ? 1 : 0;
    }
}
