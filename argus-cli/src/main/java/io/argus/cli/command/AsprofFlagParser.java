package io.argus.cli.command;

import io.argus.cli.config.Messages;
import io.argus.cli.provider.jdk.AsProfOptions;

/**
 * Shared parser for asprof passthrough flags used by both the one-shot
 * profile command and the session subcommands (start/stop/dump/status).
 *
 * <p>The caller dispatches each CLI argument here first; on {@link Result#NOT_HANDLED}
 * the caller falls through to its parser-specific flags. On {@link Result#ERROR} the
 * helper has already printed a localized error and the caller should return.
 */
public final class AsprofFlagParser {

    public enum Result { NOT_HANDLED, HANDLED, ERROR }

    private AsprofFlagParser() {}

    public static Result handle(String arg, AsProfOptions.Builder b, Messages messages) {
        // boolean flags
        if (arg.equals("--threads"))   { b.perThread(true);  return Result.HANDLED; }
        if (arg.equals("--alluser"))   { b.allUser(true);    return Result.HANDLED; }
        if (arg.equals("--allkernel")) { b.allKernel(true);  return Result.HANDLED; }
        if (arg.equals("--live"))      { b.live(true);       return Result.HANDLED; }
        if (arg.equals("--reverse"))   { b.reverse(true);    return Result.HANDLED; }
        if (arg.equals("--sched"))     { b.sched(true);      return Result.HANDLED; }
        if (arg.equals("--nofree"))    { b.nofree(true);     return Result.HANDLED; }
        if (arg.equals("--ttsp"))      { b.ttsp(true);       return Result.HANDLED; }

        // key=value with validation
        if (arg.startsWith("--interval=")) {
            String v = arg.substring("--interval=".length());
            String err = ProfileCommand.validateInterval(v, messages);
            if (err != null) { System.err.println(err); return Result.ERROR; }
            b.interval(v); return Result.HANDLED;
        }
        if (arg.startsWith("--jstackdepth=")) {
            int depth = ProfileCommand.parseClampedInt(arg.substring("--jstackdepth=".length()), 1, 2048, 64);
            b.jstackdepth(depth); return Result.HANDLED;
        }
        if (arg.startsWith("--cstack=")) {
            String v = arg.substring("--cstack=".length());
            String err = ProfileCommand.validateCstack(v, messages);
            if (err != null) { System.err.println(err); return Result.ERROR; }
            b.cstack(v); return Result.HANDLED;
        }
        if (arg.startsWith("--clock=")) {
            String v = arg.substring("--clock=".length());
            String err = ProfileCommand.validateClock(v, messages);
            if (err != null) { System.err.println(err); return Result.ERROR; }
            b.clock(v); return Result.HANDLED;
        }
        if (arg.startsWith("--proc=")) {
            String v = arg.substring("--proc=".length());
            String err = ProfileCommand.validateInterval(v, messages);
            if (err != null) { System.err.println(err); return Result.ERROR; }
            b.procInterval(v); return Result.HANDLED;
        }
        if (arg.startsWith("--begin=")) {
            String v = arg.substring("--begin=".length());
            if (v.isEmpty()) { System.err.println(messages.get("error.profile.adv.begin.empty")); return Result.ERROR; }
            b.beginFunction(v); return Result.HANDLED;
        }
        if (arg.startsWith("--end=")) {
            String v = arg.substring("--end=".length());
            if (v.isEmpty()) { System.err.println(messages.get("error.profile.adv.end.empty")); return Result.ERROR; }
            b.endFunction(v); return Result.HANDLED;
        }

        // key=value raw passthrough
        if (arg.startsWith("--alloc="))    { b.allocBytes(arg.substring("--alloc=".length()));   return Result.HANDLED; }
        if (arg.startsWith("--include="))  { b.addInclude(arg.substring("--include=".length())); return Result.HANDLED; }
        if (arg.startsWith("--exclude="))  { b.addExclude(arg.substring("--exclude=".length())); return Result.HANDLED; }
        if (arg.startsWith("--minwidth=")) { b.minwidth(arg.substring("--minwidth=".length()));  return Result.HANDLED; }
        if (arg.startsWith("--signal="))   { b.signal(arg.substring("--signal=".length()));      return Result.HANDLED; }

        return Result.NOT_HANDLED;
    }
}
