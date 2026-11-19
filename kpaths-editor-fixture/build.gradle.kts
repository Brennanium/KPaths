plugins {
  alias(libs.plugins.kotlin.jvm)
  id("dev.knapshots.compatibility")
}

dependencies {
  testImplementation("dev.knapshots:knapshots-editor-junit:0.1.0-SNAPSHOT")
  testImplementation("dev.knapshots:knapshots-ide-runner:0.1.0-SNAPSHOT")
  testImplementation(libs.junit.jupiter)
  testRuntimeOnly("org.junit.platform:junit-platform-launcher")
  testRuntimeOnly(project(":kpaths-core"))
  testRuntimeOnly(project(":kpaths-annotations"))
}

knapshots {
  gradlePlugin {
    id.set("dev.kpaths.gradle-plugin")
    version.set(project.version.toString())
  }
  repository.set(rootProject.layout.buildDirectory.dir("consumer-verification/repository"))
  idePlugin {
    kefs {
      idePlugin.set("io.github.brennanium:kpaths-compiler-plugin-jvm")
      repository.set(rootProject.layout.projectDirectory.dir("kpaths-kefs-ide-fixture/build/kefs-repository"))
      enabled.set(providers.gradleProperty("kpaths.editorMode").orElse("enabled").map { it != "disabled" })
    }
  }
}

tasks.test {
  outputs.upToDateWhen { !providers.gradleProperty("kpaths.editorIntegration").isPresent }
  useJUnitPlatform {
    if (!providers.gradleProperty("kpaths.editorIntegration").isPresent) excludeTags("ide-integration")
  }
  systemProperty("kpaths.editorMode", providers.gradleProperty("kpaths.editorMode").orElse("enabled").get())
  providers.gradleProperty("kpaths.editorReportDirectory").orNull?.let {
    systemProperty("knapshots.editor.reportDirectory", it)
  }
}
