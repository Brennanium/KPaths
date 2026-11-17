#!/usr/bin/env python3
"""Prototype: prepare a local Gradle consumer from explicit build project roles.

This is not yet the editor-testing CLI or the assertEditor source-host API.
"""
import argparse
import json
import subprocess
import tempfile
from pathlib import Path
from editor_project_model import generate_project
from editor_testing_config import read_config
from matrix_project_root import project_root


def prepare_checkout(root, gradle_plugin_project, compiler_plugin_project, runtime_projects, kotlin_version=None, offline=False):
    """Build current-checkout artifacts and export their evaluated Gradle metadata."""
    wrapper = root / 'gradlew'
    with tempfile.TemporaryDirectory(prefix='editor-model-') as temporary:
        output = Path(temporary) / 'model.json'
        command = [str(wrapper), '--no-configuration-cache', '-I', str(root / 'gradle/local-test-repository.init.gradle'),
                   '-I', str(root / 'gradle/editor-testing-model.init.gradle'), 'exportEditorTestingModel',
                   f'{gradle_plugin_project}:publishAllPublicationsToConsumerVerificationRepository',
                   f'{compiler_plugin_project}:publishJvmPublicationToConsumerVerificationRepository']
        for project in runtime_projects:
            command.extend((f'{project}:publishKotlinMultiplatformPublicationToConsumerVerificationRepository',
                            f'{project}:publishJvmPublicationToConsumerVerificationRepository'))
        command.extend((f'-PeditorTesting.gradlePluginProject={gradle_plugin_project}',
                        f'-PeditorTesting.compilerPluginProject={compiler_plugin_project}',
                        f'-PeditorTesting.runtimeProjects={",".join(runtime_projects)}',
                        f'-PeditorTesting.modelFile={output}'))
        if kotlin_version:
            command.append(f'-PeditorTesting.kotlinVersion={kotlin_version}')
        if offline:
            command.append('--offline')
        subprocess.run(command, cwd=root, check=True)
        model = json.loads(output.read_text())
    return model


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--config', type=Path, help='Project configuration (defaults to editor-testing.toml)')
    parser.add_argument('--gradle-plugin-project', help='Gradle project providing the plugin under test')
    parser.add_argument('--compiler-plugin-project', help='Gradle project publishing the compiler plugin')
    parser.add_argument('--runtime-project', action='append', help='Gradle project publishing a runtime dependency; repeat as needed')
    parser.add_argument('--project-dir', type=Path, required=True, help='New destination for the generated project')
    parser.add_argument('--source-dir', type=Path, help='Directory of Kotlin source files relative to the source root')
    parser.add_argument('--kotlin-version', help='Override the build catalog version for the generated consumer')
    parser.add_argument('--offline', action='store_true', help='Use cached dependencies only')
    parser.add_argument('--compile', action='store_true', help='Compile the generated consumer after preparation')
    args = parser.parse_args(argv)
    root = project_root()
    if not (args.gradle_plugin_project and args.compiler_plugin_project and args.runtime_project):
        try:
            roles = read_config(args.config or root / 'editor-testing.toml')['project']
        except (OSError, ValueError) as error:
            parser.error(str(error))
        args.gradle_plugin_project = args.gradle_plugin_project or roles['gradle-plugin']
        args.compiler_plugin_project = args.compiler_plugin_project or roles['compiler-plugin']
        args.runtime_project = args.runtime_project or roles['runtime']
    wrapper = root / 'gradlew'
    repository = root / 'build/consumer-verification/repository'
    sources = {}
    if args.source_dir:
        source_root = args.source_dir.resolve(strict=True)
        sources = {str(path.relative_to(source_root)): path.read_text() for path in source_root.rglob('*.kt')}
    model = prepare_checkout(root, args.gradle_plugin_project, args.compiler_plugin_project,
                             args.runtime_project, args.kotlin_version, args.offline)
    project = generate_project(args.project_dir, model, repository, root, sources)
    print(f'Generated temporary consumer: {project}', flush=True)
    if args.compile:
        command = [str(wrapper), '-p', str(project), 'compileKotlin', '--no-configuration-cache']
        if args.offline:
            command.append('--offline')
        subprocess.run(command, cwd=root, check=True)
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
