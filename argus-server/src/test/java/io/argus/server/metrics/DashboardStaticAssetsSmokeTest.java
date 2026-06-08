package io.argus.server.metrics;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class DashboardStaticAssetsSmokeTest {

    @Test
    void mainDashboardKeepsModeStripAndIncidentSynopsisHooks() throws Exception {
        String html = readRepoFile("argus-frontend/src/main/resources/public/index.html");
        String app = readRepoFile("argus-frontend/src/main/resources/public/js/app.js");
        String cluster = readRepoFile("argus-frontend/src/main/resources/public/js/dashboard-cluster.js");
        String podContext = readRepoFile("argus-frontend/src/main/resources/public/js/pod-context.js");
        String websocket = readRepoFile("argus-frontend/src/main/resources/public/js/websocket.js");

        assertContains(html, "/js/pod-context.js");
        assertContains(html, "id=\"dashboard-mode-strip\"");
        assertContains(html, "id=\"incident-synopsis-list\"");
        assertContains(podContext, "window.ArgusPodContext");
        assertContains(podContext, "opts.hash ? readHashPod() : null");
        assertContains(cluster, "ArgusPodContext");
        assertContains(app, "function updateIncidentSynopsis()");
        assertContains(cluster, "Selected-pod REST snapshots are active");
        assertContains(websocket, "WebSocket connected");
    }

    @Test
    void fleetProfilesAndConsolePreservePodContext() throws Exception {
        String fleetHtml = readRepoFile("argus-frontend/src/main/resources/public/fleet.html");
        String fleetJs = readRepoFile("argus-frontend/src/main/resources/public/js/fleet.js");
        String profilesJs = readRepoFile("argus-frontend/src/main/resources/public/js/profiles.js");
        String consoleHtml = readRepoFile("argus-frontend/src/main/resources/public/console.html");

        assertContains(fleetHtml, "/js/pod-context.js");
        assertContains(consoleHtml, "/js/pod-context.js");
        assertContains(fleetHtml, "id=\"panel-reasons\"");
        assertContains(fleetHtml, "id=\"panel-profiles-cpu-link\"");
        assertContains(fleetJs, "buildTopReasons");
        assertContains(fleetJs, "podContext.contextUrls");
        assertContains(fleetJs, "podContext.selectInitialPod");
        assertContains(profilesJs, "podContext.readQueryParam('event')");
        assertContains(profilesJs, "podContext.readQueryParam('range')");
        assertContains(consoleHtml, "podContext.selectInitialPod");
    }

    private static void assertContains(String text, String needle) {
        assertTrue(text.contains(needle), "missing expected dashboard smoke hook: " + needle);
    }

    private static String readRepoFile(String relative) throws IOException {
        Path userDir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (Path root = userDir; root != null; root = root.getParent()) {
            Path candidate = root.resolve(relative);
            if (Files.exists(candidate)) {
                return Files.readString(candidate);
            }
        }
        throw new IOException("Unable to find repo file: " + relative + " from " + userDir);
    }
}
