plugins {
  alias(libs.plugins.kotlin.multiplatform) apply false
  alias(libs.plugins.ksp) apply false
  alias(libs.plugins.dokka)
}

group = "dev.kpaths"
version = "0.1.0-SNAPSHOT"

allprojects {
  group = rootProject.group
  version = rootProject.version
}

val docsSiteDirectory = layout.buildDirectory.dir("docs/site")
val localDocsPython = rootDir.resolve(".venv-docs/bin/python")
val docsPython = providers.gradleProperty("docsPython").orElse(
  providers.provider {
    if (localDocsPython.isFile) localDocsPython.absolutePath else "python3"
  },
)

dokka {
  dokkaPublications.html {
    moduleName.set("KPaths")
    moduleVersion.set(version.toString())
    outputDirectory.set(layout.buildDirectory.dir("docs/api"))
    includes.from("dokka-module.md")
    suppressInheritedMembers.set(true)
    suppressObviousFunctions.set(true)
  }
}

dependencies {
  dokka(project(":kpaths-annotations"))
  dokka(project(":kpaths-core"))
  dokka(project(":kpaths-reflection-jvm"))
}

val mkdocsBuild = tasks.register<Exec>("mkdocsBuild") {
  group = "documentation"
  description = "Renders the KPaths guides with Material for MkDocs."
  dependsOn(tasks.named("dokkaGenerate"))
  workingDir = rootDir
  inputs.file("mkdocs.yml")
  inputs.dir("docs")
  outputs.dir(docsSiteDirectory)
  commandLine(
    docsPython.get(),
    "-m",
    "mkdocs",
    "build",
    "--strict",
    "--config-file",
    "mkdocs.yml",
    "--site-dir",
    docsSiteDirectory.get().asFile.absolutePath,
  )
}

tasks.register<Sync>("docsBuild") {
  group = "documentation"
  description = "Builds the complete KPaths documentation site, including the Dokka API reference."
  dependsOn(mkdocsBuild)
  from(layout.buildDirectory.dir("docs/api"))
  into(docsSiteDirectory.map { it.dir("api") })
}

tasks.register<Exec>("docsServe") {
  group = "documentation"
  description = "Serves the complete generated documentation site at http://localhost:8000."
  dependsOn("docsBuild")
  commandLine(
    docsPython.get(),
    "-m",
    "http.server",
    "8000",
    "--directory",
    docsSiteDirectory.get().asFile.absolutePath,
  )
}
