package com.blib.internal.common.molang.functions.query;

import com.blib.internal.common.molang.math.IValue;
import com.blib.internal.common.molang.MolangQueryContext;

/**
 * {@code query.has_armor_slot(slot)}: 1 if the armor slot (0 = head, 1 = chest, 2 = legs, 3 = feet) has an item.
 */
public class HasArmorSlot extends ContextQueryFunction {

    public HasArmorSlot(IValue[] values, String name) throws Exception {
        super(values, name);
    }

    @Override
    public int getRequiredArguments() {
        return 1;
    }

    @Override
    protected double evaluate(MolangQueryContext context) {
        return context.hasArmorSlot(intArg(0));
    }
}
