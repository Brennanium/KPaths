import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(libs.plugins.kotlin.jvm)
}

kotlin {
  compilerOptions.jvmTarget.set(JvmTarget.JVM_11)
}

tasks.withType<JavaCompile>().configureEach {
  options.release.set(11)
}

dependencies {
  implementation(project(":kpaths-annotations"))
  implementation(project(":kpaths-core"))
  implementation(libs.ksp.api)

  testImplementation(gradleTestKit())
  testImplementation(kotlin("test"))
  testImplementation(libs.junit.jupiter)
  testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
  dependsOn(":kpaths-annotations:jar", ":kpaths-core:jar", ":kpaths-ksp:jar")
  systemProperty("kpaths.projectDir", rootProject.projectDir.absolutePath)
  useJUnitPlatform()
}
