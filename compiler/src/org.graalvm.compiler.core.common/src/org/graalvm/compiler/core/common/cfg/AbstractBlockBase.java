/*
 * Copyright (c) 2009, 2019, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package org.graalvm.compiler.core.common.cfg;

import static org.graalvm.compiler.core.common.cfg.AbstractControlFlowGraph.INVALID_BLOCK_ID;

import java.util.Comparator;

import org.graalvm.compiler.core.common.RetryableBailoutException;

/**
 * Abstract representation of a basic block in the Graal IR. A basic block is the longest sequence
 * of instructions without a jump in between. Each block is held in specific order by
 * {@code AbstractControlFlowGraph} (typically in a reverse post order fashion). Both the fronend of
 * Graal as well as the backend operate on the same abstract block representation.
 *
 * In reality only one subclass exists that is shared both by the frontend and backend. However, the
 * frontend builds, while scheduling the graph, the final control flow graph used by the backend.
 * Since Graal has a strict dependency separation between frontend and backend this abstract basic
 * block is the coupling API.
 */
public abstract class AbstractBlockBase<T extends AbstractBlockBase<T>> {
    public static final double[] EMPTY_PROBABILITY_ARRAY = new double[0];
    public static final double[] SINGLETON_PROBABILITY_ARRAY = new double[]{1.0};

    /**
     * Id of this basic block. The id is concurrently used as a unique identifier for the block as
     * well as it's index into the @{@link #getBlocks()} array of the associated
     * {@link AbstractControlFlowGraph}.
     */
    protected char id = INVALID_BLOCK_ID;
    /**
     * Block id of the dominator of this block. See
     * <a href="https://en.wikipedia.org/wiki/Dominator_(graph_theory)">dominator theory<a/> for
     * details.
     */
    private char dominator = INVALID_BLOCK_ID;
    /**
     * Block id of the first dominated block. A block can dominate more basic blocks: they are
     * connected sequentially via the {@link AbstractBlockBase#dominatedSibling} index pointer into
     * the {@link #getBlocks()}array.
     */
    private char firstDominated = INVALID_BLOCK_ID;
    /**
     * The dominated sibling of this block. See {@link AbstractBlockBase#firstDominated} for
     * details.
     */
    private char dominatedSibling = INVALID_BLOCK_ID;
    /**
     * See {@link #getDominatorDepth()}.
     */
    protected char domDepth = 0;
    /**
     * Dominator number of this block: the dominator number is assigned for each basic block when
     * building the dominator tree. It is a numbering scheme used for fast and efficient dominance
     * checks. It attributes each basic block a numerical value that adheres to the following
     * constraints
     * <ul>
     * <li>b1.domNumber > b2.domNumber iff b1 dominates b2</li>
     * <li>b1.domNumber == b2.domNumber iff b1 == b2</li>
     * </ul>
     *
     * This means all dominated blocks between {@code this} and the deepest dominated block in
     * dominator tree are within the {@code [domNumber;maxDomNumber]} interval.
     */
    private char domNumber = INVALID_BLOCK_ID;
    /**
     * The maximum child dominator number, i.e., the maximum dom number of the deepest dominated
     * block along the particular branch on the dominator tree rooted at this.
     *
     * See {@link #domNumber} for details.
     */
    private char maxChildDomNumber = INVALID_BLOCK_ID;
    protected final AbstractControlFlowGraph<T> cfg;

    protected AbstractBlockBase(AbstractControlFlowGraph<T> cfg) {
        this.cfg = cfg;
    }

    public void setDominatorNumber(char domNumber) {
        this.domNumber = domNumber;
    }

    public void setMaxChildDomNumber(char maxChildDomNumber) {
        this.maxChildDomNumber = maxChildDomNumber;
    }

    public int getDominatorNumber() {
        if (domNumber == INVALID_BLOCK_ID) {
            return -1;
        }
        return domNumber;
    }

    public int getMaxChildDominatorNumber() {
        if (maxChildDomNumber == INVALID_BLOCK_ID) {
            return -1;
        }
        return this.maxChildDomNumber;
    }

    public int getId() {
        return id;
    }

    /**
     * Gets the block ordering of the graph in which this block lies. The {@linkplain #getId() id}
     * of each block in the graph is an index into the returned array.
     */
    public T[] getBlocks() {
        return cfg.getBlocks();
    }

    public void setId(char id) {
        this.id = id;
    }

