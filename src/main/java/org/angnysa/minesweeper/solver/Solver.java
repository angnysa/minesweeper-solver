package org.angnysa.minesweeper.solver;

import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.ToString;
import lombok.extern.log4j.Log4j2;
import org.angnysa.minesweeper.game.Cell;
import org.angnysa.minesweeper.game.MineField;

import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * <p>Minesweeper solver.</p>
 *
 * <p>
 *     The solver works its way through the minefield from a single cell to
 *     the whole field or until no safe solution exist.
 * </p>
 * <p>
 *     The solver follows these rules:
 *     <ol>
 *         <li>
 *             Recursion is expensive for the stack and can be fatal with large
 *             fields on a default JVM. No recursion is allowed. Cells are not
 *             explored or flagged immediately but added to a work queue for
 *             later processing.
 *         </li>
 *         <li>
 *             Each explored cell is known to be surrounded by <code>M</code>
 *             mines and <code>C</code> unexplored or flagged neighbors,
 *             where <code>0 <= M <= C</code> and <code>0 <= C</code>. The
 *             tuple <code>(M, C)</code> is called a {@link MineGroup MineGroup}.
 *             When <code>M < C</code>, there is no way to know exactly which
 *             cell is trapped. When <code>M == C</code>, we know that every
 *             cell are trapped and they can be marked for flagging. When
 *             <code>M == (number of flagged cells in the group)</code>, we
 *             know that no cell is trapped and they can be marked for
 *             exploration. When the status of all cells is known, the group
 *             becomes <b>obvious</b>, the cells are explored or flagged and
 *             the group is deleted.
 *         </li>
 *         <li>
 *             When exploring a new cell and if the location of the surrounding
 *             mines is not obvious, a new MineGroup is created.
 *         </li>
 *         <li>
 *             When exploring a new cell, it is removed from any MineGroup that
 *             references it. Each group is checked for obviousness, and (if
 *             not) marked for interaction check.
 *         </li>
 *         <li>
 *             When flagging a new cell, it is removed from any MineGroup that
 *             references it and the group's mine counter is decremented (as
 *             one mine has been found). Each group is checked for obviousness,
 *             and (if not) marked for interaction check.
 *         </li>
 *         <li>
 *             Neighboring MineGroups interact with each other and share
 *             one or more cells. After creating or modifying a MineGroup, it
 *             should be checked with its neighbors to see if such interaction
 *             allows to reduce the uncertainty of each group. This is done by
 *             calculating the intersecting cells of two groups, and the
 *             minimum/maximum number of mines that both group allow on the
 *             intersecting cells. If only one value is allowed by both group
 *             (e.g. group #1 allows [1, 2] mines, group #2 allows [2, 4] mines,
 *             only 2 mines can be present in the intersection), then we can
 *             extract a new MineGroup (in this case, with 2 mines).
 *         </li>
 *     </ol>
 * </p>
 * <p>
 *     When all these rules have been exhausted, the field is explored as much
 *     as the solver could. Any remaining group marks an undecidable set of mine
 *     locations.
 * </p>
 * <p>
 *     {@link #solve(Cell)} can be called as many times as wanted to try and
 *     solve the whole minefield.
 * </p>
 */
@RequiredArgsConstructor
@Log4j2
public class Solver {

    /**
     * Represents a set of {@link #mineCount} mines in the cells {@link #options}.
     */
    @ToString
    public class MineGroup {
        private boolean obvious = false;
        @Getter private int mineCount;
        @Getter private final Set<Cell> options;

        private MineGroup(int mineCount, @NonNull Set<Cell> options) {
            this.mineCount = mineCount;
            this.options = new HashSet<>(options);
        }

        /**
         * Removes a cell from {@link #options}, and checks it for obviousness.
         *
         * @param cell The explored cell
         */
        private void onExplored(@NonNull Cell cell) {
            enterCall(this, "onExplored", cell);
            if (obvious) {
                throw new IllegalStateException();
            } else {
                if (options.remove(cell)) {
                    trace("Removed %s", cell);
                    validate();
                }
            }
            exitCall(this, "onExplored");
        }

        /**
         * Remove a cell from {@link #options}, decrements {@link #mineCount},
         * and checks it for obviousness.
         *
         * @param cell the flagged cell
         */
        private void onFlagged(@NonNull Cell cell) {
            enterCall(this, "onFlagged", cell);
            if (obvious) {
                throw new IllegalStateException();
            } else if (! cell.isFlagged()) {
                throw new IllegalArgumentException();
            } else if (!options.contains(cell)) {
                throw new IllegalArgumentException();
            } else {
                options.remove(cell);
                mineCount--;
                trace("Decremented mineCount to %d and removed %s", mineCount, cell);
                validate();
            }
            exitCall(this, "onFlagged");
        }

        /**
         * Removes all cells in the newly created intersection group from
         * {@link #options}, reduces {@link #mineCount} by the intersection's
         * mineCount, and checks for certainty.
         *
         * @param intersection the intersecting group
         */
        private void onIntersected(MineGroup intersection) {
            enterCall(this, "onIntersected", intersection);
            if (obvious) {
                throw new IllegalStateException();
            } else {
                intersection.getOptions().forEach(c -> unlinkCellGroup(c, this));
                options.removeAll(intersection.getOptions());
                mineCount -= intersection.mineCount;
                validate();
            }
            exitCall(this, "onIntersected");
        }

        /**
         * Check the group for correctness and certainty.
         */
        public void validate() {
            enterCall(this, "validate");
            for (Cell option : options) {
                if (option.isFlagged()) {
                    throw new IllegalArgumentException(option.toString());
                } else if (option.isExplored()) {
                    throw new IllegalArgumentException(option.toString());
                }
            }

            if (mineCount < 0) {
                throw new IllegalStateException(String.format("mineCount == %d < 0", mineCount));
            } else if (mineCount > options.size()) {
                throw new IllegalArgumentException(String.format("mineCount == %d > %d", mineCount, options.size()));
            }

            if (obvious) {
                throw new IllegalStateException();
            } else if (mineCount == 0) {
                exploreCellQueue.addAll(getOptions());
                getOptions().forEach(c -> unlinkCellGroup(c, this));
                mineGroups.remove(this);
                obvious = true;
                trace("Obvious, explore");
            } else if (mineCount == options.size()) {
                flagCellQueue.addAll(getOptions());
                getOptions().forEach(c -> unlinkCellGroup(c, this));
                mineGroups.remove(this);
                obvious = true;
                trace("Obvious, flag");
            } else {
                intersectGroupQueue.addLast(this);
            }
            exitCall(this, "validate");
        }

        @ToString.Include
        private int hash() {
            return System.identityHashCode(this);
        }
    }

    /**
     * The minefield to solve
     */
    @Getter @NonNull private final MineField mineField;

    /**
     * All existing mine groups
     */
    @Getter private final Set<MineGroup> mineGroups = new HashSet<>();

    /**
     * Map of cells referencing the groups that reference them. e.g:
     * <dl>
     *     <dt>Cell #1</dt>
     *     <dd>
     *         <ul>
     *             <li>Group #1 (Cell #1, Cell #2)</li>
     *             <li>Group #2 (Cell #1, Cell #3)</li>
     *         </ul>
     *     </dd>
     *     <dt>Cell #2</dt>
     *     <dd>
     *         <ul>
     *             <li>Group #1 (Cell #1, Cell #2)</li>
     *         </ul>
     *     <dt>Cell #3</dt>
     *     <dd>
     *         <ul>
     *             <li>Group #2 (Cell #1, Cell #3)</li>
     *         </ul>
     *     </dd>
     * </dl>
     */
    private final Map<Cell, Set<MineGroup>> mineGroupsByCell = new HashMap<>();

    /**
     * Queue of cells known to be safe but not yet explored.
     */
    private final Deque<Cell> exploreCellQueue = new LinkedList<>();

    /**
     * Queue of cells known to be trapped but not yet flagged
     */
    private final Deque<Cell> flagCellQueue = new LinkedList<>();

    /**
     * Queue of groups that have been modified but not yet checked for intersection
     */
    private final Deque<MineGroup> intersectGroupQueue = new LinkedList<>();

    /**
     * Attempts to solve {@link #mineField} from the given starting point.
     *
     * @param start The starting cell
     * @return Whether the minefield has been completely solved.
     */
    public boolean solve(@NonNull Cell start) {
        enterCall(this, "solve", start);

        try {
            exploreCellQueue.add(start);

            boolean worked;
            do {
                worked = processQueue(exploreCellQueue, this::explore)
                        || processQueue(flagCellQueue, this::flag)
                        || processQueue(intersectGroupQueue, this::intersectMineGroup)
                        ;
            } while (worked);

            return mineGroups.isEmpty();
        } finally {
            exitCall(this, "solve");
        }
    }

    /**
     * Calls {@link Consumer#accept(Object) consumer.accept()} on each element
     * of <code>queue</code>.
     *
     * @param queue The queue of object to process
     * @param consumer The object processor
     * @param <T> The object type
     * @return Whether at least one element was present in the queue.
     */
    private <T> boolean processQueue(Deque<T> queue, Consumer<T> consumer) {
        enterCall(this, "processQueue", queue, consumer);
        boolean worked = false;
        while (! queue.isEmpty()) {
            T obj = queue.removeFirst();
            consumer.accept(obj);
            worked = true;
        }
        exitCall(this, "processQueue", worked);
        return worked;
    }

    /**
     * Explore a cell, create a group, and call {@link MineGroup#onExplored(Cell)} on each referencing group.
     *
     * @param cell the cell to explore, assumed safe.
     */
    private void explore(@NonNull Cell cell) {
        enterCall(this, "explore", cell);

        if (! cell.isExplored()) {
            cell.explore();
            mineGroupsByCell.getOrDefault(cell, Collections.emptySet()).forEach(g -> g.onExplored(cell));
            mineGroupsByCell.remove(cell);

            // create mine group
            AtomicInteger mines = new AtomicInteger(cell.getSurroundingMinesCount());
            Set<Cell> options = new HashSet<>();
            cell.forEachNeighbor(n -> {
                if (n.isFlagged()) {
                    mines.decrementAndGet();
                } else if (! n.isExplored()) {
                    options.add(n);
                }
            });

            createMineGroup(mines.get(), options);
        }

        exitCall(this, "explore");
    }

    /**
     * Flag a cell, and call {@link MineGroup#onFlagged(Cell)} on each referencing group.
     *
     * @param cell the cell to flag, assumed trapped.
     */
    private void flag(@NonNull Cell cell) {
        enterCall(this, "flag", cell);
        if (! cell.isFlagged()) {
            cell.flag();
            mineGroupsByCell.getOrDefault(cell, Collections.emptySet()).forEach(g -> g.onFlagged(cell));
            mineGroupsByCell.remove(cell);
        }
        exitCall(this, "flag");
    }

    /**
     * Create a group for the given parameter or return it if it already exist.
     *
     * @param mineCount The number of mines
     * @param options The cells on which they are
     * @return The new or existing group
     */
    private MineGroup createMineGroup(int mineCount, Set<Cell> options) {
        enterCall(this, "createMineGroup", mineCount, options);

        final MineGroup group = new MineGroup(mineCount, options);

        //search duplicate
        for (Cell cell : group.getOptions()) {
            for (MineGroup g : mineGroupsByCell.getOrDefault(cell, Collections.emptySet())) {
                if (g.getOptions().equals(options) && g.getMineCount() == mineCount) {
                    // found
                    trace("Duplicates: %s", g);
                    exitCall(this, "createMineGroup", g);
                    return g;
                }
            }
        }

        // new group
        for (Cell opt : group.getOptions()) {
            linkCellGroup(opt, group);
        }

        mineGroups.add(group);
        group.validate();

        exitCall(this, "createMineGroup", group);
        return group;
    }

    /**
     * Adds an entry in {@link #mineGroupsByCell}
     *
     * @param cell The cell (must be part of <code>group</code>).
     * @param group The group
     */
    private void linkCellGroup(@NonNull Cell cell, @NonNull MineGroup group) {
        enterCall(this, "linkCellGroup", cell, group);
        if (!group.getOptions().contains(cell)) {
            throw new IllegalArgumentException("cell");
        }

        mineGroupsByCell.computeIfAbsent(cell, c -> new HashSet<>()).add(group);
        exitCall(this, "linkCellGroup");
    }

    /**
     * Removes an entry in {@link #mineGroupsByCell}
     *
     * @param cell The cell (must be part of <code>group</code>).
     * @param group The group
     */
    private void unlinkCellGroup(@NonNull Cell cell, @NonNull MineGroup group) {
        enterCall(this, "unlinkCellGroup", cell, group);
        if (!group.getOptions().contains(cell)) {
            throw new IllegalArgumentException("cell");
        }

        if (mineGroupsByCell.containsKey(cell)) {
            mineGroupsByCell.get(cell).remove(group);
            if (mineGroupsByCell.get(cell).isEmpty()) {
                mineGroupsByCell.remove(cell);
            }
        }
        exitCall(this, "unlinkCellGroup");
    }

    /**
     * Tries to find the first intersection of <code>group</code>.
     *
     * @param group The group to inspect.
     */
    private void intersectMineGroup(MineGroup group) {
        enterCall(this, "intersectMineGroup", group);
        if (!group.obvious) {
            // list all groups that intersect with the given group
            Set<MineGroup> intersections = new HashSet<>();
            for (Cell option : group.getOptions()) {
                intersections.addAll(mineGroupsByCell.get(option));
            }

            // try to intersect them and stop at the first success
            // the method will schedule both groups and the intersection to be re-intersected later
            for (MineGroup group2 : intersections) {
                if (group2 != group) {
                    if (calculateAndAddGroupIntersection(group, group2)) {
                        break;
                    }
                }
            }
        }
        exitCall(this, "intersectMineGroup");
    }

    /**
     * Tries to calculate the intersection of both groups.
     *
     * On success, a new group is created, added to the known groups, and both
     * groups are modified. They are also all added for intersection calculation.
     *
     * @param group1 The first group
     * @param group2 The second group
     * @return Whether an intersecting group was found
     */
    private boolean calculateAndAddGroupIntersection(@NonNull MineGroup group1, @NonNull MineGroup group2) {
        enterCall(this, "calculateAndAddGroupIntersection", group1, group2);
        Set<Cell> intersectionOptions = new HashSet<>(group1.getOptions());
        intersectionOptions.retainAll(group2.getOptions());

        if (intersectionOptions.size() > 0) {
            // groupXMinMineCount: minimum number of mines that the group X (1 or 2) expects in the intersection
            // groupXMaxMineCount: maximum number of mines that the group X (1 or 2) expects in the intersection
            // the intersection group is accepted if the intersection of both range is a single value

            int group1MinMineCount = Math.max(0, group1.getMineCount() - (group1.getOptions().size() - intersectionOptions.size()));
            int group1MaxMineCount = Math.min(group1.getMineCount(), intersectionOptions.size());
            int group2MinMineCount = Math.max(0, group2.getMineCount() - (group2.getOptions().size() - intersectionOptions.size()));
            int group2MaxMineCount = Math.min(group2.getMineCount(), intersectionOptions.size());

            int intersectionMineCount;
            if (group1MinMineCount == group2MaxMineCount) {
                intersectionMineCount = group1MinMineCount;
            } else if (group2MinMineCount == group1MaxMineCount) {
                intersectionMineCount = group2MinMineCount;
            } else {
                exitCall(this, "calculateAndAddGroupIntersection", false);
                return false;
            }

            MineGroup intersection = createMineGroup(intersectionMineCount, intersectionOptions);
            trace("Intersection group: %s", intersection);

            group1.onIntersected(intersection);
            group2.onIntersected(intersection);

            exitCall(this, "calculateAndAddGroupIntersection", true);
            return true;
        } else {
            exitCall(this, "calculateAndAddGroupIntersection", false);
            return false;
        }
    }








    /**
     * The spaces to prefix the trace lines with.
     * Shortened or expanded as {@link #enterCall(Object, String, Object...)},
     * {@link #exitCall(Object, String)} or {@link #exitCall(Object, String, Object)} are called.
     */
    private String tracePrefix = "";
    private static final String TRACE_PREFIX_INCREMENT = "  ";

    /**
     * Logs a new trace entry.
     *
     * Both parameters are pased directly to {@link String#format(String, Object...)}.
     * <code>params</code> can contain {@link Supplier} instances. If tracing
     * is enabled, these are called and replace the values passed to
     * {@link String#format(String, Object...)}.
     *
     * @param format The {@link String#format(String, Object...)} format string.
     * @param params The {@link String#format(String, Object...)} parameters.
     */
    private void trace(@NonNull String format, @NonNull Object... params) {
        if (log.isTraceEnabled()) {
            for (int i = 0; i < params.length; i++) {
                if (params[i] instanceof Supplier) {
                    params[i] = ((Supplier<?>) params[i]).get();
                }
            }
            log.trace(tracePrefix +"TRACE "+String.format(format, params));
        }
    }

    /**
     * Traces the beginning of a method call.
     *
     * @param target The instance owning the method
     * @param method The called method name
     * @param params The method parameters
     */
    private void enterCall(@NonNull Object target, @NonNull String method, @NonNull Object... params) {
        if (log.isTraceEnabled()) {
            log.trace(String.format("%sCALL %s(%d)#%s %s"
                    , tracePrefix
                    , target.getClass().getSimpleName()
                    , System.identityHashCode(target)
                    , method
                    , Arrays.toString(params)));
            tracePrefix += TRACE_PREFIX_INCREMENT;
        }
    }

    /**
     * Traces the end of a method call with return value.
     *
     * @param target The instance owning the method
     * @param method The called method name
     * @param retVal The method return value
     */
    private void exitCall(@NonNull Object target, @NonNull String method, @NonNull Object retVal) {
        if (log.isTraceEnabled()) {
            tracePrefix = tracePrefix.substring(0, tracePrefix.length()-TRACE_PREFIX_INCREMENT.length());
            log.trace(String.format("%sRET %s(%d)#%s (%s) %s"
                    , tracePrefix
                    , target.getClass().getSimpleName()
                    , System.identityHashCode(target)
                    , method
                    , retVal.getClass().getSimpleName()
                    , retVal));
        }
    }

    /**
     * Traces the end of a method call without return value.
     *
     * @param target The instance owning the method
     * @param method The called method name
     */
    private void exitCall(@NonNull Object target, @NonNull String method) {
        if (log.isTraceEnabled()) {
            tracePrefix = tracePrefix.substring(0, tracePrefix.length()-TRACE_PREFIX_INCREMENT.length());
            log.trace(String.format("%sRET %s(%d)#%s"
                    , tracePrefix
                    , target.getClass().getSimpleName()
                    , System.identityHashCode(target)
                    , method));
        }
    }
}
