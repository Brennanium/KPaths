"""Helpers for isolated IntelliJ-platform integration test environments."""
from pathlib import Path
import xml.etree.ElementTree as ET


def trust_project(config_directory: Path, project_directory: Path) -> None:
    """Trust one project in the supplied disposable IDE configuration directory."""
    config = config_directory / 'options/trusted-paths.xml'
    config.parent.mkdir(parents=True, exist_ok=True)
    application = ET.parse(config).getroot() if config.exists() else ET.Element('application')
    component = application.find("component[@name='Trusted.Paths']")
    if component is None:
        component = ET.SubElement(application, 'component', name='Trusted.Paths')
    option = component.find("option[@name='TRUSTED_PROJECT_PATHS']")
    if option is None:
        option = ET.SubElement(component, 'option', name='TRUSTED_PROJECT_PATHS')
    mapping = option.find('map')
    if mapping is None:
        mapping = ET.SubElement(option, 'map')
    project = str(project_directory.resolve())
    entry = next((item for item in mapping.findall('entry') if item.get('key') == project), None)
    if entry is None:
        entry = ET.SubElement(mapping, 'entry', key=project)
    entry.set('value', 'true')
    ET.indent(application, space='  ')
    ET.ElementTree(application).write(config, encoding='unicode', xml_declaration=True)
