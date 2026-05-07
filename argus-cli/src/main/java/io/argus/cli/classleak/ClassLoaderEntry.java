package io.argus.cli.classleak;

/**
 * A single classloader row from {@code jcmd VM.classloader_stats}.
 *
 * <p>Example line:
 * <pre>
 * 0x0000000500068770  0x0000000500069008  0x0000000cb30b9400     735   5586944   5581408  jdk.internal.loader.ClassLoaders$AppClassLoader
 * </pre>
 *
 * @param address    hex address of the ClassLoaderData (used as stable identity key for diff)
 * @param parent     hex address of the parent classloader (or "0x0" for bootstrap)
 * @param type       classloader type name (e.g. {@code jdk.internal.loader.ClassLoaders$AppClassLoader})
 * @param classCount number of loaded classes
 * @param chunkBytes total allocated metaspace chunk bytes
 * @param blockBytes total allocated metaspace block bytes
 */
public record ClassLoaderEntry(
        String address,
        String parent,
        String type,
        long classCount,
        long chunkBytes,
        long blockBytes
) {}