    public abstract int getPredecessorCount();

    public abstract int getSuccessorCount();

    private boolean contains(T key, boolean usePred) {
        for (int i = 0; i < (usePred ? getPredecessorCount() : getSuccessorCount()); i++) {
            T b = usePred ? getPredecessorAt(i) : getSuccessorAt(i);
            if (b == key) {
                return true;
            }
        }
        return false;
    }

    public boolean containsPred(T key) {
        return contains(key, true);
    }

    public boolean containsSucc(T key) {
        return contains(key, false);
    }

    public abstract T getPredecessorAt(int predIndex);

    public abstract T getSuccessorAt(int succIndex);

    public abstract double getSuccessorProbabilityAt(int succIndex);

    public T getDominator() {
        return dominator != INVALID_BLOCK_ID ? cfg.getBlocks()[dominator] : null;
    }

    /**
     * Returns the next dominator of this block that is either in the same loop of this block or in
     * an outer loop.
     *
     * @return the next dominator while skipping over loops
     */
    public T getDominatorSkipLoops() {
        T d = getDominator();

        if (d == null) {
            // We are at the start block and don't have a dominator.
            return null;
        }

        if (isLoopHeader()) {
            // We are moving out of current loop => just return dominator.
            assert d.getLoopDepth() == getLoopDepth() - 1;
            assert d.getLoop() != getLoop();
            return d;
        }

        while (d.getLoop() != getLoop()) {
            // We have a case where our dominator is in a different loop. Move further along
            // the domiantor tree until we hit our loop again.
            d = d.getDominator();
        }

        assert d.getLoopDepth() <= getLoopDepth();

        return d;
    }

    public void setDominator(T dominator) {
        this.dominator = safeCast(dominator.getId());
        this.domDepth = addExact(dominator.domDepth, 1);
    }

    public static char addExact(char x, int y) {
        int result = x + y;
        char res = (char) (x + y);
        if (res != result) {
            throw new RetryableBailoutException("Graph too large to safely compile in reasonable time. Dominator tree depth ids create numerical overflows");
        }
        return res;
    }

    public static char safeCast(int i) {
        if (i < 0 || i > AbstractControlFlowGraph.LAST_VALID_BLOCK_INDEX) {
            throw new RetryableBailoutException("Graph too large to safely compile in reasonable time.");
        }
        return (char) i;
    }

    /**
     * Level in the dominator tree starting with 0 for the start block.
     */
    public int getDominatorDepth() {
        return domDepth;
    }

    public T getFirstDominated() {
        return firstDominated != INVALID_BLOCK_ID ? cfg.getBlocks()[firstDominated] : null;
    }

    public void setFirstDominated(T block) {
        this.firstDominated = safeCast(block.getId());
    }

    public T getDominatedSibling() {
        return dominatedSibling != INVALID_BLOCK_ID ? cfg.getBlocks()[dominatedSibling] : null;
    }

    public void setDominatedSibling(T block) {
        if (block != null) {
            this.dominatedSibling = safeCast(block.getId());
        }
    }

    @Override
    public String toString() {
        return "B" + (int) id;
    }

    public abstract int getLinearScanNumber();

    public abstract void setLinearScanNumber(int linearScanNumber);

    public abstract boolean isAligned();

    public abstract void setAlign(boolean align);

    public abstract boolean isExceptionEntry();

    public abstract Loop<T> getLoop();

    public abstract char getLoopDepth();

    public abstract void delete();

    public abstract boolean isLoopEnd();

    public abstract boolean isLoopHeader();

    /**
     * If this block {@linkplain #isLoopHeader() is a loop header}, returns the number of the loop's
     * backedges. Note that due to control flow optimizations after computing loops this value may
     * differ from that computed via {@link #getLoop()}. Returns -1 if this is not a loop header.
     */
    public abstract int numBackedges();

    public abstract T getPostdominator();

    public abstract double getRelativeFrequency();

    public abstract T getDominator(int distance);

    public abstract boolean isModifiable();

    @Override
    public int hashCode() {
        return id;
    }

    public static class BlockIdComparator implements Comparator<AbstractBlockBase<?>> {
        @Override
        public int compare(AbstractBlockBase<?> o1, AbstractBlockBase<?> o2) {
            return Integer.compare(o1.getId(), o2.getId());
        }
    }

    public static final BlockIdComparator BLOCK_ID_COMPARATOR = new BlockIdComparator();
}
