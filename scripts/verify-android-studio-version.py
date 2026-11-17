#!/usr/bin/env python3
"""Resolve an Android Studio release and run the complete local KEFS consumer checks."""
from __future__ import annotations

import argparse
import hashlib
import io
import json
import platform
import re
import subprocess
import sys
import tarfile
import tempfile
import urllib.request
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
RELEASES = 'https://jb.gg/android-studio-releases-list.xml'
UPDATES = 'https://plugins.jetbrains.com/api/plugins/26480/updates?size=100'
MARKETPLACE = 'https://plugins.jetbrains.com/files/'


def get_bytes(url):
    with urllib.request.urlopen(url, timeout=60) as response:
        return response.read()


def release_for(selector, xml, system='Darwin'):
    items = ET.fromstring(xml).findall('item')
    if selector == 'latest-stable':
        items = [item for item in items if item.findtext('channel') in ('Stable', 'Patch')]
        if not items:
            raise ValueError('The Android Studio feed contains no stable releases')
        item = max(items, key=lambda x: tuple(map(int, x.findtext('version').split('.'))))
    else:
        items = [item for item in items if selector in (item.findtext('version'), (item.findtext('build') or '').removeprefix('AI-'))]
        if len(items) != 1:
            raise ValueError(f'Expected one Android Studio release for {selector!r}, found {len(items)}; use the full version or build')
        item = items[0]
    suffix = {'Darwin': '-mac_arm.dmg', 'Linux': '-linux.tar.gz'}[system]
    downloads = [d for d in item.findall('download') if (d.findtext('link') or '').endswith(suffix)]
    if len(downloads) != 1:
        raise ValueError(f'No unique {system} download in Android Studio release')
    url = downloads[0].findtext('link')
    digest = downloads[0].findtext('checksum')
    if not url.startswith('https://') or not re.fullmatch(r'[0-9a-fA-F]{64}', digest or ''):
        raise ValueError('Invalid Android Studio download URL or SHA-256 in release feed')
    return {'name': item.findtext('name'), 'version': item.findtext('version'),
            'build': item.findtext('build').removeprefix('AI-'), 'url': url, 'sha256': digest.lower()}


def compatible(build, since, until):
    parts = tuple(int(p) for p in build.split('.'))
    def bound(value, upper):
        if not value:
            return (10**12,) if upper else (0,)
        return tuple(10**12 if x == '*' else int(x) for x in value.split('.'))
    return bound(since, False) <= parts <= bound(until, True)


def kefs_update(build, updates, preferred=None):
    candidates = [item for item in updates if item.get('approve') and item.get('listed') and
                  not item.get('hidden') and not item.get('channel') and
                  compatible(build, item.get('since'), item.get('until'))]
    if preferred:
        candidates = [item for item in candidates if item.get('version') == preferred]
    if not candidates:
        raise ValueError(f'No stable KEFS Marketplace update {preferred or ""} declares compatibility with IDE build {build}')
    return max(candidates, key=lambda x: (int(x.get('cdate', 0)), x['id']))


def compiler_version(ide, build, plugin_zip):
    kotlin_lib = next((p for p in (ide / 'Contents/plugins/Kotlin/lib/kotlinc.kotlin-compiler-common.jar',
                                    ide / 'plugins/Kotlin/lib/kotlinc.kotlin-compiler-common.jar') if p.is_file()), None)
    if kotlin_lib is None:
        raise ValueError(f'No bundled Kotlin compiler found in {ide}')
    with zipfile.ZipFile(kotlin_lib) as jar:
        raw = jar.read('META-INF/compiler.version').decode().strip()
    if not re.fullmatch(r'\d+\.\d+\.255-dev-\d+', raw):
        return raw
    with zipfile.ZipFile(io.BytesIO(plugin_zip)) as outer:
        for name in outer.namelist():
            if not name.endswith('.jar'):
                continue
            with zipfile.ZipFile(io.BytesIO(outer.read(name))) as inner:
                try:
                    mapping = inner.read('android-studio-kotlin-versions.properties').decode()
                except KeyError:
                    continue
                platform_build = '.'.join(build.split('.')[:3])
                entries = dict(line.split('=', 1) for line in mapping.splitlines()
                               if '=' in line and not line.lstrip().startswith(('#', '!')))
                if platform_build not in entries:
                    raise ValueError(f'KEFS has no Kotlin mapping for Android Studio platform build {platform_build}')
                return entries[platform_build].strip()
    raise ValueError('KEFS plugin has no Android Studio Kotlin version mapping')


