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
  implementation(kotlin("reflect"))

  testImplementation(kotlin("test"))
  testImplementation(libs.junit.jupiter)
  testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
  useJUnitPlatform()
}
