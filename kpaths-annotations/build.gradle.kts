import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.dokka)
}

kotlin {
  compilerOptions.jvmTarget.set(JvmTarget.JVM_11)
}

tasks.withType<JavaCompile>().configureEach {
  options.release.set(11)
}

dokka {
  dokkaSourceSets.named("main") {
    includes.from("src/main/kotlin/dev/kpaths/annotations/package.md")
  }
}
