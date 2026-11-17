"""Create a disposable Gradle consumer with supplied editor source files."""
from pathlib import Path
import shutil


def prepare_imported_project(template: Path, destination: Path, repository: Path, sources: dict[str, str]) -> Path:
    """Copy the consumer template, replacing Kotlin sources with caller-provided text.

    Paths are relative to src/main/kotlin. The caller owns destination's lifecycle.
    """
    shutil.copytree(template, destination, ignore=shutil.ignore_patterns('.idea', '.gradle', 'build'))
    settings = destination / 'settings.gradle.kts'
    content = settings.read_text()
    expected = '../../build/consumer-verification/repository'
    if content.count(expected) != 2:
        raise ValueError('Consumer template repository configuration changed')
    # Kotlin string escaping; avoid interpreting filesystem characters as interpolation.
    repo = str(repository.resolve()).replace('\\', '\\\\').replace('"', '\\"').replace('$', '\\$')
    settings.write_text(content.replace(expected, repo))
    source_root = destination / 'src/main/kotlin'
    shutil.rmtree(source_root, ignore_errors=True)
    source_root.mkdir(parents=True)
    for name, text in sources.items():
        path = Path(name)
        if path.is_absolute() or not path.parts or any(part in ('..', '.') for part in path.parts):
            raise ValueError(f'Invalid source path: {name}')
        file = source_root / path
        file.parent.mkdir(parents=True, exist_ok=True)
        file.write_text(text)
    return destination
