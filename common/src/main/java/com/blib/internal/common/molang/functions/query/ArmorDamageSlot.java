package com.blib.internal.common.molang.functions.query;

import com.blib.internal.common.molang.math.IValue;
import com.blib.internal.common.molang.MolangQueryContext;

/**
 * {@code query.armor_damage_slot(slot)}: the damage value of the item in the armor slot.
 */
public class ArmorDamageSlot extends ContextQueryFunction {

    public ArmorDamageSlot(IValue[] values, String name) throws Exception {
        super(values, name);
    }

    @Override
    public int getRequiredArguments() {
        return 1;
    }

    @Override
    protected double evaluate(MolangQueryContext context) {
        return context.armorDamageSlot(intArg(0));
    }
}
