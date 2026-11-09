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
    ":kpaths-jvm:checkPomFileForKotlinMultiplatformPublication",
    ":kpaths-jvm:checkPomFileForJvmPublication",
    ":kpaths-native:checkPomFileForKotlinMultiplatformPublication",
    ":kpaths-native:checkPomFileForIosArm64Publication",
    ":kpaths-native:checkPomFileForIosSimulatorArm64Publication",
    ":kpaths-reflection-jvm:checkPomFileForKotlinMultiplatformPublication",
    ":kpaths-reflection-jvm:checkPomFileForJvmPublication",
    ":kpaths-codegen-model:checkPomFileForKotlinMultiplatformPublication",
    ":kpaths-codegen-model:checkPomFileForJvmPublication",
    ":kpaths-ksp-codegen:checkPomFileForKotlinMultiplatformPublication",
    ":kpaths-ksp-codegen:checkPomFileForJvmPublication",
    ":kpaths-ksp:checkPomFileForKotlinMultiplatformPublication",
    ":kpaths-ksp:checkPomFileForJvmPublication",
  )
}

/**
 * Fast, repeatable verification for the experimental K2 compiler-plugin implementation.
 *
 * This intentionally covers the plugin's source fixture, runtime tests, and a separate consumer
 * that does not apply the plugin. Run this after editing FIR/IR generation code.
 */
tasks.register("verifyCompilerPluginJvm") {
  group = "verification"
  description = "Verifies the K2 compiler plugin on JVM, including binary consumer metadata."
  dependsOn(
    ":kpaths-codegen-model:jvmTest",
    ":kpaths-compiler-plugin-fixture:jvmTest",
    ":kpaths-compiler-plugin-consumer:jvmTest",
  )
}

/**
 * Full cross-backend verification for generated `Paths` declarations.
 *
 * Kotlin/Native is compiled here rather than run: the fixture's assertions are JVM tests, while
 * the Native fixture proves that identical FIR/IR generation reaches a second backend.
 */
tasks.register("verifyCompilerPlugin") {
  group = "verification"
  description = "Verifies the K2 compiler plugin on JVM and Kotlin/Native."
  dependsOn(
    "verifyCompilerPluginJvm",
    ":kpaths-compiler-plugin-fixture:compileKotlinIosSimulatorArm64",
  )
}

/** Runs downstream compile contracts, including disabled acceptance specs for planned features. */
tasks.register("verifyCompilerPluginContracts") {
  group = "verification"
  description = "Runs downstream compiler-plugin API acceptance contracts."
  dependsOn(":kpaths-compiler-plugin-fixture:compilerPluginContractTest")
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
