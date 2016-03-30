/*
 * Copyright (c) 2015, 2016 Oracle and/or its affiliates. All rights reserved. This
 * code is released under a tri EPL/GPL/LGPL license. You can use it,
 * redistribute it and/or modify it under the terms of the:
 *
 * Eclipse Public License version 1.0
 * GNU General Public License version 2
 * GNU Lesser General Public License version 2.1
 */
package org.jruby.truffle.core.format.format;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.NodeChildren;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.object.DynamicObject;
import org.jruby.truffle.RubyContext;
import org.jruby.truffle.core.Layouts;
import org.jruby.truffle.core.format.FormatNode;
import org.jruby.truffle.core.format.printf.PrintfTreeBuilder;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

@NodeChildren({
        @NodeChild(value = "spacePadding", type = FormatNode.class),
        @NodeChild(value = "zeroPadding", type = FormatNode.class),
        @NodeChild(value = "value", type = FormatNode.class),
})
public abstract class FormatIntegerNode extends FormatNode {

    private final char format;

    public FormatIntegerNode(RubyContext context, char format) {
        super(context);
        this.format = format;
    }

    @Specialization
    public byte[] format(int spacePadding, int zeroPadding, int value) {
        return doFormat(value, spacePadding, zeroPadding);
    }

    @Specialization
    public byte[] format(int spacePadding, int zeroPadding, long value) {
        return doFormat(value, spacePadding, zeroPadding);
    }

    @TruffleBoundary
    @Specialization(guards = "isRubyBignum(value)")
    public byte[] format(int spacePadding, int zeroPadding, DynamicObject value) {
        final BigInteger bigInteger = Layouts.BIGNUM.getValue(value);

        String formatted;

        switch (format) {
            case 'd':
            case 'i':
            case 'u':
                formatted = bigInteger.toString();
                break;

            case 'o':
                formatted = bigInteger.toString(8).toLowerCase(Locale.ENGLISH);
                break;

            case 'x':
                formatted = bigInteger.toString(16).toLowerCase(Locale.ENGLISH);
                break;

            case 'X':
                formatted = bigInteger.toString(16).toUpperCase(Locale.ENGLISH);
                break;

            default:
                throw new UnsupportedOperationException();
        }

        while (formatted.length() < spacePadding) {
            formatted = " " + formatted;
        }

        while (formatted.length() < zeroPadding) {
            formatted = "0" + formatted;
        }

        return formatted.getBytes(StandardCharsets.US_ASCII);
    }

    @TruffleBoundary
    protected byte[] doFormat(Object value, int spacePadding, int zeroPadding) {
        // TODO CS 3-May-15 write this without building a string and formatting

        final StringBuilder builder = new StringBuilder();

        builder.append("%");

        if (spacePadding != PrintfTreeBuilder.DEFAULT) {
            builder.append(" ");
            builder.append(spacePadding);

            if (zeroPadding != PrintfTreeBuilder.DEFAULT) {
                builder.append(".");
                builder.append(zeroPadding);
            }
        } else if (zeroPadding != PrintfTreeBuilder.DEFAULT) {
            builder.append("0");
            builder.append(zeroPadding);
        }

        builder.append(format);

        return String.format(builder.toString(), value).getBytes(StandardCharsets.US_ASCII);
    }

}
