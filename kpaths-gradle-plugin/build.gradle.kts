import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(libs.plugins.kotlin.jvm)
  `java-gradle-plugin`
  `maven-publish`
}

kotlin {
  compilerOptions.jvmTarget.set(JvmTarget.JVM_11)
}

java {
  sourceCompatibility = JavaVersion.VERSION_11
  targetCompatibility = JavaVersion.VERSION_11
}

dependencies {
  // The consumer supplies its Kotlin Gradle plugin. Publishing this as a runtime dependency
  // would silently upgrade a consumer's Kotlin version to the version used to build KPaths.
  compileOnly("org.jetbrains.kotlin:kotlin-gradle-plugin-api:${libs.versions.kotlin.get()}")
  testImplementation("org.jetbrains.kotlin:kotlin-gradle-plugin-api:${libs.versions.kotlin.get()}")
  testImplementation(kotlin("test"))
  testImplementation(gradleTestKit())
  testImplementation(libs.junit.jupiter)
  testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

gradlePlugin {
  plugins {
    create("kpathsCompiler") {
      id = "dev.kpaths.compiler"
      implementationClass = "dev.kpaths.gradle.KPathsGradlePlugin"
      displayName = "KPaths compiler plugin"
      description = "Generates nested KPaths catalogs with the Kotlin compiler."
    }
  }
}

val generateCoordinates = tasks.register("generateCoordinates") {
  val output = layout.buildDirectory.dir("generated/kpaths-coordinates")
  val artifactGroup = project.group.toString()
  val artifactVersion = project.version.toString()
  val supportedVersions = rootProject.file("gradle/kpaths-supported-kotlin-versions.txt")
    .readLines().map(String::trim).filter { it.isNotEmpty() && !it.startsWith("#") }
  require(supportedVersions.isNotEmpty() && supportedVersions.distinct().size == supportedVersions.size)
  inputs.property("artifactGroup", artifactGroup)
  inputs.property("artifactVersion", artifactVersion)
  inputs.property("supportedKotlinVersions", supportedVersions)
  outputs.dir(output)
  doLast {
    val file = output.get().file("dev/kpaths/gradle/KPathsCoordinates.kt").asFile
    file.parentFile.mkdirs()
    file.writeText("""
      package dev.kpaths.gradle
      internal const val KPATHS_GROUP = "$artifactGroup"
      internal const val KPATHS_VERSION = "$artifactVersion"
      internal val KPATHS_SUPPORTED_KOTLIN_VERSIONS = setOf(${supportedVersions.joinToString { "\"$it\"" }})
    """.trimIndent() + "\n")
  }
}

kotlin.sourceSets.named("main") { kotlin.srcDir(generateCoordinates) }

tasks.test {
  useJUnitPlatform()
  dependsOn("jar")
  dependsOn(":kpaths-compiler-plugin:jvmJar", ":kpaths-core:jvmJar", ":kpaths-annotations:jvmJar")
  systemProperty("kpaths.projectDir", rootProject.projectDir.absolutePath)
  systemProperty("kpaths.version", project.version.toString())
  systemProperty("kpaths.kotlinVersion", libs.versions.kotlin.get())
  systemProperty("kpaths.testKotlinVersion", providers.gradleProperty("kpaths.testKotlinVersion").orElse(libs.versions.kotlin).get())
}
