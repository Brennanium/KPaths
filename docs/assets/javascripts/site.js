/* Shared navigation for the guides and the Dokka reference. */
(() => {
  const script = document.currentScript;
  if (!script || !script.src) return;
  const siteRoot = new URL('../../', script.src);
  const storageKey = 'dokka-dark-mode';

  function storageGet(key) {
    try { return localStorage.getItem(key); } catch (_) { return null; }
  }
  function storageSet(key, value) {
    try { localStorage.setItem(key, value); } catch (_) { /* Storage may be disabled. */ }
  }

  function syncGuideTheme() {
    const scheme = document.body.dataset.mdColorScheme;
    if (scheme === 'slate' || scheme === 'default') {
      storageSet(storageKey, JSON.stringify(scheme === 'slate'));
    }
  }

  if (document.body.classList.contains('md-typeset') || document.querySelector('.md-header')) {
    syncGuideTheme();
    new MutationObserver(syncGuideTheme).observe(document.body, {
      attributes: true, attributeFilter: ['data-md-color-scheme'],
    });
    // Older release snapshots do not include the Material palette switcher.
    const header = document.querySelector('.md-header__inner');
    if (header && !header.querySelector('[data-md-component="palette"]')) {
      const button = document.createElement('button');
      button.type = 'button';
      button.className = 'kpaths-theme-toggle';
      button.setAttribute('aria-label', 'Switch between light and dark mode');
      button.textContent = '◐';
      button.addEventListener('click', () => {
        const dark = document.body.dataset.mdColorScheme !== 'slate';
        document.body.dataset.mdColorScheme = dark ? 'slate' : 'default';
        document.body.dataset.mdColorPrimary = dark ? 'black' : 'white';
        document.body.dataset.mdColorAccent = 'indigo';
        storageSet(storageKey, JSON.stringify(dark));
      });
      if (storageGet(storageKey) === 'true') document.body.dataset.mdColorScheme = 'slate';
      const source = header.querySelector('.md-header__source');
      header.insertBefore(button, source || null);
    }
  }

  fetch(new URL('versions.json', siteRoot))
    .then(response => response.ok ? response.json() : Promise.reject(new Error('Versions unavailable')))
    .then(versions => {
      if (!Array.isArray(versions) || !versions.length) return;
      const relative = decodeURI(location.pathname.slice(siteRoot.pathname.length));
      const current = versions.find(version => version.path && relative.startsWith(version.path)) || versions[0];
      const page = current.path ? relative.slice(current.path.length) : relative;
      const isApi = page === 'api' || page.startsWith('api/');
      const header = document.querySelector('.md-header__inner');
      const apiHeader = document.querySelector('#navigation-wrapper .navigation-controls');
      const target = header || apiHeader;
      if (!target || target.querySelector('.kpaths-version')) return;

      const wrapper = document.createElement('label');
      wrapper.className = 'kpaths-version';
      const label = document.createElement('span');
      label.className = 'kpaths-version__label';
      label.textContent = 'Version';
      const select = document.createElement('select');
      select.setAttribute('aria-label', 'Documentation version');
      versions.forEach(version => {
        const option = document.createElement('option');
        option.value = version.path;
        option.textContent = version.label;
        option.selected = version.path === current.path;
        select.append(option);
      });
      wrapper.append(label, select);
      if (header) target.insertBefore(wrapper, header.querySelector('.md-header__source'));
      else target.prepend(wrapper);

      select.addEventListener('change', async () => {
        const versionRoot = new URL(select.value, siteRoot);
        const destination = new URL(page + location.search + location.hash, versionRoot);
        try {
          const response = await fetch(destination, { method: 'HEAD' });
          location.assign(response.ok ? destination.href : versionRoot.href);
        } catch (_) {
          location.assign(versionRoot.href);
        }
      });

      if (isApi && apiHeader) {
        const originalVersion = document.querySelector('#library-version');
        if (originalVersion) originalVersion.hidden = true;
        const home = document.createElement('a');
        home.className = 'kpaths-api-home';
        home.href = new URL(current.path, siteRoot).href;
        home.textContent = 'Guides';
        apiHeader.prepend(home);
        const toggle = document.querySelector('#theme-toggle-button');
        if (toggle) toggle.addEventListener('click', () => {
          setTimeout(() => {
            const dark = document.documentElement.classList.contains('theme-dark');
            const scope = new URL(current.path, siteRoot).pathname + '.__palette';
            const palette = {
              color: { scheme: dark ? 'slate' : 'default', primary: dark ? 'black' : 'white',
                accent: 'indigo', media: dark ? '(prefers-color-scheme: dark)' : '(prefers-color-scheme: light)' },
            };
            storageSet(scope, JSON.stringify(palette));
          }, 0);
        });
      }
    })
    .catch(() => { /* Documentation remains usable without version metadata. */ });
})();
