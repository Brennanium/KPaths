plugins {
  alias(libs.plugins.kotlin.multiplatform) apply false
  alias(libs.plugins.ksp) apply false
  alias(libs.plugins.dokka)
  alias(libs.plugins.vanniktech.maven.publish) apply false
}

group = "io.github.brennanium"
version = providers.gradleProperty("VERSION_NAME").get()

allprojects {
  group = rootProject.group
  version = rootProject.version
}

val docsSiteDirectory = layout.buildDirectory.dir("docs/site")
val documentationVersion = providers.gradleProperty("documentationVersion").orElse("main")
val localDocsPython = rootDir.resolve(".venv-docs/bin/python")
val docsPython = providers.gradleProperty("docsPython").orElse(
  providers.provider {
    if (localDocsPython.isFile) localDocsPython.absolutePath else "python3"
  },
)

dokka {
  dokkaPublications.html {
    moduleName.set("KPaths")
    moduleVersion.set(documentationVersion)
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

tasks.register("verifyMavenPublications") {
  group = "verification"
  description = "Validates Maven Central POM metadata for every published KPaths artifact."
  dependsOn(
    ":kpaths-annotations:checkPomFileForKotlinMultiplatformPublication",
    ":kpaths-annotations:checkPomFileForJvmPublication",
    ":kpaths-annotations:checkPomFileForIosArm64Publication",
    ":kpaths-annotations:checkPomFileForIosSimulatorArm64Publication",
    ":kpaths-core:checkPomFileForKotlinMultiplatformPublication",
    ":kpaths-core:checkPomFileForJvmPublication",
    ":kpaths-core:checkPomFileForIosArm64Publication",
    ":kpaths-core:checkPomFileForIosSimulatorArm64Publication",
    ":kpaths-reflection-jvm:checkPomFileForKotlinMultiplatformPublication",
    ":kpaths-reflection-jvm:checkPomFileForJvmPublication",
    ":kpaths-ksp:checkPomFileForKotlinMultiplatformPublication",
    ":kpaths-ksp:checkPomFileForJvmPublication",
  )
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
