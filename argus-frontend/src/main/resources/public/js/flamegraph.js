/**
 * Minimal, dependency-free flamegraph renderer (vendored).
 *
 * The repo had no reusable d3-flamegraph component — the `argus flame` CLI lets
 * async-profiler emit its own standalone HTML — so this is a small from-scratch
 * SVG renderer. No build step, no d3, no external deps: it consumes the
 * hierarchical tree the aggregator builds server-side
 * ({@code {name,value,children:[...]}}) and the diff variant
 * ({@code {name,value,head,base,delta,children:[...]}}).
 *
 * Public API (attached to window.ArgusFlame):
 *   render(container, root, opts)
 *     container : DOM element to render into (cleared first)
 *     root      : the tree node (synthetic "root")
 *     opts.mode : 'plain' (default) or 'diff'
 *     opts.onZoom(node) : optional callback when a frame is clicked
 *
 * Layout: classic icicle (root on top, children below). Each frame's width is
 * proportional to its inclusive `value` within the current zoom root. Click a
 * frame to zoom; click the root row to reset.
 */
(function () {
    'use strict';
    if (window.ArgusFlame) return;

    var ROW_HEIGHT = 18;
    var MIN_LABEL_WIDTH = 28; // px below which the label is dropped

    function esc(s) {
        return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) {
            return ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c];
        });
    }

    // Stable hue from a frame name so sibling frames are visually distinct
    // (warm "flame" palette in plain mode).
    function plainColor(name) {
        var h = 0;
        for (var i = 0; i < name.length; i++) {
            h = (h * 31 + name.charCodeAt(i)) & 0xffffffff;
        }
        var hue = 20 + (Math.abs(h) % 35);      // 20–55deg: reds→oranges→yellows
        var sat = 70 + (Math.abs(h >> 8) % 20); // 70–90%
        return 'hsl(' + hue + ',' + sat + '%,55%)';
    }

    // Diff color: green = grew (positive delta), red = shrank, grey = unchanged.
    // Saturation scales with |delta| relative to the node's own magnitude.
    function diffColor(node) {
        var delta = node.delta || 0;
        var mag = Math.max(node.head || 0, node.base || 0, 1);
        var frac = Math.min(1, Math.abs(delta) / mag);
        var light = 70 - Math.round(frac * 30); // 70%→40% as the change grows
        if (delta > 0) return 'hsl(130,60%,' + light + '%)';
        if (delta < 0) return 'hsl(5,70%,' + light + '%)';
        return 'hsl(210,8%,55%)';
    }

    function maxDepth(node, d) {
        var m = d;
        if (node.children) {
            for (var i = 0; i < node.children.length; i++) {
                m = Math.max(m, maxDepth(node.children[i], d + 1));
            }
        }
        return m;
    }

    function render(container, root, opts) {
        opts = opts || {};
        var mode = opts.mode === 'diff' ? 'diff' : 'plain';
        container.innerHTML = '';

        if (!root || !(root.value > 0) && !(mode === 'diff' && (root.head > 0 || root.base > 0))) {
            var empty = document.createElement('div');
            empty.className = 'flame-empty';
            empty.textContent = 'No profile samples in this window.';
            container.appendChild(empty);
            return;
        }

        var width = container.clientWidth || 900;
        var depth = maxDepth(root, 0) + 1;
        var height = depth * ROW_HEIGHT;

        var svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
        svg.setAttribute('width', '100%');
        svg.setAttribute('height', height);
        svg.setAttribute('viewBox', '0 0 ' + width + ' ' + height);
        svg.setAttribute('preserveAspectRatio', 'none');
        svg.classList.add('flame-svg');

        var tip = document.createElement('div');
        tip.className = 'flame-tooltip';
        tip.hidden = true;

        // Total used to scale the current zoom root to full width.
        var zoomTotal = (mode === 'diff')
            ? Math.max(root.head || 0, root.base || 0, root.value || 0, 1)
            : (root.value || 1);

        function draw(node, depthIdx, x0, w) {
            if (w < 0.5) return;
            var g = document.createElementNS('http://www.w3.org/2000/svg', 'g');
            var rect = document.createElementNS('http://www.w3.org/2000/svg', 'rect');
            rect.setAttribute('x', x0);
            rect.setAttribute('y', depthIdx * ROW_HEIGHT);
            rect.setAttribute('width', Math.max(0, w - 1));
            rect.setAttribute('height', ROW_HEIGHT - 1);
            rect.setAttribute('fill', mode === 'diff' ? diffColor(node) : plainColor(node.name));
            rect.classList.add('flame-rect');
            g.appendChild(rect);

            if (w >= MIN_LABEL_WIDTH) {
                var label = document.createElementNS('http://www.w3.org/2000/svg', 'text');
                label.setAttribute('x', x0 + 3);
                label.setAttribute('y', depthIdx * ROW_HEIGHT + 13);
                label.classList.add('flame-label');
                // Trim by chars; SVG text clip via overflow on the rect group.
                var maxChars = Math.floor((w - 6) / 6.2);
                var name = node.name.length > maxChars ? node.name.slice(0, maxChars - 1) + '…' : node.name;
                label.textContent = name;
                g.appendChild(label);
            }

            g.style.cursor = 'pointer';
            g.addEventListener('mousemove', function (ev) {
                tip.hidden = false;
                tip.innerHTML = tooltipHtml(node, mode);
                var cr = container.getBoundingClientRect();
                tip.style.left = (ev.clientX - cr.left + 12) + 'px';
                tip.style.top = (ev.clientY - cr.top + 12) + 'px';
            });
            g.addEventListener('mouseleave', function () { tip.hidden = true; });
            g.addEventListener('click', function (ev) {
                ev.stopPropagation();
                if (opts.onZoom) opts.onZoom(node);
                rerenderFrom(node);
            });

            svg.appendChild(g);

            // Children laid out left-to-right under the parent.
            if (node.children && node.children.length) {
                var childX = x0;
                for (var i = 0; i < node.children.length; i++) {
                    var c = node.children[i];
                    var cv = childInclusive(c, mode);
                    var cw = (cv / zoomTotal) * width;
                    draw(c, depthIdx + 1, childX, cw);
                    childX += cw;
                }
            }
        }

        function rerenderFrom(node) {
            // Zoom: re-render with `node` as the new root occupying full width.
            render(container, node, opts);
        }

        var rootInclusive = childInclusive(root, mode);
        zoomTotal = rootInclusive || 1;
        draw(root, 0, 0, width);

        // Click anywhere on empty svg area resets to the supplied root only if
        // we are already at it; caller controls full reset via its own button.
        container.appendChild(svg);
        container.appendChild(tip);
    }

    function childInclusive(node, mode) {
        if (mode === 'diff') {
            return Math.max(node.head || 0, node.base || 0, node.value || 0);
        }
        return node.value || 0;
    }

    function tooltipHtml(node, mode) {
        if (mode === 'diff') {
            var sign = (node.delta || 0) > 0 ? '+' : '';
            return '<div class="flame-tip-name">' + esc(node.name) + '</div>' +
                '<div class="flame-tip-row">head: ' + (node.head || 0) +
                ' · base: ' + (node.base || 0) + '</div>' +
                '<div class="flame-tip-row">Δ ' + sign + (node.delta || 0) + '</div>';
        }
        return '<div class="flame-tip-name">' + esc(node.name) + '</div>' +
            '<div class="flame-tip-row">' + (node.value || 0) + ' samples</div>';
    }

    window.ArgusFlame = Object.freeze({ render: render });
})();
