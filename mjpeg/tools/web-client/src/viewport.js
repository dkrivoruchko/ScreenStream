function createViewportLayout() {
    const shell = byId('shell'), safeArea = byId('safe-area');
    const listeners = [];
    function bounds() {
        const visual = window.visualViewport;
        const unzoomed = !visual || Math.abs(visual.scale - 1) < .01;
        // Percentage heights can stay at the small viewport after browser bars retract.
        // Keep the canvas at layout size; a keyboard constrains its usable rectangle below.
        if (unzoomed) {
            const height = Math.max(document.documentElement.clientHeight, window.innerHeight || 0);
            if (height > 0) shell.style.height = height + 'px';
        }
        const origin = shell.getBoundingClientRect(), width = shell.clientWidth, height = shell.clientHeight;
        let left = 0, top = 0, right = width, bottom = height;
        if (unzoomed) {
            const useVisual = visual && visual.width > 0 && visual.height > 0;
            left = (useVisual ? visual.offsetLeft : 0) - origin.left;
            top = (useVisual ? visual.offsetTop : 0) - origin.top;
            right = left + (useVisual ? visual.width : window.innerWidth || width);
            bottom = top + (useVisual ? visual.height : window.innerHeight || height);
        }
        // Zoom magnifies the existing layout; do not chase the shrunken visual viewport.
        const safe = window.getComputedStyle(safeArea);
        left = Math.min(width, Math.max(0, left, parseFloat(safe.left) || 0));
        top = Math.min(height, Math.max(0, top + 4, parseFloat(safe.top) || 0));
        right = Math.max(left, Math.min(width, right, width - (parseFloat(safe.right) || 0)));
        bottom = Math.max(top, Math.min(height, bottom - 4, height - (parseFloat(safe.bottom) || 0)));
        return { left, top, right, bottom, width: right - left, height: bottom - top };
    }
    function refresh() {
        const value = bounds();
        listeners.forEach(listener => listener(value));
    }
    function keepFocusVisible(panel) {
        const focused = document.activeElement;
        if (panel.hidden || !focused || focused === panel || focused.disabled || !panel.contains(focused) || panel.clientHeight <= 0) return;
        const field = focused.getBoundingClientRect(), box = panel.getBoundingClientRect();
        if (field.bottom <= field.top) return;
        // Our own later relayout need not repeat the browser's initial focus scroll.
        const top = box.top + panel.clientTop, bottom = top + panel.clientHeight;
        if (field.top < top || field.bottom - field.top > panel.clientHeight) panel.scrollTop += field.top - top;
        else if (field.bottom > bottom) panel.scrollTop += field.bottom - bottom;
    }
    ['resize', 'orientationchange', 'scroll', 'pageshow'].forEach(name => window.addEventListener(name, refresh));
    ['fullscreenchange', 'webkitfullscreenchange', 'mozfullscreenchange', 'MSFullscreenChange'].forEach(name => {
        document.addEventListener(name, refresh);
    });
    if (window.visualViewport) {
        window.visualViewport.addEventListener('resize', refresh);
        window.visualViewport.addEventListener('scroll', refresh);
    }
    return { bounds, keepFocusVisible, watch: listener => { listeners.push(listener); listener(bounds()); } };
}
