package com.blib.internal.common.molang.functions.query;

import com.blib.internal.common.molang.math.IValue;
import com.blib.internal.common.molang.MolangQueryContext;

/**
 * {@code query.position(axis)}: the entity's world position on the given axis (0 = x, 1 = y, 2 = z).
 */
public class Position extends ContextQueryFunction {

    public Position(IValue[] values, String name) throws Exception {
        super(values, name);
    }

    @Override
    public int getRequiredArguments() {
        return 1;
    }

    @Override
    protected double evaluate(MolangQueryContext context) {
        return context.position(intArg(0));
    }
}
