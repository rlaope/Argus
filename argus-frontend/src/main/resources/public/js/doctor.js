/**
 * Argus Dashboard - Doctor Report Module
 *
 * Fetches /api/doctor and renders a health score gauge plus finding cards.
 */

let doctorInterval = null;

/**
 * Initialize the doctor report panel.
 * Fetches immediately and schedules 30s refresh.
 */
export function initDoctor() {
    fetchDoctorReport();
    doctorInterval = setInterval(fetchDoctorReport, 30000);
}

async function fetchDoctorReport() {
    try {
        const res = await fetch('/api/doctor');
        if (!res.ok) return;
        const data = await res.json();
        renderDoctorReport(data);
    } catch (_) {
        // server not ready yet — no-op
    }
}

function renderDoctorReport(data) {
    const { score, findings } = data;

    // --- Score gauge ---
    const gaugeEl = document.getElementById('doctor-score');
    const gaugeArcEl = document.getElementById('doctor-score-arc');
    const gaugeLabelEl = document.getElementById('doctor-score-label');

    if (gaugeEl) {
        gaugeEl.textContent = score;
        gaugeEl.className = 'doctor-score-number ' + scoreClass(score);
    }

    if (gaugeArcEl) {
        // SVG circle: circumference = 2π × r (r=44, so ≈ 276.5)
        const circ = 2 * Math.PI * 44;
        const filled = (score / 100) * circ;
        gaugeArcEl.style.strokeDasharray = `${filled.toFixed(1)} ${circ.toFixed(1)}`;
        gaugeArcEl.className = 'doctor-arc-fill ' + scoreClass(score);
    }

    if (gaugeLabelEl) {
        gaugeLabelEl.textContent = scoreLabel(score);
        gaugeLabelEl.className = 'doctor-score-label ' + scoreClass(score);
    }

    // --- Findings ---
    const listEl = document.getElementById('doctor-findings');
    if (!listEl) return;

    if (!findings || findings.length === 0) {
        listEl.innerHTML = '<div class="empty-state">No findings</div>';
        return;
    }

    listEl.innerHTML = findings.map(f => `
        <div class="doctor-finding doctor-finding--${f.severity.toLowerCase()}">
            <span class="doctor-finding-badge">${escapeSvg(f.severity)}</span>
            <div class="doctor-finding-body">
                <span class="doctor-finding-msg">${escapeHtmlStr(f.message)}</span>
                ${f.recommendation ? `
                <div class="doctor-finding-rec">
                    <code class="doctor-rec-code">${escapeHtmlStr(f.recommendation)}</code>
                    <button class="btn btn-small doctor-copy-btn" data-flag="${escapeHtmlAttr(f.recommendation)}">Copy</button>
                </div>` : ''}
            </div>
        </div>
    `).join('');

    // Wire copy buttons
    listEl.querySelectorAll('.doctor-copy-btn').forEach(btn => {
        btn.addEventListener('click', () => {
            const flag = btn.dataset.flag;
            navigator.clipboard.writeText(flag).then(() => {
                btn.textContent = 'Copied!';
                setTimeout(() => { btn.textContent = 'Copy'; }, 2000);
            });
        });
    });
}

function scoreClass(score) {
    if (score >= 80) return 'score-green';
    if (score >= 50) return 'score-amber';
    return 'score-red';
}

function scoreLabel(score) {
    if (score >= 80) return 'Healthy';
    if (score >= 50) return 'Degraded';
    return 'Critical';
}

function escapeHtmlStr(str) {
    if (!str) return '';
    return str.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
}

function escapeHtmlAttr(str) {
    if (!str) return '';
    return str.replace(/"/g, '&quot;');
}

function escapeSvg(str) {
    return escapeHtmlStr(str);
}
