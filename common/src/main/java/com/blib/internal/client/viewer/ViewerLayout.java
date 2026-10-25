package com.blib.internal.client.viewer;

/** The one layout every viewer uses for BLib pages, so JEI, REI and EMI look alike. */
public final class ViewerLayout {

    public static final int WIDTH = 160;

    public static final int NOTE_HEIGHT = 22;

    public static final int SLOT = 18;

    public static final int INPUT_COLUMNS = 3;

    public static final int OUTPUT_X = 86;

    public static final int OUTPUT_COLUMNS = 4;

    public static final int ARROW_X = 58;

    public static final int ROWS = 3;

    public static final int HEIGHT = NOTE_HEIGHT + ROWS * SLOT;

    public static final int MAX_INPUTS = INPUT_COLUMNS * ROWS;

    public static final int MAX_OUTPUTS = OUTPUT_COLUMNS * ROWS;

    private ViewerLayout() {}

    public static int inputX(int index) {
        return (index % INPUT_COLUMNS) * SLOT;
    }

    public static int inputY(int index) {
        return NOTE_HEIGHT + (index / INPUT_COLUMNS) * SLOT;
    }

    public static int outputX(int index) {
        return OUTPUT_X + (index % OUTPUT_COLUMNS) * SLOT;
    }

    public static int outputY(int index) {
        return NOTE_HEIGHT + (index / OUTPUT_COLUMNS) * SLOT;
    }

    public static int arrowY() {
        return NOTE_HEIGHT + 1;
    }
}
