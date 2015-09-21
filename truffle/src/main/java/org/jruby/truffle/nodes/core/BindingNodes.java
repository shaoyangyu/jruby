/*
 * Copyright (c) 2013, 2015 Oracle and/or its affiliates. All rights reserved. This
 * code is released under a tri EPL/GPL/LGPL license. You can use it,
 * redistribute it and/or modify it under the terms of the:
 *
 * Eclipse Public License version 1.0
 * GNU General Public License version 2
 * GNU Lesser General Public License version 2.1
 */
package org.jruby.truffle.nodes.core;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.ImportStatic;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.FrameSlot;
import com.oracle.truffle.api.frame.MaterializedFrame;
import com.oracle.truffle.api.object.DynamicObject;
import com.oracle.truffle.api.source.SourceSection;
import org.jruby.truffle.nodes.RubyGuards;
import org.jruby.truffle.nodes.locals.ReadFrameSlotNode;
import org.jruby.truffle.nodes.locals.ReadFrameSlotNodeGen;
import org.jruby.truffle.nodes.locals.WriteFrameSlotNode;
import org.jruby.truffle.nodes.locals.WriteFrameSlotNodeGen;
import org.jruby.truffle.nodes.objects.AllocateObjectNode;
import org.jruby.truffle.nodes.objects.AllocateObjectNodeGen;
import org.jruby.truffle.runtime.RubyArguments;
import org.jruby.truffle.runtime.RubyContext;
import org.jruby.truffle.runtime.ThreadLocalObject;
import org.jruby.truffle.runtime.control.RaiseException;
import org.jruby.truffle.runtime.core.ArrayOperations;
import org.jruby.truffle.runtime.layouts.Layouts;

@CoreClass(name = "Binding")
public abstract class BindingNodes {

    public static DynamicObject createBinding(RubyContext context, MaterializedFrame frame) {
        final Object[] arguments = frame.getArguments();

        final MaterializedFrame bindingFrame = Truffle.getRuntime().createMaterializedFrame(
                RubyArguments.pack(
                        RubyArguments.getMethod(arguments),
                        frame,
                        RubyArguments.getSelf(arguments),
                        RubyArguments.getBlock(arguments),
                        RubyArguments.extractUserArguments(arguments)),
                new FrameDescriptor(context.getCoreLibrary().getNilObject()));

        return Layouts.BINDING.createBinding(context.getCoreLibrary().getBindingFactory(), bindingFrame);
    }

    protected static class FrameSlotAndDepth {
        private final FrameSlot slot;
        private final int depth;

        public FrameSlotAndDepth(FrameSlot slot, int depth) {
            this.slot = slot;
            this.depth = depth;
        }
    }

    public static FrameDescriptor getFrameDescriptor(DynamicObject binding) {
        assert RubyGuards.isRubyBinding(binding);
        return Layouts.BINDING.getFrame(binding).getFrameDescriptor();
    }

    public static FrameSlotAndDepth findFrameSlotOrNull(DynamicObject binding, DynamicObject symbol) {
        assert RubyGuards.isRubyBinding(binding);
        assert RubyGuards.isRubySymbol(symbol);

        final String identifier = Layouts.SYMBOL.getString(symbol);

        int depth = 0;
        MaterializedFrame frame = Layouts.BINDING.getFrame(binding);

        while (frame != null) {
            final FrameSlot frameSlot = frame.getFrameDescriptor().findFrameSlot(identifier);
            if (frameSlot != null) {
                return new FrameSlotAndDepth(frameSlot, depth);
            }

            frame = RubyArguments.getDeclarationFrame(frame.getArguments());
            depth++;
        }
        return null;
    }

    @CoreMethod(names = { "dup", "clone" })
    public abstract static class DupNode extends UnaryCoreMethodNode {

        @Child private AllocateObjectNode allocateObjectNode;

        public DupNode(RubyContext context, SourceSection sourceSection) {
            super(context, sourceSection);
            allocateObjectNode = AllocateObjectNodeGen.create(context, sourceSection, null, null);
        }

        @Specialization
        public DynamicObject dup(DynamicObject binding) {
            DynamicObject copy = allocateObjectNode.allocate(
                    Layouts.BASIC_OBJECT.getLogicalClass(binding),
                    copyFrame(Layouts.BINDING.getFrame(binding)));
            return copy;
        }

