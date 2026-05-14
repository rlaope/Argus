package io.argus.cli.json;

/**
 * Marker for models whose JSON shape is owned by the model itself.
 * Implementations append a complete JSON value (typically an object) to {@code out}
 * with no surrounding whitespace and no trailing newline.
 */
public interface JsonWritable {
    void writeJson(StringBuilder out);
}
