#!/usr/bin/env python3
"""Add navigation and shared styling to generated Dokka and release documentation."""
import argparse
import html
import json
import os
import re
import shutil
from pathlib import Path

VERSION = re.compile(r'^(\d+)\.(\d+)\.(\d+)$')
MARKER = 'data-kpaths-site-assets'


def asset_path(page: Path, asset: Path) -> str:
    return Path(os.path.relpath(asset, page.parent)).as_posix()


def add_assets(page: Path, site: Path, stylesheet: str, palette=None) -> None:
    contents = page.read_text(encoding='utf-8')
    if MARKER in contents:
        return
    # Dokka also emits navigation.html fragments, which are not standalone pages.
    if '<html' not in contents.lower():
        return
    if '</head>' not in contents or '</body>' not in contents:
        raise ValueError(f'Cannot install documentation navigation in {page}: missing HTML boundaries')
    css = html.escape(asset_path(page, site / 'assets/stylesheets' / stylesheet), quote=True)
    js = html.escape(asset_path(page, site / 'assets/javascripts/site.js'), quote=True)
    palette_link = ''
    if palette and not re.search(r'href=["\'][^"\']*/palette\.[^"\']+\.css', contents):
        palette_href = html.escape(asset_path(page, palette), quote=True)
        palette_link = f'<link {MARKER} rel="stylesheet" href="{palette_href}">\n'
    contents = contents.replace('</head>', f'{palette_link}<link {MARKER} rel="stylesheet" href="{css}">\n</head>', 1)
    contents = contents.replace('</body>', f'<script {MARKER} defer src="{js}"></script>\n</body>', 1)
    page.write_text(contents, encoding='utf-8')


def finalize(site: Path) -> None:
    site = site.resolve()
    if not (site / 'index.html').is_file():
        raise ValueError(f'Not a built documentation site: {site}')
    assets = [site / 'assets/javascripts/site.js', site / 'assets/stylesheets/kpaths.css',
              site / 'assets/stylesheets/dokka.css', site / 'assets/images/kpaths-mark.svg']
    if not all(path.is_file() for path in assets):
        raise ValueError('KPaths documentation assets are missing; build the guide site first')
    palettes = list((site / 'assets/stylesheets').glob('palette.*.min.css'))
    if len(palettes) != 1:
        raise ValueError('Expected one Material palette stylesheet in the built guide site')
    palette = palettes[0]
    releases = []
    for path in site.iterdir():
        match = VERSION.fullmatch(path.name)
        if path.is_dir() and match and (path / 'index.html').is_file():
            releases.append((tuple(map(int, match.groups())), path))
    releases.sort(key=lambda item: item[0], reverse=True)
    versions = [{'label': 'main', 'path': ''}] + [
        {'label': path.name, 'path': f'{path.name}/'} for _, path in releases
    ]
    (site / 'versions.json').write_text(json.dumps(versions, indent=2) + '\n', encoding='utf-8')
    api_roots = [site / 'api'] + [release / 'api' for _, release in releases]
    for api in api_roots:
        if api.is_dir():
            (api / 'images').mkdir(exist_ok=True)
            shutil.copy2(site / 'assets/images/kpaths-mark.svg', api / 'images/kpaths-mark.svg')
    for page in (site / 'api').rglob('*.html'):
        add_assets(page, site, 'dokka.css')
    for _, release in releases:
        for page in release.rglob('*.html'):
            stylesheet = 'dokka.css' if page.is_relative_to(release / 'api') else 'kpaths.css'
            add_assets(page, site, stylesheet, palette if stylesheet == 'kpaths.css' else None)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('site', type=Path)
    args = parser.parse_args()
    finalize(args.site)


if __name__ == '__main__':
    main()
