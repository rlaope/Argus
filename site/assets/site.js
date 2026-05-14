(function () {
    'use strict';

    // --- Hamburger / mobile sidebar ---
    var hamburger = document.getElementById('hamburger-btn');
    var sidebar = document.getElementById('sidebar');
    var overlay = document.getElementById('sidebar-overlay');

    function openSidebar() {
        sidebar.classList.add('open');
        overlay.classList.add('visible');
    }

    function closeSidebar() {
        sidebar.classList.remove('open');
        overlay.classList.remove('visible');
    }

    if (hamburger && sidebar && overlay) {
        hamburger.addEventListener('click', function () {
            sidebar.classList.contains('open') ? closeSidebar() : openSidebar();
        });

        overlay.addEventListener('click', closeSidebar);

        // Close sidebar on nav link click (mobile)
        sidebar.querySelectorAll('a').forEach(function (link) {
            link.addEventListener('click', function () {
                if (window.innerWidth <= 768) closeSidebar();
            });
        });
    }

    // --- Scrollspy (per-page TOC highlight) ---
    var navLinks = Array.from(document.querySelectorAll('#sidebar .sidebar-nav a[data-target]'));

    // Build ordered list of section anchors that actually exist on this page
    var sections = navLinks.map(function (link) {
        var id = link.getAttribute('data-target');
        return { id: id, el: document.getElementById(id), link: link };
    }).filter(function (s) { return s.el !== null; });

    var headerHeight = parseInt(
        getComputedStyle(document.documentElement).getPropertyValue('--header-height') || '48', 10
    );

    function getScrollY() {
        return window.pageYOffset || document.documentElement.scrollTop;
    }

    function updateActive() {
        if (sections.length === 0) return;

        var scrollY = getScrollY();
        var threshold = headerHeight + 24;
        var activeId = null;

        // Find the last section whose top is above the threshold
        for (var i = 0; i < sections.length; i++) {
            var top = sections[i].el.getBoundingClientRect().top + scrollY;
            if (top - threshold <= scrollY) {
                activeId = sections[i].id;
            }
        }

        // If near top, default to first
        if (activeId === null && sections.length > 0) {
            activeId = sections[0].id;
        }

        navLinks.forEach(function (link) {
            link.classList.remove('active');
        });

        if (activeId) {
            var activeSection = sections.find(function (s) { return s.id === activeId; });
            if (activeSection) {
                activeSection.link.classList.add('active');
                // Scroll the active link into view within the sidebar
                activeSection.link.scrollIntoView({ block: 'nearest' });
            }
        }
    }

    var ticking = false;
    window.addEventListener('scroll', function () {
        if (!ticking) {
            requestAnimationFrame(function () {
                updateActive();
                ticking = false;
            });
            ticking = true;
        }
    }, { passive: true });

    // Initial call
    updateActive();
})();
