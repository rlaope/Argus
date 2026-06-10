const APM_FACADE_ENDPOINTS = Object.freeze({
    services: '/apm/services',
    incidents: '/apm/incidents',
    backendLinks: '/apm/backend-links'
});

const APM_LIVE_ENABLED = new URLSearchParams(window.location.search).get('live') === '1';

const SAMPLE_APM_DATA = Object.freeze({
    scope: { tenant: 'tenant-a', project: 'payments', environment: 'prod' },
    services: [
        {
            name: 'shop/checkout',
            owner: 'payments-platform',
            health: 'degraded',
            latencyP95: 184,
            errorRate: 1.8,
            gcP95: 96,
            requestRate: 124,
            traceLinks: 7,
            runbook: 'GC latency',
            endpoints: [
                { method: 'GET', route: '/checkout/{id}', latencyP95: 184, errorRate: 1.8, traces: 36, local: '/?pod=shop/checkout-7f4c-abc' },
                { method: 'POST', route: '/checkout', latencyP95: 263, errorRate: 2.4, traces: 41, local: '/?pod=shop/checkout-7f4c-abc' },
                { method: 'GET', route: '/checkout/{id}/status', latencyP95: 82, errorRate: 0.3, traces: 19, local: '/?pod=shop/checkout-7f4c-def' }
            ],
            findings: [
                { severity: 'warning', title: 'GC pause regression', detail: 'G1 evacuation pause overlaps checkout POST spans.', traceId: 'trace-checkout-1', spanId: 'span-1' },
                { severity: 'critical', title: 'Lock contention hotspot', detail: 'PaymentClient cache lock blocks carrier threads.', traceId: 'trace-checkout-2', spanId: 'span-4' }
            ]
        },
        {
            name: 'shop/catalog',
            owner: 'commerce-platform',
            health: 'healthy',
            latencyP95: 71,
            errorRate: 0.2,
            gcP95: 18,
            requestRate: 310,
            traceLinks: 4,
            runbook: 'Catalog latency',
            endpoints: [
                { method: 'GET', route: '/catalog/search', latencyP95: 71, errorRate: 0.2, traces: 22, local: '/?pod=shop/catalog-554b-a' },
                { method: 'GET', route: '/catalog/{sku}', latencyP95: 49, errorRate: 0.1, traces: 18, local: '/?pod=shop/catalog-554b-b' }
            ],
            findings: [
                { severity: 'info', title: 'Profile hotspot', detail: 'JSON serialization is the top allocation frame.', traceId: 'trace-catalog-1', spanId: 'span-8' }
            ]
        },
        {
            name: 'identity/session',
            owner: 'identity',
            health: 'unhealthy',
            latencyP95: 421,
            errorRate: 6.7,
            gcP95: 44,
            requestRate: 88,
            traceLinks: 11,
            runbook: 'Session incident',
            endpoints: [
                { method: 'POST', route: '/session', latencyP95: 421, errorRate: 6.7, traces: 52, local: '/?pod=identity/session-65dd-a' },
                { method: 'DELETE', route: '/session/{id}', latencyP95: 135, errorRate: 1.1, traces: 15, local: '/?pod=identity/session-65dd-b' }
            ],
            findings: [
                { severity: 'critical', title: 'Bad release regression', detail: 'Latency shifted after deployment 2026.06.09.', traceId: 'trace-session-1', spanId: 'span-2' },
                { severity: 'warning', title: 'Virtual-thread pinning', detail: 'Synchronized token refresh pins request carriers.', traceId: 'trace-session-2', spanId: 'span-5' }
            ]
        }
    ],
    incidents: [
        { time: '09:41', service: 'identity/session', severity: 'critical', title: 'Bad release regression', target: 'POST /session' },
        { time: '09:33', service: 'shop/checkout', severity: 'warning', title: 'GC latency burst', target: 'POST /checkout' },
        { time: '09:29', service: 'identity/session', severity: 'warning', title: 'Virtual-thread pinning', target: 'POST /session' },
        { time: '09:25', service: 'shop/checkout', severity: 'warning', title: 'Lock contention', target: 'GET /checkout/{id}' }
    ]
});

let apmState = {
    services: [],
    incidents: [],
    scope: SAMPLE_APM_DATA.scope,
    selectedService: null,
    fromFacade: false
};

