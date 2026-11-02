package com.ee06.wooms.domain.sketch.entity;

import com.ee06.wooms.domain.sketch.dto.StrokeMessage;

/**
 * In-memory pixel canvas for one drawing room (keyed by woomsId).
 * Mirrors the role of {@code Channel} in the chat domain.
 * Cells hold a palette index; {@link #EMPTY} (-1) means unpainted.
 */
public class SketchSession {

    public static final int SIZE = 32;
    public static final int EMPTY = -1;

    private final int[][] canvas = new int[SIZE][SIZE];

    public SketchSession() {
        clear();
    }

    public synchronized void apply(StrokeMessage stroke) {
        if (stroke == null || stroke.getTool() == null) return;

        // No global CLEAR: this is a cumulative group mural, so a single user
        // must not be able to wipe everyone's work. Only per-cell edits.
        switch (stroke.getTool()) {
            case "ERASE" -> setCell(stroke.getX(), stroke.getY(), EMPTY);
            case "PEN" -> {
                if (stroke.getColorIndex() != null) {
                    setCell(stroke.getX(), stroke.getY(), stroke.getColorIndex());
                }
            }
            default -> { /* unknown / disallowed tool ignored */ }
        }
    }

    public synchronized int[][] snapshot() {
        int[][] copy = new int[SIZE][SIZE];
        for (int row = 0; row < SIZE; row++) {
            System.arraycopy(canvas[row], 0, copy[row], 0, SIZE);
        }
        return copy;
    }

    /** Controlled reset used by the "archive + start new mural" flow (not exposed as a stroke tool). */
    public synchronized void reset() {
        clear();
    }

    public synchronized boolean isEmpty() {
        for (int[] row : canvas) {
            for (int cell : row) {
                if (cell != EMPTY) return false;
            }
        }
        return true;
    }

    /** Flatten the canvas to a compact CSV of palette indices for persistence. */
    public synchronized String serialize() {
        StringBuilder sb = new StringBuilder(SIZE * SIZE * 3);
        for (int row = 0; row < SIZE; row++) {
            for (int col = 0; col < SIZE; col++) {
                if (row != 0 || col != 0) sb.append(',');
                sb.append(canvas[row][col]);
            }
        }
        return sb.toString();
    }

    /** Restore the canvas from a CSV produced by {@link #serialize()}. Malformed/size-mismatched input is ignored. */
    public synchronized void deserialize(String csv) {
        if (csv == null || csv.isBlank()) return;
        String[] parts = csv.split(",");
        if (parts.length != SIZE * SIZE) return;
        int i = 0;
        for (int row = 0; row < SIZE; row++) {
            for (int col = 0; col < SIZE; col++) {
                try {
                    canvas[row][col] = Integer.parseInt(parts[i++].trim());
                } catch (NumberFormatException e) {
                    canvas[row][col] = EMPTY;
                }
            }
        }
    }

    private void setCell(Integer x, Integer y, int value) {
        if (x == null || y == null) return;
        if (x < 0 || x >= SIZE || y < 0 || y >= SIZE) return;
        canvas[y][x] = value;
    }

    private void clear() {
        for (int[] row : canvas) {
            java.util.Arrays.fill(row, EMPTY);
        }
    }
}
