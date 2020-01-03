package org.angnysa.minesweeper;

import org.angnysa.minesweeper.game.Cell;
import org.angnysa.minesweeper.game.Random2DSquareGridMineField;
import org.angnysa.minesweeper.solver.Solver;

import java.util.Random;

public class Main {
    private static final int WIDTH = 30;
    private static final int HEIGHT = 16;
    private static final int MINES = (WIDTH*HEIGHT)*20/100;
    private static final long SEED = new Random().nextLong();
    private static final int SOLVE_X = WIDTH/2;
    private static final int SOLVE_Y = HEIGHT/2;
    private static final int SELECT_CELL_ATTEMPTS = 1000;
    private static final int MAX_SOLVE_ATTEMPTS = 100;

    public static void main(String[] args) {

        System.out.println("seed: "+SEED);
        System.out.println("Creating ...");
        Random rng = new Random(SEED);
        Random2DSquareGridMineField mineField = new Random2DSquareGridMineField(WIDTH, HEIGHT, MINES, rng);

        Solver solver = new Solver(mineField);

        System.out.println("Solving ...");

        long start=System.nanoTime();
        Cell cell = mineField.getCell(SOLVE_X, SOLVE_Y);
        int solveAttempts=1;
        int cellAttempt;
        try {
            do {
                System.out.println(String.format("Solve attempt %d starting at cell %s", solveAttempts, cell));
                solver.solve(cell);
                solveAttempts++;
                cellAttempt = 1;
                do {
                    cell = mineField.getCell(rng.nextInt(WIDTH), rng.nextInt(HEIGHT));
                    cellAttempt++;
                } while ((cell.isExplored() || cell.isFlagged()) && cellAttempt < SELECT_CELL_ATTEMPTS);
            } while (solveAttempts <= MAX_SOLVE_ATTEMPTS && cellAttempt < SELECT_CELL_ATTEMPTS && !solver.getMineGroups().isEmpty());
        } catch (Exception e) {
            e.printStackTrace(System.out);
        } finally {
            long delta = System.nanoTime()-start;
            System.out.println();
            mineField.display();
            System.out.println();
            solver.getMineGroups().forEach(System.out::println);
            System.out.println("Done: "+formatNs(delta));
        }
    }

    private static String formatNs(long nano) {
        final String[] units = new String[] {"d", "h", "m", "s", "ms", "µs", "ns"};
        final int[] factors = new int[] {24, 60, 60, 1000, 1000, 1000};

        StringBuilder res = new StringBuilder();

        long amount = nano;
        for (int i=factors.length-1; i>=0 && amount>0; i--) {
            long mod = amount % factors[i];
            amount = amount / factors[i];

            if (res.length() > 0) {
                res.insert(0, ' ');
            }
            res.insert(0, units[i+1]);
            res.insert(0, mod);
        }

        return res.toString();
    }
}