        private MaterializedFrame copyFrame(MaterializedFrame frame) {
            final MaterializedFrame copy = Truffle.getRuntime().createMaterializedFrame(frame.getArguments(), frame.getFrameDescriptor().copy());
            for (FrameSlot slot : frame.getFrameDescriptor().getSlots()) {
                copy.setObject(copy.getFrameDescriptor().findFrameSlot(slot.getIdentifier()), frame.getValue(slot));
            }
            return copy;
        }

    }

    @ImportStatic(BindingNodes.class)
    @CoreMethod(names = "local_variable_get", required = 1)
    public abstract static class LocalVariableGetNode extends CoreMethodArrayArgumentsNode {

        private final DynamicObject dollarUnderscore;

        public LocalVariableGetNode(RubyContext context, SourceSection sourceSection) {
            super(context, sourceSection);
            dollarUnderscore = getSymbol("$_");
        }

        @Specialization(guards = {
                "isRubySymbol(symbol)",
                "symbol == cachedSymbol",
                "!isLastLine(cachedSymbol)",
                "getFrameDescriptor(binding) == cachedFrameDescriptor",
        }, limit = "getCacheLimit()")
        public Object localVariableGetCached(DynamicObject binding, DynamicObject symbol,
                                             @Cached("symbol") DynamicObject cachedSymbol,
                                             @Cached("getFrameDescriptor(binding)") FrameDescriptor cachedFrameDescriptor,
                                             @Cached("findFrameSlotOrNull(binding, symbol)") FrameSlotAndDepth cachedFrameSlot,
                                             @Cached("createReadNode(cachedFrameSlot)") ReadFrameSlotNode readLocalVariableNode) {
            if (cachedFrameSlot == null) {
                CompilerDirectives.transferToInterpreter();
                throw new RaiseException(getContext().getCoreLibrary().nameErrorLocalVariableNotDefined(Layouts.SYMBOL.getString(symbol), binding, this));
            } else {
                final MaterializedFrame frame = RubyArguments.getDeclarationFrame(Layouts.BINDING.getFrame(binding), cachedFrameSlot.depth);
                return readLocalVariableNode.executeRead(frame);
            }
        }

        @TruffleBoundary
        @Specialization(guards = { "isRubySymbol(symbol)", "!isLastLine(symbol)" })
        public Object localVariableGetUncached(DynamicObject binding, DynamicObject symbol) {
            final FrameSlotAndDepth frameSlot = findFrameSlotOrNull(binding, symbol);
            if (frameSlot == null) {
                throw new RaiseException(getContext().getCoreLibrary().nameErrorLocalVariableNotDefined(Layouts.SYMBOL.getString(symbol), binding, this));
            } else {
                final MaterializedFrame frame = RubyArguments.getDeclarationFrame(Layouts.BINDING.getFrame(binding), frameSlot.depth);
                return frame.getValue(frameSlot.slot);
            }
        }

        @TruffleBoundary
        @Specialization(guards = {"isRubySymbol(symbol)", "isLastLine(symbol)"})
        public Object localVariableGetLastLine(DynamicObject binding, DynamicObject symbol) {
            final MaterializedFrame frame = Layouts.BINDING.getFrame(binding);
            final FrameSlot frameSlot = frame.getFrameDescriptor().findFrameSlot(Layouts.SYMBOL.getString(symbol));

            if (frameSlot == null) {
                throw new RaiseException(getContext().getCoreLibrary().nameErrorLocalVariableNotDefined(Layouts.SYMBOL.getString(symbol), binding, this));
            }

            final Object value = frame.getValue(frameSlot);
            if (value instanceof ThreadLocalObject) {
                return ((ThreadLocalObject) value).get();
            } else {
                return value;
            }
        }

        protected ReadFrameSlotNode createReadNode(FrameSlotAndDepth frameSlot) {
            if (frameSlot == null) {
                return null;
            } else {
                return ReadFrameSlotNodeGen.create(frameSlot.slot);
            }
        }

        protected boolean isLastLine(DynamicObject symbol) {
            return symbol == dollarUnderscore;
        }

        protected int getCacheLimit() {
            return getContext().getOptions().BINDING_LOCAL_VARIABLE_CACHE;
        }

    }

    @ImportStatic(BindingNodes.class)
    @CoreMethod(names = "local_variable_set", required = 2)
    public abstract static class LocalVariableSetNode extends CoreMethodArrayArgumentsNode {

        private final DynamicObject dollarUnderscore;

        public LocalVariableSetNode(RubyContext context, SourceSection sourceSection) {
            super(context, sourceSection);
            dollarUnderscore = getSymbol("$_");
        }

