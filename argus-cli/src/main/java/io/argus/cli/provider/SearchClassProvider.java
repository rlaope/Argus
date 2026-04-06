package io.argus.cli.provider;

import io.argus.cli.model.SearchClassResult;

/**
 * Provider for loaded class search.
 */
public interface SearchClassProvider extends DiagnosticProvider {
    SearchClassResult searchClasses(long pid, String pattern);
}
