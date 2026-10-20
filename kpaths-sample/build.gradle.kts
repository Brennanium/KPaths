import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.ksp)
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
  ksp(project(":kpaths-ksp"))

  testImplementation(kotlin("test"))
  testImplementation(libs.junit.jupiter)
  testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
  useJUnitPlatform()
}
