"""Development packaging for the Kotlin Plugin Matrix CLI; not a published release."""
from setuptools import setup
from setuptools.command.build_py import build_py


class MatrixBuildPy(build_py):
    # The package draws from the existing scripts directory while extraction is deferred.
    modules = {
        '__init__', 'matrix_entrypoint', 'matrix_project_root', 'kotlin-plugin-matrix',
        'kotlin_version_resolver', 'run-compiler-matrix', 'verify-compiler-compatibility',
        'verify-android-studio-version', 'verify-kefs-compatibility', 'ide_sandbox', 'imported_ide_project',
        'editor_testing_config', 'editor_project_model', 'prototype_editor_project',
    }

    def find_package_modules(self, package, package_dir):
        return [item for item in super().find_package_modules(package, package_dir)
                if item[1] in self.modules]


setup(
    name='kotlin-plugin-matrix',
    version='0.0.0.dev0',
    description='Run Kotlin compiler plugin compatibility matrices',
    author='Brennanium',
    license='MIT',
    python_requires='>=3.9',
    install_requires=['tomli>=2; python_version < "3.11"'],
    packages=['kotlin_plugin_matrix'],
    package_dir={'kotlin_plugin_matrix': 'scripts'},
    entry_points={'console_scripts': ['kotlin-plugin-matrix=kotlin_plugin_matrix.matrix_entrypoint:main']},
    cmdclass={'build_py': MatrixBuildPy},
)