const refs = {
    status: document.getElementById('apm-facade-status'),
    statusText: document.querySelector('#apm-facade-status .status-text'),
    totalServices: document.getElementById('apm-total-services'),
    degradedServices: document.getElementById('apm-degraded-services'),
    openIncidents: document.getElementById('apm-open-incidents'),
    p95Latency: document.getElementById('apm-p95-latency'),
    gcPressure: document.getElementById('apm-gc-pressure'),
    traceLinks: document.getElementById('apm-trace-links'),
    tenantSelect: document.getElementById('apm-filter-tenant'),
    projectSelect: document.getElementById('apm-filter-project'),
    environmentSelect: document.getElementById('apm-filter-environment'),
    serviceSelect: document.getElementById('apm-filter-service'),
    refreshBtn: document.getElementById('apm-refresh-btn'),
    grafanaGlobalLink: document.getElementById('apm-grafana-global-link'),
    inventory: document.getElementById('apm-service-inventory'),
    inventoryCount: document.getElementById('apm-inventory-count'),
    selectedService: document.getElementById('apm-selected-service'),
    endpointTable: document.getElementById('apm-endpoint-table'),
    incidentTimeline: document.getElementById('apm-incident-timeline'),
    rootCauseCards: document.getElementById('apm-root-cause-cards'),
    rootCauseCount: document.getElementById('apm-root-cause-count'),
    backendLinks: document.getElementById('apm-backend-links'),
    localDashboardLink: document.getElementById('apm-local-dashboard-link'),
    localProfilesLink: document.getElementById('apm-local-profiles-link'),
    localConsoleLink: document.getElementById('apm-local-console-link')
};

document.addEventListener('DOMContentLoaded', initApmWorkflow);

async function initApmWorkflow() {
    refs.refreshBtn.addEventListener('click', refreshApmWorkflow);
    refs.serviceSelect.addEventListener('change', () => selectService(refs.serviceSelect.value));
    await refreshApmWorkflow();
}

async function refreshApmWorkflow() {
    setApmStatus('loading');
    const data = await loadApmFacadeData();
    apmState.services = data.services;
    apmState.incidents = data.incidents;
    apmState.scope = data.scope;
    apmState.fromFacade = data.fromFacade;
    apmState.selectedService = apmState.services[0] || null;
    rebuildServiceFilter();
    renderApmSummary();
    renderServiceInventory();
    renderEndpointView();
    renderIncidentTimeline();
    renderRootCauseCards();
    renderBackendDrilldowns();
    setApmStatus(data.status);
}

async function loadApmFacadeData() {
    if (APM_LIVE_ENABLED) {
        const [serviceResponse, incidentResponse] = await Promise.all([
            fetchJson(APM_FACADE_ENDPOINTS.services),
            fetchJson(APM_FACADE_ENDPOINTS.incidents)
        ]);
        if (serviceResponse && Array.isArray(serviceResponse.services) && incidentResponse && Array.isArray(incidentResponse.incidents)) {
            try {
                return {
                    services: normalizeFacadeServices(serviceResponse.services),
                    incidents: normalizeFacadeIncidents(incidentResponse.incidents),
                    scope: normalizeFacadeScope(serviceResponse.scope || incidentResponse.scope || readFilterScope()),
                    fromFacade: true,
                    status: 'connected'
                };
            } catch (e) {
                return emptyApmData('error');
            }
        }
        return emptyApmData('error');
    }
    return sampleApmData();
}

function sampleApmData() {
    return {
        services: SAMPLE_APM_DATA.services.map(service => ({ ...service })),
        incidents: SAMPLE_APM_DATA.incidents.map(incident => ({ ...incident })),
        scope: normalizeFacadeScope(SAMPLE_APM_DATA.scope),
        fromFacade: false,
        status: 'sample'
    };
}

function emptyApmData(status) {
    return {
        services: [],
        incidents: [],
        scope: readFilterScope(),
        fromFacade: false,
        status
    };
}

async function fetchJson(url) {
    try {
        const response = await fetch(url, { headers: { Accept: 'application/json' } });
        if (!response.ok) {
            return null;
        }
        return await response.json();
    } catch (e) {
        return null;
    }
}

