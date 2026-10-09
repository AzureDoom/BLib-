/**
 * This class is a fork of the matching class found in the Geckolib repository. Original source:
 * https://github.com/bernie-g/geckolib Copyright © 2024 Bernie-G. Licensed under the MIT License.
 * https://github.com/bernie-g/geckolib/blob/main/LICENSE
 */
package com.blib.internal.common.molang.math.functions.classic;

import com.blib.internal.common.molang.math.IValue;
import com.blib.internal.common.molang.math.functions.Function;

public class Pi extends Function {

    public Pi(IValue[] values, String name) throws Exception {
        super(values, name);
    }

    @Override
    public double get() {
        return Math.PI;
    }
}
