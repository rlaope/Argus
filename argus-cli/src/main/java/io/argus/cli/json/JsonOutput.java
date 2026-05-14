package io.argus.cli.json;

/**
 * Thin entry point for JSON rendering: models own the schema via {@link JsonWritable#writeJson(StringBuilder)},
 * commands call {@link #println(JsonWritable)} (or {@link #render(JsonWritable)} when they need the string).
 */
public final class JsonOutput {
    private JsonOutput() {}

    public static String render(JsonWritable result) {
        StringBuilder sb = new StringBuilder();
        result.writeJson(sb);
        return sb.toString();
    }

    public static void println(JsonWritable result) {
        StringBuilder sb = new StringBuilder();
        result.writeJson(sb);
        System.out.println(sb);
    }
}
