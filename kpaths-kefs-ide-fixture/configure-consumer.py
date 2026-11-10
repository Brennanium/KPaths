#!/usr/bin/env python3
"""Configure KEFS only in this fixture's isolated consumer project."""
import argparse
from pathlib import Path
from xml.etree import ElementTree as ET

parser = argparse.ArgumentParser()
parser.add_argument("mode", choices=("enabled", "disabled"))
args = parser.parse_args()
root = Path(__file__).resolve().parent
project = ET.Element("project", version="4")
component = ET.SubElement(project, "component", name="KotlinPluginsSettings")
settings = {
    "repositories": ("KPaths local", f"{root / 'build/kefs-repository'};PATH"),
    "plugins": ("KPaths", f"EXACT;{str(args.mode == 'enabled').lower()};false;io.github.brennanium:kpaths-compiler-plugin-jvm"),
    "pluginsRepos": ("KPaths", "KPaths local"),
}
for name, (key, value) in settings.items():
    mapping = ET.SubElement(ET.SubElement(component, "option", name=name), "map")
    ET.SubElement(mapping, "entry", key=key, value=value)
output = root / "consumer/.idea/kotlin-plugins.xml"
output.parent.mkdir(parents=True, exist_ok=True)
ET.indent(project, space="  ")
ET.ElementTree(project).write(output, encoding="unicode", xml_declaration=True)
print(f"Wrote fixture-only KEFS configuration: {output}")
