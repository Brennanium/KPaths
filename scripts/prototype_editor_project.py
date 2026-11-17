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
from matrix_project_root import project_root


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--gradle-plugin-project', required=True, help='Gradle project providing the plugin under test')
    parser.add_argument('--compiler-plugin-project', required=True, help='Gradle project publishing the compiler plugin')
    parser.add_argument('--runtime-project', action='append', required=True, help='Gradle project publishing a runtime dependency; repeat as needed')
    parser.add_argument('--project-dir', type=Path, required=True, help='New destination for the generated project')
    parser.add_argument('--source-dir', type=Path, help='Directory of Kotlin source files relative to the source root')
    parser.add_argument('--kotlin-version', help='Override the build catalog version for the generated consumer')
    parser.add_argument('--offline', action='store_true', help='Use cached dependencies only')
    parser.add_argument('--compile', action='store_true', help='Compile the generated consumer after preparation')
    args = parser.parse_args(argv)
    root = project_root()
    wrapper = root / 'gradlew'
    repository = root / 'build/consumer-verification/repository'
    sources = {}
    if args.source_dir:
        source_root = args.source_dir.resolve(strict=True)
        sources = {str(path.relative_to(source_root)): path.read_text() for path in source_root.rglob('*.kt')}
    with tempfile.TemporaryDirectory(prefix='editor-model-') as temporary:
        output = Path(temporary) / 'model.json'
        command = [str(wrapper), '--no-configuration-cache', '-I', str(root / 'gradle/local-test-repository.init.gradle'),
                   '-I', str(root / 'gradle/editor-testing-model.init.gradle'), 'exportEditorTestingModel',
                   f'{args.gradle_plugin_project}:publishAllPublicationsToConsumerVerificationRepository',
                   f'{args.compiler_plugin_project}:publishJvmPublicationToConsumerVerificationRepository']
        for project in args.runtime_project:
            command.extend((f'{project}:publishKotlinMultiplatformPublicationToConsumerVerificationRepository',
                            f'{project}:publishJvmPublicationToConsumerVerificationRepository'))
        command.extend((f'-PeditorTesting.gradlePluginProject={args.gradle_plugin_project}',
                        f'-PeditorTesting.compilerPluginProject={args.compiler_plugin_project}',
                        f'-PeditorTesting.runtimeProjects={",".join(args.runtime_project)}',
                        f'-PeditorTesting.modelFile={output}'))
        if args.kotlin_version:
            command.append(f'-PeditorTesting.kotlinVersion={args.kotlin_version}')
        if args.offline:
            command.append('--offline')
        subprocess.run(command, cwd=root, check=True)
        model = json.loads(output.read_text())
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
