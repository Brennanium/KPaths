import importlib.util
import hashlib
import io
import json
import tempfile
import tarfile
import unittest
import zipfile
from pathlib import Path
from unittest import mock

spec = importlib.util.spec_from_file_location('studio', Path(__file__).with_name('verify-android-studio-version.py'))
studio = importlib.util.module_from_spec(spec)
spec.loader.exec_module(studio)


def zipped(entries):
    output = io.BytesIO()
    with zipfile.ZipFile(output, 'w') as archive:
        for name, contents in entries.items():
            archive.writestr(name, contents)
    return output.getvalue()


class AndroidStudioVersionTests(unittest.TestCase):
    def test_resolves_stable_and_exact_with_validated_download(self):
        def item(version, channel, build):
            return f'''<item><name>Studio {version}</name><version>{version}</version><build>AI-{build}</build><channel>{channel}</channel><download><link>https://example.org/studio-mac_arm.dmg</link><checksum>{'a' * 64}</checksum></download><download><link>https://example.org/studio-linux.tar.gz</link><checksum>{'b' * 64}</checksum></download></item>'''
        feed = '<content>' + item('2026.2.2.8', 'Stable', '262.1.1') + item('2026.2.2.9', 'Patch', '262.1.2') + item('2026.2.3.1', 'Canary', '262.1.3') + '</content>'
        self.assertEqual(studio.release_for('latest-stable', feed)['version'], '2026.2.2.9')
        self.assertEqual(studio.release_for('2026.2.3.1', feed)['build'], '262.1.3')
        self.assertTrue(studio.release_for('2026.2.3.1', feed, 'Linux')['url'].endswith('-linux.tar.gz'))
        release_feed = '<content>' + item('2026.2.2.9', 'Patch', '262.1.2') + item('2026.3.1.1', 'Release', '263.1.1') + '</content>'
        self.assertEqual(studio.release_for('latest-stable', release_feed)['version'], '2026.3.1.1')
        with self.assertRaisesRegex(ValueError, 'Expected one'):
            studio.release_for('2026.2.2', feed)

    def test_selects_only_compatible_stable_kefs(self):
        updates = [dict(id=1, version='old', cdate='1', approve=True, listed=True, since='261.0', until='261.*'),
                   dict(id=2, version='good', cdate='2', approve=True, listed=True, since='262.0', until='262.*'),
                   dict(id=3, version='preview', cdate='3', approve=True, listed=True, channel='EAP', since='262.0', until='262.*')]
        self.assertEqual(studio.kefs_update('262.10968.63.2622.1', updates)['version'], 'good')
        self.assertEqual(studio.kefs_update('262.10968.63.2622.1', updates, 'good')['version'], 'good')
        with self.assertRaisesRegex(ValueError, 'No stable KEFS'):
            studio.kefs_update('262.10968.63.2622.1', updates, 'old')
        with self.assertRaisesRegex(ValueError, 'No stable KEFS'):
            studio.kefs_update('263.1', updates)

    def test_uses_published_kefs_mapping_for_android_studio_stub(self):
        with tempfile.TemporaryDirectory() as directory:
            ide = Path(directory)
            jar = ide / 'Contents/plugins/Kotlin/lib/kotlinc.kotlin-compiler-common.jar'
            jar.parent.mkdir(parents=True)
            jar.write_bytes(zipped({'META-INF/compiler.version': '2.4.255-dev-255'}))
            inner = zipped({'android-studio-kotlin-versions.properties': '262.10968.63=2.4.20-ij262-52\n'})
            plugin = zipped({'plugin/lib/plugin.jar': inner})
            self.assertEqual(studio.compiler_version(ide, '262.10968.63.2622.1', plugin), '2.4.20-ij262-52')
            with self.assertRaisesRegex(ValueError, 'no Kotlin mapping'):
                studio.compiler_version(ide, '262.999.1', plugin)

    def test_rejects_mismatched_ide_and_invalid_download(self):
        with tempfile.TemporaryDirectory() as directory:
            ide = Path(directory)
            info = ide / 'Contents/Resources/product-info.json'
            info.parent.mkdir(parents=True)
            info.write_text(json.dumps({'name': 'IntelliJ IDEA', 'buildNumber': '262.1'}))
            with self.assertRaisesRegex(ValueError, 'not Android Studio'):
                studio.installed_build(ide)
        with self.assertRaises(ValueError):
            studio.release_for('latest-stable', '<content><item><channel>Stable</channel><version>1.2.3.4</version></item></content>')

    def test_download_checks_hash_and_reuses_verified_cache(self):
        data = b'android studio disk image'
        digest = hashlib.sha256(data).hexdigest()
        with tempfile.TemporaryDirectory() as directory:
            image = Path(directory) / 'studio.dmg'
            with mock.patch.object(studio.urllib.request, 'urlopen', return_value=io.BytesIO(data)) as fetch:
                studio.download_checked('https://example.org/studio.dmg', digest, image)
                studio.download_checked('https://example.org/studio.dmg', digest, image)
                self.assertEqual(fetch.call_count, 1)
                self.assertEqual(image.read_bytes(), data)
            with mock.patch.object(studio.urllib.request, 'urlopen', return_value=io.BytesIO(b'bad')):
                with self.assertRaisesRegex(ValueError, 'SHA-256'):
                    studio.download_checked('https://example.org/studio.dmg', '0' * 64, image)
            self.assertEqual(image.read_bytes(), data)

    def test_linux_archive_and_product_layout(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            archive = root / 'studio.tar.gz'
            info = json.dumps({'name': 'Android Studio', 'buildNumber': 'AI-262.1'}).encode()
            compiler = zipped({'META-INF/compiler.version': '2.4.20-ij262-52'})
            with tarfile.open(archive, 'w:gz') as tar:
                for name, data in [('android-studio/product-info.json', info),
                                   ('android-studio/plugins/Kotlin/lib/kotlinc.kotlin-compiler-common.jar', compiler)]:
                    member = tarfile.TarInfo(name)
                    member.size = len(data)
                    tar.addfile(member, io.BytesIO(data))
            ide = studio.extract_linux_archive(archive, root / 'out')
            self.assertEqual(studio.installed_build(ide), '262.1')
            self.assertEqual(studio.compiler_version(ide, '262.1', b''), '2.4.20-ij262-52')

    def test_linux_archive_rejects_traversal(self):
        with tempfile.TemporaryDirectory() as directory:
            archive = Path(directory) / 'bad.tar.gz'
            with tarfile.open(archive, 'w:gz') as tar:
                member = tarfile.TarInfo('../outside')
                member.size = 1
                tar.addfile(member, io.BytesIO(b'x'))
            with self.assertRaisesRegex(ValueError, 'Unsafe archive path'):
                studio.extract_linux_archive(archive, Path(directory) / 'out')

    def test_linux_archive_rejects_external_symlink(self):
        with tempfile.TemporaryDirectory() as directory:
            archive = Path(directory) / 'bad.tar.gz'
            with tarfile.open(archive, 'w:gz') as tar:
                member = tarfile.TarInfo('android-studio/escape')
                member.type = tarfile.SYMTYPE
                member.linkname = '../../outside'
                tar.addfile(member)
            with self.assertRaisesRegex(ValueError, 'Unsafe archive link'):
                studio.extract_linux_archive(archive, Path(directory) / 'out')

    def test_project_driver_receives_selected_ide_and_reports_result(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            ide = root / 'studio'
            ide.mkdir()
            driver = root / 'custom-driver'
            driver.write_text('driver')
            report = root / 'report.json'
            release = {'name': 'Android Studio', 'version': '1.2.3.4', 'build': '262.1'}
            with mock.patch.object(studio.platform, 'system', return_value='Linux'), \
                 mock.patch.object(studio.platform, 'machine', return_value='x86_64'), \
                 mock.patch.object(studio, 'get_bytes', return_value=b'feed'), \
                 mock.patch.object(studio, 'release_for', return_value=release), \
                 mock.patch.object(studio, 'installed_build', return_value='262.1'), \
                 mock.patch.object(studio.subprocess, 'run') as run:
                self.assertEqual(studio.main(['1.2.3.4', '--ide-path', str(ide), '--driver', str(driver), '--report-json', str(report)]), 0)
            run.assert_called_once_with([str(driver.resolve()), '--ide-path', str(ide.resolve())], cwd=driver.resolve().parent, check=True)
            result = json.loads(report.read_text())
            self.assertEqual(result['result'], 'passed')
            self.assertEqual(result['driver'], str(driver.resolve()))

    def test_project_driver_failure_is_not_reported_as_success(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            ide = root / 'studio'
            ide.mkdir()
            driver = root / 'driver'
            driver.write_text('driver')
            report = root / 'report.json'
            release = {'name': 'Android Studio', 'version': '1.2.3.4', 'build': '262.1'}
            with mock.patch.object(studio.platform, 'system', return_value='Linux'), \
                 mock.patch.object(studio.platform, 'machine', return_value='x86_64'), \
                 mock.patch.object(studio, 'get_bytes', return_value=b'feed'), \
                 mock.patch.object(studio, 'release_for', return_value=release), \
                 mock.patch.object(studio, 'installed_build', return_value='262.1'), \
                 mock.patch.object(studio.subprocess, 'run', side_effect=studio.subprocess.CalledProcessError(1, 'driver')):
                self.assertEqual(studio.main(['1.2.3.4', '--ide-path', str(ide), '--driver', str(driver), '--report-json', str(report)]), 1)
            self.assertEqual(json.loads(report.read_text())['result'], 'failed')

    def test_project_driver_runs_as_a_real_executable(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve()
            ide = root / 'studio'
            ide.mkdir()
            driver = root / 'driver'
            driver.write_text('#!/bin/sh\nset -eu\nprintf "%s\\n" "$@" > received-arguments\n')
            driver.chmod(0o755)
            release = {'name': 'Android Studio', 'version': '1.2.3.4', 'build': '262.1'}
            with mock.patch.object(studio.platform, 'system', return_value='Linux'), \
                 mock.patch.object(studio.platform, 'machine', return_value='x86_64'), \
                 mock.patch.object(studio, 'get_bytes', return_value=b'feed'), \
                 mock.patch.object(studio, 'release_for', return_value=release), \
                 mock.patch.object(studio, 'installed_build', return_value='262.1'):
                self.assertEqual(studio.main(['1.2.3.4', '--ide-path', str(ide), '--driver', str(driver)]), 0)
            self.assertEqual((root / 'received-arguments').read_text().splitlines(), ['--ide-path', str(ide)])


if __name__ == '__main__':
    unittest.main()
