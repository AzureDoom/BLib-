package com.blib.internal.common.molang.functions.query;

import com.blib.internal.common.molang.math.IValue;
import com.blib.internal.common.molang.MolangQueryContext;

/**
 * {@code query.position_delta(axis)}: how far the entity is moving this tick on the given axis.
 */
public class PositionDelta extends ContextQueryFunction {

    public PositionDelta(IValue[] values, String name) throws Exception {
        super(values, name);
    }

    @Override
    public int getRequiredArguments() {
        return 1;
    }

    @Override
    protected double evaluate(MolangQueryContext context) {
        return context.positionDelta(intArg(0));
    }
}