        @Specialization(guards = {
                "isRubySymbol(symbol)",
                "!isLastLine(symbol)",
                "getFrameDescriptor(binding) == cachedFrameDescriptor",
                "symbol == cachedSymbol"
        }, limit = "getCacheLimit()")
        public Object localVariableSetCached(DynamicObject binding, DynamicObject symbol, Object value,
                                             @Cached("symbol") DynamicObject cachedSymbol,
                                             @Cached("getFrameDescriptor(binding)") FrameDescriptor cachedFrameDescriptor,
                                             @Cached("findFrameSlot(binding, symbol)") FrameSlotAndDepth cachedFrameSlot,
                                             @Cached("createWriteNode(cachedFrameSlot)") WriteFrameSlotNode writeLocalVariableNode) {
            final MaterializedFrame frame = RubyArguments.getDeclarationFrame(Layouts.BINDING.getFrame(binding), cachedFrameSlot.depth);
            return writeLocalVariableNode.executeWrite(frame, value);
        }

        @TruffleBoundary
        @Specialization(guards = { "isRubySymbol(symbol)", "!isLastLine(symbol)" })
        public Object localVariableSetUncached(DynamicObject binding, DynamicObject symbol, Object value) {
            final FrameSlotAndDepth frameSlot = findFrameSlot(binding, symbol);
            final MaterializedFrame frame = RubyArguments.getDeclarationFrame(Layouts.BINDING.getFrame(binding), frameSlot.depth);
            frame.setObject(frameSlot.slot, value);
            return value;
        }

        @TruffleBoundary
        @Specialization(guards = {"isRubySymbol(symbol)", "isLastLine(symbol)"})
        public Object localVariableSetLastLine(DynamicObject binding, DynamicObject symbol, Object value) {
            final MaterializedFrame frame = Layouts.BINDING.getFrame(binding);
            final FrameSlot frameSlot = frame.getFrameDescriptor().findFrameSlot(Layouts.SYMBOL.getString(symbol));
            frame.setObject(frameSlot, ThreadLocalObject.wrap(getContext(), value));
            return value;
        }

        protected FrameSlotAndDepth findFrameSlot(DynamicObject binding, DynamicObject symbol) {
            final FrameSlotAndDepth frameSlot = BindingNodes.findFrameSlotOrNull(binding, symbol);
            if (frameSlot == null) {
                final FrameSlot newSlot = Layouts.BINDING.getFrame(binding).getFrameDescriptor().addFrameSlot(Layouts.SYMBOL.getString(symbol));
                return new FrameSlotAndDepth(newSlot, 0);
            } else {
                return frameSlot;
            }
        }

        protected WriteFrameSlotNode createWriteNode(FrameSlotAndDepth frameSlot) {
            return WriteFrameSlotNodeGen.create(frameSlot.slot);
        }

        protected boolean isLastLine(DynamicObject symbol) {
            return symbol == dollarUnderscore;
        }

        protected int getCacheLimit() {
            return getContext().getOptions().BINDING_LOCAL_VARIABLE_CACHE;
        }
    }

    @CoreMethod(names = "local_variables")
    public abstract static class LocalVariablesNode extends CoreMethodArrayArgumentsNode {

        public LocalVariablesNode(RubyContext context, SourceSection sourceSection) {
            super(context, sourceSection);
        }

        @TruffleBoundary
        @Specialization
        public DynamicObject localVariables(DynamicObject binding) {
            final DynamicObject array = Layouts.ARRAY.createArray(getContext().getCoreLibrary().getArrayFactory(), null, 0);

            MaterializedFrame frame = Layouts.BINDING.getFrame(binding);

            while (frame != null) {
                for (Object name : frame.getFrameDescriptor().getIdentifiers()) {
                    if (name instanceof String) {
                        ArrayOperations.append(array, getSymbol((String) name));
                    }
                }

                frame = RubyArguments.getDeclarationFrame(frame.getArguments());
            }

            return array;
        }
    }

    @CoreMethod(names = "allocate", constructor = true)
    public abstract static class AllocateNode extends CoreMethodArrayArgumentsNode {

        public AllocateNode(RubyContext context, SourceSection sourceSection) {
            super(context, sourceSection);
        }

        @TruffleBoundary
        @Specialization
        public DynamicObject allocate(DynamicObject rubyClass) {
            throw new RaiseException(getContext().getCoreLibrary().typeErrorAllocatorUndefinedFor(rubyClass, this));
        }

    }

}