def installed_build(ide):
    candidates = (ide / 'Contents/Resources/product-info.json', ide / 'product-info.json')
    info_path = next((p for p in candidates if p.is_file()), None)
    if info_path is None:
        raise ValueError(f'No product-info.json found in {ide}')
    info = json.loads(info_path.read_text())
    if info['name'] != 'Android Studio':
        raise ValueError(f'{ide} is not Android Studio')
    return info['buildNumber'].removeprefix('AI-')


def download_checked(url, sha256, destination):
    destination.parent.mkdir(parents=True, exist_ok=True)
    if destination.exists():
        digest = hashlib.sha256()
        with destination.open('rb') as cached:
            while chunk := cached.read(1024 * 1024):
                digest.update(chunk)
        if digest.hexdigest() == sha256:
            return
    temporary = destination.with_suffix('.download')
    try:
        digest = hashlib.sha256()
        with urllib.request.urlopen(url, timeout=120) as response, temporary.open('wb') as output:
            while chunk := response.read(1024 * 1024):
                output.write(chunk)
                digest.update(chunk)
        if digest.hexdigest() != sha256:
            raise ValueError('Android Studio download failed the official SHA-256 check')
        temporary.replace(destination)
    finally:
        temporary.unlink(missing_ok=True)


def extract_linux_archive(archive, destination):
    """Extract a verified archive without permitting paths or links outside the sandbox."""
    with tarfile.open(archive, 'r:gz') as source:
        root = destination.resolve()
        for member in source.getmembers():
            target = (root / member.name).resolve()
            if not target.is_relative_to(root):
                raise ValueError(f'Unsafe archive path: {member.name}')
            if member.issym() or member.islnk():
                link = (target.parent / member.linkname).resolve() if member.issym() else (root / member.linkname).resolve()
                if not link.is_relative_to(root):
                    raise ValueError(f'Unsafe archive link: {member.name}')
            if member.isdev() or member.isfifo():
                raise ValueError(f'Unsupported archive entry: {member.name}')
        source.extractall(destination)
    ide = destination / 'android-studio'
    if not ide.is_dir():
        raise ValueError('Android Studio archive has no android-studio directory')
    return ide


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('version', help='Full Android Studio version/build, or latest-stable')
    parser.add_argument('--ide-path', type=Path, help='Use this installed Android Studio if its build matches')
    parser.add_argument('--skip-ide-probe', action='store_true', help='Skip imported IDE consumer checks')
    parser.add_argument('--latest-kefs', action='store_true', help='Probe the newest compatible KEFS instead of a recorded tested version')
    parser.add_argument('--report-json', type=Path, help='Write resolved versions and test result')
    args = parser.parse_args(argv)
    system, machine = platform.system(), platform.machine()
    if (system, machine) not in (('Darwin', 'arm64'), ('Linux', 'x86_64')):
        parser.error('Automatic Android Studio installation requires Apple Silicon macOS or x86_64 Linux')
    mount = None
    mounted = False
    extraction = None
    report = {'selector': args.version, 'result': 'failed'}
    try:
        release = release_for(args.version, get_bytes(RELEASES), system)
        report['androidStudio'] = release
        print(f"Resolved {args.version}: {release['name']} (build {release['build']})", flush=True)
        ide = args.ide_path.expanduser().resolve() if args.ide_path else Path('/Applications/Android Studio.app' if system == 'Darwin' else '/opt/android-studio')
        if args.ide_path and installed_build(ide) != release['build']:
            raise ValueError(f"Installed IDE does not match requested build {release['build']}")
        if not ide.exists() or installed_build(ide) != release['build']:
            dmg = ROOT / 'build/ide-distributions' / (release['version'] + ('-mac-arm.dmg' if system == 'Darwin' else '-linux.tar.gz'))
            print(f'Preparing checksum-verified image from {release["url"]}', flush=True)
            download_checked(release['url'], release['sha256'], dmg)
            if system == 'Darwin':
                mount = Path(tempfile.mkdtemp(prefix='kpaths-studio-'))
                subprocess.run(['hdiutil', 'attach', '-readonly', '-nobrowse', '-mountpoint', str(mount), str(dmg)], check=True)
                mounted = True
                ide = mount / 'Android Studio.app'
            else:
                extraction = tempfile.TemporaryDirectory(prefix='kpaths-studio-')
                ide = extract_linux_archive(dmg, Path(extraction.name))
            if installed_build(ide) != release['build']:
                raise ValueError('Downloaded IDE build does not match the release feed')
        tested = json.loads((ROOT / 'kpaths-kefs-ide-fixture/ide-compatibility.json').read_text())['tested']
        matching = [entry for entry in tested if entry['product'] == 'Android Studio' and entry['build'] == release['build']]
        if len(matching) > 1:
            raise ValueError(f"Duplicate tested IDE build {release['build']}")
        preferred = matching[0]['kefsVersion'] if matching and not args.latest_kefs else None
        update = kefs_update(release['build'], json.loads(get_bytes(UPDATES)), preferred)
        kefs = update['version']
        plugin_zip = get_bytes(MARKETPLACE + update['file'].lstrip('/'))
        kotlin = compiler_version(ide, release['build'], plugin_zip)
        if preferred and kotlin != matching[0]['kotlinIdeVersion']:
            raise ValueError(f"KEFS resolved {kotlin}; the tested matrix records {matching[0]['kotlinIdeVersion']}")
        report.update({'idePath': str(ide), 'kefsVersion': kefs, 'ideKotlinVersion': kotlin,
                       'importedConsumer': 'skipped' if args.skip_ide_probe else 'requested'})
        print(f'Resolved KEFS {kefs}; IDE Kotlin compiler {kotlin}', flush=True)
        command = [sys.executable, str(ROOT / 'scripts/verify-kefs-compatibility.py'), '--ide-path', str(ide),
                   '--ide-kotlin-version', kotlin, '--kefs-version', kefs, '--allow-untested-combination']
        if not args.skip_ide_probe:
            command.append('--ide-probe')
        subprocess.run(command, cwd=ROOT, check=True)
        report['result'] = 'passed'
        if not args.skip_ide_probe:
            report['importedConsumer'] = 'passed'
        print('IDE compatibility checks passed.', flush=True)
        return 0
    except (OSError, ValueError, KeyError, ET.ParseError, tarfile.TarError, zipfile.BadZipFile, subprocess.CalledProcessError) as error:
        report['error'] = str(error)
        print(f'IDE compatibility check failed: {error}', file=sys.stderr)
        return 1
    finally:
        if extraction is not None:
            extraction.cleanup()
        if mount is not None:
            if mounted and subprocess.run(['hdiutil', 'detach', '-force', str(mount)], check=False).returncode:
                report['result'] = 'failed'
                report['error'] = f'Could not unmount temporary Android Studio image at {mount}'
                print(report['error'], file=sys.stderr)
            else:
                mount.rmdir()
        if args.report_json:
            args.report_json.parent.mkdir(parents=True, exist_ok=True)
            args.report_json.write_text(json.dumps(report, indent=2) + '\n')
        if report.get('error', '').startswith('Could not unmount'):
            return 1


if __name__ == '__main__':
    sys.exit(main())
