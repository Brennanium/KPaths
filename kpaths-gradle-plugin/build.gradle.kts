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
  implementation("org.jetbrains.kotlin:kotlin-gradle-plugin-api:${libs.versions.kotlin.get()}")
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
  val kotlinVersion = libs.versions.kotlin.get()
  inputs.property("artifactGroup", artifactGroup)
  inputs.property("artifactVersion", artifactVersion)
  inputs.property("kotlinVersion", kotlinVersion)
  outputs.dir(output)
  doLast {
    val file = output.get().file("dev/kpaths/gradle/KPathsCoordinates.kt").asFile
    file.parentFile.mkdirs()
    file.writeText("""
      package dev.kpaths.gradle
      internal const val KPATHS_GROUP = "$artifactGroup"
      internal const val KPATHS_VERSION = "$artifactVersion"
      internal const val KPATHS_KOTLIN_VERSION = "$kotlinVersion"
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
}