function normalizeFacadeServices(services) {
    return services.map(service => {
        if (!isObject(service)) {
            throw new Error('invalid facade service');
        }
        return {
            name: service.name || serviceDisplayName(service.service, 'unknown/service'),
            owner: service.owner?.team || service.owner || 'unassigned',
            health: String(service.health || 'unknown').toLowerCase(),
            latencyP95: finiteNumber(service.signals?.latencyP95Millis || service.latencyP95),
            errorRate: finiteNumber(service.signals?.errorRate || service.errorRate),
            gcP95: finiteNumber(service.signals?.gcPauseP95Millis || service.gcP95),
            requestRate: finiteNumber(service.signals?.requestRatePerSecond || service.requestRate),
            traceLinks: finiteNumber(service.traceLinks || service.backendLinks?.length),
            runbook: service.runbook?.title || 'runbook',
            endpoints: normalizeFacadeEndpoints(service.endpoints || []),
            findings: normalizeFacadeFindings(service.findings || [])
        };
    });
}

function normalizeFacadeEndpoints(endpoints) {
    if (!Array.isArray(endpoints)) {
        throw new Error('invalid facade endpoints');
    }
    return endpoints.map(endpoint => {
        if (!isObject(endpoint)) {
            throw new Error('invalid facade endpoint');
        }
        return {
            method: String(endpoint.method || endpoint.httpMethod || 'GET').toUpperCase(),
            route: endpoint.route || endpoint.endpointRoute || '',
            latencyP95: finiteNumber(endpoint.signals?.latencyP95Millis || endpoint.latencyP95),
            errorRate: finiteNumber(endpoint.signals?.errorRate || endpoint.errorRate),
            traces: finiteNumber(endpoint.traces || endpoint.signals?.traceCount),
            local: endpoint.local || endpoint.localDashboardPath || '/'
        };
    });
}

function normalizeFacadeFindings(findings) {
    if (!Array.isArray(findings)) {
        throw new Error('invalid facade findings');
    }
    return findings.map(finding => {
        if (!isObject(finding)) {
            throw new Error('invalid facade finding');
        }
        return {
            severity: String(finding.severity || 'info').toLowerCase(),
            title: finding.title || finding.kind || 'Finding',
            detail: finding.detail || finding.message || '',
            traceId: finding.traceId || '',
            spanId: finding.spanId || ''
        };
    });
}

function normalizeFacadeIncidents(incidents) {
    return incidents.map(incident => {
        if (!isObject(incident)) {
            throw new Error('invalid facade incident');
        }
        return {
            time: incident.time || incident.updatedAt || incident.startedAt || '',
            service: incident.service || serviceDisplayName(incident.serviceId, 'unknown/service'),
            severity: String(incident.severity || 'info').toLowerCase(),
            title: incident.title || incident.id || 'Incident',
            target: incident.target || incident.endpointRoute || incident.traceId || ''
        };
    });
}

function rebuildServiceFilter() {
    refs.serviceSelect.replaceChildren();
    apmState.services.forEach(service => {
        const option = document.createElement('option');
        option.value = service.name;
        option.textContent = service.name;
        refs.serviceSelect.appendChild(option);
    });
    if (apmState.selectedService) {
        refs.serviceSelect.value = apmState.selectedService.name;
    }
}

function renderApmSummary() {
    const services = apmState.services;
    const degraded = services.filter(service => service.health !== 'healthy').length;
    refs.totalServices.textContent = String(services.length);
    refs.degradedServices.textContent = String(degraded);
    refs.openIncidents.textContent = String(apmState.incidents.length);
    refs.p95Latency.textContent = Math.max(...services.map(service => service.latencyP95), 0) + 'ms';
    refs.gcPressure.textContent = Math.max(...services.map(service => service.gcP95), 0) + 'ms';
    refs.traceLinks.textContent = String(services.reduce((sum, service) => sum + service.traceLinks, 0));
}

function renderServiceInventory() {
    refs.inventoryCount.textContent = apmState.services.length + ' services';
    const fragment = document.createDocumentFragment();
    apmState.services.forEach(service => {
        const row = document.createElement('button');
        row.type = 'button';
        row.className = 'apm-service-row' + (service === apmState.selectedService ? ' is-selected' : '');
        row.innerHTML =
            '<div class="apm-row-title">' +
            '<span>' + escHtml(service.name) + '</span>' +
            '<span class="apm-health apm-health--' + healthClass(service.health) + '">' + escHtml(service.health) + '</span>' +
            '</div>' +
            '<div class="apm-row-meta">' +
            '<span>' + escHtml(service.owner) + '</span>' +
            '<span>p95 ' + service.latencyP95 + 'ms</span>' +
            '<span>err ' + service.errorRate + '%</span>' +
            '</div>';
        row.addEventListener('click', () => selectService(service.name));
        fragment.appendChild(row);
    });
    refs.inventory.replaceChildren(fragment);
}

