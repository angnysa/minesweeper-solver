package org.angnysa.minesweeper.game;

import java.util.function.Consumer;

/**
 * Represents a single cell of the {@link MineField}. Implementations must make sure each instance
 */
public interface Cell {
    /**
     * Whether the cell is already explored or not.
     *
     * @return Whether the cell is already explored or not.
     */
    boolean isExplored();

    /**
     * Whether the cell is already marked as being trapped or not.
     *
     * @return Whether the cell is already marked as being trapped or not.
     */
    boolean isFlagged();

    /**
     * Marks the cell as trapped.
     *
     * @throws IllegalStateException If the cell is already explored, or some
     * other condition prevents the operation from being performed.
     */
    void flag() throws IllegalStateException;

    /**
     * Marks the cell as explored.
     *
     * @throws IllegalStateException if the cell is trapped.
     */
    void explore() throws IllegalStateException;

    /**
     * Count the mines surrounding this cell.
     *
     * @return The number of mines surrounding this cell.
     * @throws IllegalStateException if the cell is not explored
     */
    int getSurroundingMinesCount() throws IllegalStateException;

    /**
     * Calls {@link Consumer#accept(Object) consumer.accept()} on each neighboring cell.
     *
     * @param consumer The callback
     */
    void forEachNeighbor(Consumer<Cell> consumer);
}
