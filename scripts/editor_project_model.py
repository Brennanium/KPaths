"""Prototype for generating an imported Gradle project from evaluated build metadata."""
import json
import shutil
from pathlib import Path


def kotlin_literal(value: str) -> str:
    """Quote data as a Kotlin string without permitting string interpolation."""
    return json.dumps(value, ensure_ascii=True).replace('$', '\\$')


def generate_project(destination: Path, model: dict, repository: Path, wrapper_root: Path,
                     sources: dict[str, str]) -> Path:
    """Generate a disposable JVM project; artifact preparation belongs to the caller."""
    if model.get('schemaVersion') != 1:
        raise ValueError('Unsupported editor testing model schema')
    plugin = model.get('gradlePlugin')
    dependencies = model.get('dependencies', [])
    root = destination.resolve()
    for name in sources:
        relative = Path(name)
        if relative.is_absolute() or not relative.parts or '..' in relative.parts:
            raise ValueError(f'Invalid source path: {name}')
    root.mkdir(parents=True, exist_ok=False)
    local = kotlin_literal(str(repository.resolve()))
    (root / 'settings.gradle.kts').write_text(f'''pluginManagement {{
  repositories {{ maven {{ url = uri({local}) }}; gradlePluginPortal(); mavenCentral() }}
}}
dependencyResolutionManagement {{
  repositories {{ maven {{ url = uri({local}) }}; mavenCentral() }}
}}
rootProject.name = "editor-testing-consumer"
''')
    lines = []
    for dep in dependencies:
        coordinate = ':'.join((dep['group'], dep['name'], dep['version']))
        lines.append(f'  implementation({kotlin_literal(coordinate)})')
    # Avoid interpolating project metadata into executable Gradle syntax.
    plugin_line = (f'  id({kotlin_literal(plugin["id"])}) version {kotlin_literal(plugin["version"])}\n'
                   if plugin is not None else '')
    content = (f'plugins {{\n  kotlin("jvm") version {kotlin_literal(model["kotlinVersion"])}\n'
               + plugin_line + '}\n' + 'dependencies {\n' + '\n'.join(lines) + '\n}\n')
    (root / 'build.gradle.kts').write_text(content)
    shutil.copytree(wrapper_root / 'gradle/wrapper', root / 'gradle/wrapper')
    for name in ('gradlew', 'gradlew.bat'):
        shutil.copy2(wrapper_root / name, root / name)
    for name, source in sources.items():
        target = root / 'src/main/kotlin' / name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(source)
    return root
