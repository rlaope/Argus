package io.argus.cli.provider.jdk;

import io.argus.cli.model.VmFlagResult;
import io.argus.cli.provider.VmFlagProvider;

import java.util.ArrayList;
import java.util.List;

/**
 * Provides VM flags via {@code jcmd VM.flags} and allows setting them via {@code jcmd VM.set_flag}.
 */
public final class JdkVmFlagProvider implements VmFlagProvider {

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
    public VmFlagResult getVmFlags(long pid) {
        try {
            String output = JcmdExecutor.execute(pid, "VM.flags");
            return parse(output);
        } catch (Exception e) {
            return new VmFlagResult(List.of());
        }
    }

    @Override
    public String setVmFlag(long pid, String flag, String value) {
        try {
            String cmd = "VM.set_flag " + flag + " " + value;
            String output = JcmdExecutor.execute(pid, cmd);
            return output.isBlank() ? "OK" : output.trim();
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    private static VmFlagResult parse(String output) {
        List<VmFlagResult.VmFlag> flags = new ArrayList<>();

        // VM.flags output may be a single line with space-separated flags,
        // or multi-line (one flag per line). Split by whitespace to handle both.
        String[] tokens = output.split("\\s+");

        for (String token : tokens) {
            token = token.trim();
            if (!token.startsWith("-XX:")) continue;

            String flagBody = token.substring(4);

            if (flagBody.startsWith("+")) {
                flags.add(new VmFlagResult.VmFlag(flagBody.substring(1), "true"));
            } else if (flagBody.startsWith("-")) {
                flags.add(new VmFlagResult.VmFlag(flagBody.substring(1), "false"));
            } else {
                int eq = flagBody.indexOf('=');
                if (eq > 0) {
                    flags.add(new VmFlagResult.VmFlag(flagBody.substring(0, eq), flagBody.substring(eq + 1)));
                } else {
                    flags.add(new VmFlagResult.VmFlag(flagBody, ""));
                }
            }
        }

        return new VmFlagResult(flags);
    }
}
