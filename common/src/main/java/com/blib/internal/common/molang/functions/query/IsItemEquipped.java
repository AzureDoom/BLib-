package com.blib.internal.common.molang.functions.query;

import com.blib.internal.common.molang.math.IValue;
import com.blib.internal.common.molang.MolangQueryContext;

/**
 * {@code query.is_item_equipped([hand])}: 1 if the hand (0 = main hand, the default, 1 = offhand) holds an item.
 */
public class IsItemEquipped extends ContextQueryFunction {

    public IsItemEquipped(IValue[] values, String name) throws Exception {
        super(values, name);
    }

    @Override
    protected double evaluate(MolangQueryContext context) {
        return context.isItemEquipped(intArg(0));
    }
}
