package io.argus.aggregator.model;

/**
 * Tile color enum. Wire format is the lower-case name.
 */
public enum TileColor {
    GREEN,
    YELLOW,
    RED,
    GREY;

    public String wireName() {
        return name().toLowerCase();
    }

    /** Parses lower-case wire name; returns null for invalid input. */
    public static TileColor fromWire(String s) {
        if (s == null) return null;
        try {
            return TileColor.valueOf(s.toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
