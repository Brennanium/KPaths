"""Read project-level configuration for generated imported editor tests."""
from pathlib import Path
try:
    import tomllib
except ModuleNotFoundError:  # Python 3.9 and 3.10
    try:
        import tomli as tomllib
    except ModuleNotFoundError as error:
        raise ModuleNotFoundError(
            'Editor testing on Python 3.9 or 3.10 requires tomli; install the CLI or use Python 3.11+'
        ) from error


def read_config(path: Path) -> dict:
    config = tomllib.loads(path.read_text(encoding='utf-8'))
    if set(config) != {'project', 'ide'}:
        raise ValueError('Editor testing config must contain [project] and [ide] only')
    project, ide = config['project'], config['ide']
    if not isinstance(project, dict) or not isinstance(ide, dict):
        raise ValueError('[project] and [ide] must be TOML tables')
    if set(project) != {'gradle-plugin', 'compiler-plugin', 'runtime'}:
        raise ValueError('[project] must specify gradle-plugin, compiler-plugin, and runtime')
    if set(ide) != {'integration'} or ide['integration'] != 'kefs':
        raise ValueError('[ide] must specify integration = "kefs"')
    paths = [project['gradle-plugin'], project['compiler-plugin']]
    runtime = project['runtime']
    if not isinstance(runtime, list) or not runtime:
        raise ValueError('project.runtime must be a nonempty array of Gradle project paths')
    paths.extend(runtime)
    if any(not isinstance(item, str) or not item.startswith(':') or ',' in item or item == ':' for item in paths):
        raise ValueError('Project roles must be Gradle project paths starting with :')
    if len(runtime) != len(set(runtime)):
        raise ValueError('project.runtime must not contain duplicates')
    return config