function selectService(name) {
    apmState.selectedService = apmState.services.find(service => service.name === name) || apmState.services[0] || null;
    refs.serviceSelect.value = apmState.selectedService ? apmState.selectedService.name : '';
    renderServiceInventory();
    renderEndpointView();
    renderIncidentTimeline();
    renderRootCauseCards();
    renderBackendDrilldowns();
}

function renderEndpointView() {
    const service = apmState.selectedService;
    refs.selectedService.textContent = service ? service.name : '-';
    if (!service || service.endpoints.length === 0) {
        refs.endpointTable.innerHTML = '<div class="apm-empty">No endpoints</div>';
        return;
    }
    const rows = service.endpoints.map(endpoint =>
        '<tr>' +
        '<td><strong>' + escHtml(endpoint.method) + '</strong></td>' +
        '<td>' + escHtml(endpoint.route) + '</td>' +
        '<td>' + endpoint.latencyP95 + 'ms</td>' +
        '<td>' + endpoint.errorRate + '%</td>' +
        '<td>' + endpoint.traces + '</td>' +
        '<td><a href="' + escAttr(safeLocalHref(endpoint.local, '/')) + '">Local</a></td>' +
        '</tr>'
    ).join('');
    refs.endpointTable.innerHTML =
        '<table>' +
        '<thead><tr><th>Method</th><th>Route</th><th>p95</th><th>Error</th><th>Traces</th><th>Drill</th></tr></thead>' +
        '<tbody>' + rows + '</tbody>' +
        '</table>';
}

function renderIncidentTimeline() {
    const service = apmState.selectedService;
    const incidents = service
        ? apmState.incidents.filter(incident => incident.service === service.name)
        : apmState.incidents;
    if (incidents.length === 0) {
        refs.incidentTimeline.innerHTML = '<div class="apm-empty">No active incidents</div>';
        return;
    }
    refs.incidentTimeline.innerHTML = incidents.map(incident =>
        '<div class="apm-incident-item">' +
        '<span class="apm-incident-time">' + escHtml(incident.time) + '</span>' +
        '<div><div class="apm-card-title">' + escHtml(incident.title) + '</div>' +
        '<div class="apm-card-meta"><span>' + escHtml(incident.service) + '</span><span>' + escHtml(incident.target) + '</span></div></div>' +
        '<span class="apm-health apm-health--' + healthClass(incident.severity) + '">' + escHtml(incident.severity) + '</span>' +
        '</div>'
    ).join('');
}

function renderRootCauseCards() {
    const service = apmState.selectedService;
    const findings = service ? service.findings : [];
    refs.rootCauseCount.textContent = findings.length + ' findings';
    if (findings.length === 0) {
        refs.rootCauseCards.innerHTML = '<div class="apm-empty">No findings</div>';
        return;
    }
    refs.rootCauseCards.innerHTML = findings.map(finding =>
        '<article class="apm-root-card">' +
        '<div class="apm-card-title"><span>' + escHtml(finding.title) + '</span>' +
        '<span class="apm-health apm-health--' + healthClass(finding.severity) + '">' + escHtml(finding.severity) + '</span></div>' +
        '<p>' + escHtml(finding.detail) + '</p>' +
        '<div class="apm-card-meta"><span>trace ' + escHtml(finding.traceId) + '</span><span>span ' + escHtml(finding.spanId) + '</span></div>' +
        '</article>'
    ).join('');
}

