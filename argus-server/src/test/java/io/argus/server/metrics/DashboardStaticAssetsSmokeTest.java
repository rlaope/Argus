package io.argus.server.metrics;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

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

    @Test
    void apmWorkflowKeepsFacadeHooksAndAvoidsRawAggregatorRoutes() throws Exception {
        String apmHtml = readRepoFile("argus-frontend/src/main/resources/public/apm.html");
        String apmJs = readRepoFile("argus-frontend/src/main/resources/public/js/apm.js");
        String apmCss = readRepoFile("argus-frontend/src/main/resources/public/css/apm.css");

        assertContains(apmHtml, "/js/apm.js");
        assertContains(apmHtml, "id=\"apm-service-inventory\"");
        assertContains(apmHtml, "id=\"apm-endpoint-table\"");
        assertContains(apmHtml, "id=\"apm-incident-timeline\"");
        assertContains(apmHtml, "id=\"apm-root-cause-cards\"");
        assertContains(apmHtml, "id=\"apm-backend-links\"");
        assertContains(apmJs, "APM_FACADE_ENDPOINTS");
        assertContains(apmJs, "services: '/apm/services'");
        assertContains(apmJs, "incidents: '/apm/incidents'");
        assertContains(apmJs, "backendLinks: '/apm/backend-links'");
        assertContains(apmJs, "function renderServiceInventory()");
        assertContains(apmJs, "function renderEndpointView()");
        assertContains(apmJs, "function renderIncidentTimeline()");
        assertContains(apmJs, "function renderRootCauseCards()");
        assertContains(apmJs, "function renderBackendDrilldowns()");
        assertContains(apmJs, "function buildGrafanaLink");
        assertContains(apmJs, "function buildBackendLinksFacadeHref");
        assertContains(apmJs, "function normalizeFacadeEndpoints");
        assertContains(apmJs, "function normalizeFacadeFindings");
        assertContains(apmJs, "function normalizeFacadeScope");
        assertContains(apmJs, "function readFilterScope");
        assertContains(apmJs, "function serviceDisplayName");
        assertContains(apmJs, "function isObject");
        assertContains(apmJs, "function safeLocalHref");
        assertContains(apmJs, "function finiteNumber");
        assertContains(apmJs, "return emptyApmData('error')");
        assertContains(apmJs, "Array.isArray(incidentResponse.incidents)");
        assertContains(apmJs, "throw new Error('invalid facade service')");
        assertContains(apmJs, "throw new Error('invalid facade endpoint')");
        assertContains(apmJs, "scope: normalizeFacadeScope");
        assertContains(apmJs, "namespace + '/' + name");
        assertFalse(apmJs.contains("serviceResponse.incidents"), "live incidents must come from /apm/incidents");
        assertFalse(apmJs.contains("tenant: SAMPLE_APM_DATA.scope.tenant"), "backend links must use active scope");
        assertContains(apmJs, "safeLocalHref(endpoint.local, '/')");
        assertContains(apmJs, "GC pause regression");
        assertContains(apmJs, "Lock contention hotspot");
        assertContains(apmJs, "Virtual-thread pinning");
        assertContains(apmJs, "Bad release regression");
        assertContains(apmCss, ".apm-layout");
        assertFalse(apmJs.contains("href=\"' + escAttr(endpoint.local)"), "local drilldowns must be sanitized before href assignment");
        assertFalse(apmJs.contains("refs.localDashboardLink.href = endpoint.local"), "local dashboard link must be sanitized");
        assertFalse(apmJs.contains("/fleet/"), "APM frontend must not call raw aggregator fleet routes");
        assertFalse(apmJs.contains("/api/pods"), "APM frontend must not call raw aggregator pod routes");
        assertFalse(apmJs.contains("/profile/"), "APM frontend must not call raw aggregator profile routes");
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
