"""Resolve explicit Kotlin versions and dev-family selectors to published artifacts."""
import concurrent.futures
import re
import urllib.request
import xml.etree.ElementTree as ET

CHANNELS = ("dev", "experimental", "bootstrap")
ARTIFACTS = ("kotlin-gradle-plugin", "kotlin-compiler-embeddable")
EXACT = re.compile(r"[0-9]+\.[0-9]+\.[0-9]+(?:-[A-Za-z0-9]+(?:[.-][A-Za-z0-9]+)*)?")
FAMILY = re.compile(r"([0-9]+\.[0-9]+\.[0-9]+)-dev-?\*")


def fetch_versions(channel, artifact):
    url = (f"https://packages.jetbrains.team/maven/p/kt/{channel}/org/jetbrains/kotlin/"
           f"{artifact}/maven-metadata.xml")
    try:
        with urllib.request.urlopen(url, timeout=30) as response:
            root = ET.parse(response).getroot()
    except (OSError, ET.ParseError) as error:
        raise ValueError(f"Cannot read Kotlin metadata from {url}: {error}") from error
    return {node.text for node in root.findall("./versioning/versions/version") if node.text}


def resolve(selectors, fetch=fetch_versions):
    """Return (selector, exact version), preserving order and resolving each family once."""
    families = {match.group(1) for value in selectors if (match := FAMILY.fullmatch(value))}
    for value in selectors:
        if not EXACT.fullmatch(value) and not FAMILY.fullmatch(value):
            raise ValueError(f"Invalid Kotlin version or selector: {value!r}. Use 2.4.0, 2.5.0-dev*, or 2.5.0-dev-1234.")
    available = {}
    if families:
        pairs = [(channel, artifact) for channel in CHANNELS for artifact in ARTIFACTS]
        with concurrent.futures.ThreadPoolExecutor(max_workers=len(pairs)) as pool:
            results = list(pool.map(lambda pair: fetch(*pair), pairs))
        for (_, artifact), versions in zip(pairs, results):
            available.setdefault(artifact, set()).update(versions)
    resolved = []
    for selector in selectors:
        match = FAMILY.fullmatch(selector)
        if match:
            family = match.group(1)
            common = set.intersection(*(available[artifact] for artifact in ARTIFACTS))
            pattern = re.compile(re.escape(family) + r"-dev-([0-9]+)")
            candidates = [(int(found.group(1)), version) for version in common if (found := pattern.fullmatch(version))]
            if not candidates:
                raise ValueError(f"No {family}-dev build has both compiler and Gradle plugin artifacts in the configured JetBrains repositories.")
            resolved.append((selector, max(candidates)[1]))
        else:
            resolved.append((selector, selector))
    return resolved