function renderBackendDrilldowns() {
    const service = apmState.selectedService;
    if (!service) {
        refs.backendLinks.innerHTML = '<div class="apm-empty">No service selected</div>';
        return;
    }
    const endpoint = service.endpoints[0] || {};
    const links = apmState.fromFacade ? [
        { label: 'APM backend links API', href: buildBackendLinksFacadeHref(service, endpoint) }
    ] : [
        { label: 'Grafana service board', href: buildGrafanaLink(service, endpoint) },
        { label: 'Prometheus query', href: '/prometheus?service=' + encodeURIComponent(service.name) },
        { label: 'Tempo or Jaeger trace', href: 'https://tempo.local/trace/' + encodeURIComponent(service.findings[0]?.traceId || '') },
        { label: 'Loki logs', href: 'https://loki.local/explore?service=' + encodeURIComponent(service.name) },
        { label: 'Pyroscope profile', href: 'https://pyroscope.local/profiles?service=' + encodeURIComponent(service.name) }
    ];
    refs.backendLinks.innerHTML = links.map(link =>
        '<a class="apm-link-row" href="' + escAttr(link.href) + '" target="_blank" rel="noopener">' +
        '<span>' + escHtml(link.label) + '</span><span>open</span></a>'
    ).join('');
    refs.grafanaGlobalLink.href = apmState.fromFacade ? buildBackendLinksFacadeHref(service, endpoint) : buildGrafanaLink(service, endpoint);
    refs.grafanaGlobalLink.textContent = apmState.fromFacade ? 'Backend Links' : 'Grafana';
    refs.localDashboardLink.href = safeLocalHref(endpoint.local, '/');
    refs.localProfilesLink.href = '/profiles.html?event=cpu&range=3600&service=' + encodeURIComponent(service.name);
    refs.localConsoleLink.href = '/console.html?service=' + encodeURIComponent(service.name);
}

function buildGrafanaLink(service, endpoint) {
    const scope = currentScope();
    const params = new URLSearchParams({
        'var-tenant': scope.tenant,
        'var-project': scope.project,
        'var-environment': scope.environment,
        'var-service': service.name,
        'var-endpoint': endpoint.route || ''
    });
    return 'https://grafana.local/d/argus-apm?' + params.toString();
}

function buildBackendLinksFacadeHref(service, endpoint) {
    const scope = currentScope();
    const params = new URLSearchParams({
        tenant: scope.tenant,
        project: scope.project,
        environment: scope.environment,
        service: service.name,
        endpoint: endpoint.route || ''
    });
    return APM_FACADE_ENDPOINTS.backendLinks + '?' + params.toString();
}

function currentScope() {
    return normalizeFacadeScope(apmState.scope || readFilterScope());
}

function serviceDisplayName(serviceId, fallback) {
    if (!isObject(serviceId)) {
        return fallback;
    }
    if (serviceId.displayName) {
        return String(serviceId.displayName);
    }
    const name = String(serviceId.name || '');
    if (!name) {
        return fallback;
    }
    const namespace = String(serviceId.namespace || '');
    return namespace ? namespace + '/' + name : name;
}

function readFilterScope() {
    const fallback = SAMPLE_APM_DATA.scope;
    return normalizeFacadeScope({
        tenant: refs.tenantSelect?.value || fallback.tenant,
        project: refs.projectSelect?.value || fallback.project,
        environment: refs.environmentSelect?.value || fallback.environment
    });
}

function normalizeFacadeScope(scope) {
    const fallback = SAMPLE_APM_DATA.scope;
    const candidate = isObject(scope) ? scope : {};
    return {
        tenant: String(candidate.tenant || fallback.tenant),
        project: String(candidate.project || fallback.project),
        environment: String(candidate.environment || fallback.environment)
    };
}

function isObject(value) {
    return value !== null && typeof value === 'object' && !Array.isArray(value);
}

function safeLocalHref(value, fallback) {
    const href = String(value ?? '').trim();
    if (!href.startsWith('/') || href.startsWith('//')) {
        return fallback;
    }
    try {
        const url = new URL(href, window.location.origin);
        const allowedLocalPath = url.pathname === '/'
            || url.pathname === '/apm.html'
            || url.pathname === '/console.html'
            || url.pathname === '/profiles.html';
        return url.origin === window.location.origin && allowedLocalPath ? url.pathname + url.search + url.hash : fallback;
    } catch (e) {
        return fallback;
    }
}

function setApmStatus(state) {
    refs.status.className = 'status ' + (state === 'connected' ? 'connected' : 'disconnected');
    refs.statusText.textContent = state === 'connected' ? 'Live' : state === 'sample' ? 'Preview' : state === 'error' ? 'Offline' : 'Loading';
}

function healthClass(value) {
    const key = String(value || '').toLowerCase();
    if (key === 'healthy' || key === 'info') return 'healthy';
    if (key === 'critical' || key === 'unhealthy') return 'unhealthy';
    return 'degraded';
}

function escHtml(value) {
    return String(value ?? '')
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#39;');
}

function escAttr(value) {
    return escHtml(value);
}

function finiteNumber(value) {
    const parsed = Number(value ?? 0);
    return Number.isFinite(parsed) ? parsed : 0;
}
