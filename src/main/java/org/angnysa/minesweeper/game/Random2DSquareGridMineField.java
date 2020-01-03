package org.angnysa.minesweeper.game;

import lombok.*;

import java.util.Random;
import java.util.function.Consumer;

/**
 * <p>
 *     In-memory, randomly generated implementation of {@link MineField}.
 * </p>
 * <p>
 *     The center cell [width/2, height/2] is guaranteed to not be mined.
 *     Flagging a non-trapped cell triggers an exception.
 * </p>
 */
public class Random2DSquareGridMineField implements MineField {

    @Getter
    @Setter(AccessLevel.PRIVATE)
    @RequiredArgsConstructor
    @EqualsAndHashCode(onlyExplicitlyIncluded = true)
    public class SquareCell implements Cell {
        @EqualsAndHashCode.Include
        private final int x;
        @EqualsAndHashCode.Include
        private final int y;
        @Getter(AccessLevel.PRIVATE) private boolean mined;
        private boolean explored;
        private boolean flagged;
        private int surroundingMinesCount=-1;

        public void flag() {
            if (! isMined()) {
                throw new IllegalStateException(String.format("Tried to flag non-mined cell %s", this));
            } else {
                setFlagged(true);
            }
        }

        public void explore() {
            if (isMined()) {
                throw new IllegalStateException(String.format("Explored mined cell %s", this));
            } else {
                setExplored(true);
            }
        }

        public int getSurroundingMinesCount() {
            if (! isExplored()) {
                throw new IllegalStateException(String.format("Cell %s is not explored", this));
            } else {
                if (surroundingMinesCount <= 0) {
                    surroundingMinesCount = 0;
                    forEachNeighbor(c -> {
                        if (((SquareCell) c).isMined()) {
                            surroundingMinesCount++;
                        }
                    });
                }

                return surroundingMinesCount;
            }
        }

        public void forEachNeighbor(Consumer<Cell> consumer) {
            for (int y = getY() - 1; y <= getY() + 1; y++) {
                if (y >= 0 && y < getHeight()) {
                    for (int x = getX() - 1; x <= getX() + 1; x++) {
                        if (x >= 0 && x < getWidth()
                                && (x != getX() || y != getY())) {
                            consumer.accept(getCell(x, y));
                        }
                    }
                }
            }
        }

        public char getChar() {
            if (isExplored()) {
                return " 12345678".charAt(getSurroundingMinesCount());
            } else if (isFlagged()) {
                return '!';
            } else if (isMined()) {
                return '*';
            } else {
                return '#';
            }
        }

        @Override
        public String toString() {
            return String.format("Cell@%d(%c)[%d, %d]"
                    , hashCode()
                    , isFlagged() ? 'F' : isMined() ? 'M' : isExplored() ? 'E' : 'U'
                    , x, y);
        }
    }

    private final SquareCell[][] map;

    public Random2DSquareGridMineField(int width, int height, int mines) {
        this(width, height, mines, new Random());
    }

    public Random2DSquareGridMineField(int width, int height, int mines, long seed) {
        this(width, height, mines, new Random(seed));
    }

    public Random2DSquareGridMineField(int width, int height, int mines, Random rng) {
        // create the map
        map = new SquareCell[height][width];
        for (int x=0; x<width; x++) {
            for (int y=0; y<height; y++) {
                map[y][x] = new SquareCell(x, y);
            }
        }

        // mine the field
        for (int m=0; m<mines; m++) {
            int x, y;
            do {
                x=rng.nextInt(width);
                y=rng.nextInt(height);
            } while (getCell(x, y).isMined() || (x==width/2 && y==height/2));

            getCell(x, y).setMined(true);
        }
    }

    public SquareCell getCell(int x, int y) {
        return map[y][x];
    }

    public int getWidth() {
        return map[0].length;
    }

    public int getHeight() {
        return map.length;
    }

    public void display() {
        for (int y=0; y<getHeight(); y++) {
            for (int x=0; x<getWidth(); x++) {
                System.out.print(getCell(x, y).getChar());
            }
            System.out.println(String.format(" %d", y));
        }
    }
}
