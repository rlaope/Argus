package io.argus.cli.provider;

import io.argus.cli.model.ClassLoaderResult;

public interface ClassLoaderProvider extends DiagnosticProvider {

    ClassLoaderResult getClassLoaders(long pid);
}
