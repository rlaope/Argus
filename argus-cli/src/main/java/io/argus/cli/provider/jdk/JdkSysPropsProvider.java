package io.argus.cli.provider.jdk;

import io.argus.cli.model.SysPropsResult;
import io.argus.cli.provider.SysPropsProvider;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Provides JVM system properties via {@code jcmd VM.system_properties}.
 */
public final class JdkSysPropsProvider implements SysPropsProvider {

    @Override
    public boolean isAvailable(long pid) {
        return JcmdExecutor.isJcmdAvailable();
    }

    @Override
    public int priority() {
        return 10;
    }

    @Override
    public String source() {
        return "jdk";
    }

    @Override
    public SysPropsResult getSystemProperties(long pid) {
        try {
            String output = JcmdExecutor.execute(pid, "VM.system_properties");
            return parse(output);
        } catch (Exception e) {
            return new SysPropsResult(Map.of());
        }
    }

    private static SysPropsResult parse(String output) {
        Map<String, String> props = new LinkedHashMap<>();
        String[] lines = output.split("\n");

        String currentKey = null;
        StringBuilder currentValue = new StringBuilder();

        for (String rawLine : lines) {
            // jcmd VM.system_properties escapes newlines as \n in values;
            // lines with '=' are new key=value entries
            int eqIdx = rawLine.indexOf('=');
            if (eqIdx > 0 && !rawLine.startsWith(" ") && !rawLine.startsWith("\t")) {
                // Save previous entry
                if (currentKey != null) {
                    props.put(currentKey, currentValue.toString());
                }
                currentKey = rawLine.substring(0, eqIdx).trim();
                currentValue = new StringBuilder(rawLine.substring(eqIdx + 1));
            } else if (currentKey != null) {
                // Continuation line (no '=' at the start)
                currentValue.append('\n').append(rawLine);
            }
        }
        // Save last entry
        if (currentKey != null) {
            props.put(currentKey, currentValue.toString());
        }

        return new SysPropsResult(props);
    }
}
