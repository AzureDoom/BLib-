package com.blib.internal.common.molang.math.functions.utility;

import com.blib.internal.common.molang.math.IValue;
import com.blib.internal.common.molang.math.functions.Function;

/**
 * Min angle function Wraps an angle in degrees into the range [-180, 180)
 */
public class MinAngle extends Function {

    public MinAngle(IValue[] values, String name) throws Exception {
        super(values, name);
    }

    @Override
    public int getRequiredArguments() {
        return 1;
    }

    @Override
    public double get() {
        var angle = this.getArg(0) % 360.0;

        if (angle >= 180.0)
            angle -= 360.0;
        else if (angle < -180.0)
            angle += 360.0;

        return angle;
    }
}
