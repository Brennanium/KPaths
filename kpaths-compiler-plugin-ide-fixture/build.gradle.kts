import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.util.Properties

plugins {
  kotlin("jvm") version "2.4.21"
  id("org.jetbrains.intellij.platform") version "2.19.0"
}

repositories {
  mavenCentral()
  intellijPlatform { defaultRepositories() }
}

dependencies {
  intellijPlatform {
    local(providers.gradleProperty("kpaths.idePath").orElse("/Applications/Android Studio.app"))
    bundledPlugin("org.jetbrains.kotlin")
    testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
  }
  testImplementation(kotlin("test"))
  testImplementation("junit:junit:4.13.2")
}

val ideKotlinStdlib = configurations.create("ideKotlinStdlib") { isTransitive = false }
dependencies { ideKotlinStdlib("org.jetbrains.kotlin:kotlin-stdlib:2.4.21") }

kotlin { jvmToolchain(25) }
configurations.runtimeClasspath { exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib") }

// Bridge the FirResolvedQualifier API change in this IDE compiler. Keep the build compiler
// source unchanged; the IDE-specific copy is compiled and tested separately.
val prepareIdeCompilerSources = tasks.register<Sync>("prepareIdeCompilerSources") {
  doFirst {
    val source = rootProject.file("../kpaths-compiler-plugin/src/main/kotlin/dev/kpaths/compiler/KPathsCompilerGenerationApi.kt").readText()
    check(source.split("argument.qualifierSymbol?.classId").size == 2) {
      "The IDE compatibility adaptation no longer matches; review the FIR API for this IDE."
    }
  }
  from("../kpaths-compiler-plugin/src/main/kotlin") {
    filter { line: String -> line.replace("argument.qualifierSymbol?.classId", "argument.symbol?.classId") }
  }
  into(layout.buildDirectory.dir("generated/ide-compiler-sources"))
}

// Compile against the compiler bundled in the selected IDE, not the build compiler.
val ideCompilerPlugin = sourceSets.create("ideCompilerPlugin") {
  kotlin.srcDir(prepareIdeCompilerSources)
  kotlin.srcDir("../kpaths-codegen-model/src/main/kotlin")
  resources.srcDir("../kpaths-compiler-plugin/src/main/resources")
  compileClasspath += sourceSets.main.get().compileClasspath
}
tasks.named<KotlinCompile>("compileIdeCompilerPluginKotlin") {
  compilerOptions.freeCompilerArgs.add("-opt-in=org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi")
}
val ideCompilerPluginJar = tasks.register<Jar>("ideCompilerPluginJar") {
  archiveFileName = "kpaths-ide-compiler-plugin.jar"
  destinationDirectory = layout.buildDirectory.dir("ide-compiler-plugin")
  from(ideCompilerPlugin.output)
  exclude("META-INF/plugin.xml")
}
val kpathsVersion = Properties().apply {
  rootProject.file("../gradle.properties").inputStream().use(::load)
}.getProperty("VERSION_NAME") ?: error("VERSION_NAME is missing from the root gradle.properties")

tasks.test {
  forkEvery = 1
  dependsOn(ideCompilerPluginJar)
  inputs.file(ideCompilerPluginJar.flatMap { it.archiveFile })
  inputs.files(ideKotlinStdlib)
  val buildPluginJar = providers.gradleProperty("kpaths.buildCompilerPluginJar")
    .orElse(rootProject.layout.projectDirectory.file("../kpaths-compiler-plugin/build/libs/kpaths-compiler-plugin-jvm-$kpathsVersion.jar").asFile.absolutePath)
  inputs.file(buildPluginJar)
  systemProperty("kpaths.buildCompilerPluginJar", buildPluginJar.get())
  val coreJar = rootProject.file("../kpaths-core/build/libs/kpaths-core-jvm-$kpathsVersion.jar")
  val annotationsJar = rootProject.file("../kpaths-annotations/build/libs/kpaths-annotations-jvm-$kpathsVersion.jar")
  inputs.files(coreJar, annotationsJar)
  systemProperty("kpaths.coreJar", coreJar.absolutePath)
  systemProperty("kpaths.annotationsJar", annotationsJar.absolutePath)
  systemProperty("kpaths.ideCompilerPluginJar", ideCompilerPluginJar.flatMap { it.archiveFile }.get().asFile.absolutePath)
  systemProperty("kpaths.ideKotlinStdlib", ideKotlinStdlib.singleFile.absolutePath)
}

intellijPlatform { buildSearchableOptions = false; instrumentCode = false }
