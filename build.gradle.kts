plugins {
  alias(libs.plugins.kotlin.jvm) apply false
  alias(libs.plugins.ksp) apply false
  alias(libs.plugins.dokka)
}

group = "dev.kpaths"
version = "0.1.0-SNAPSHOT"

dokka {
  dokkaPublications.html {
    moduleName.set("KPaths")
    moduleVersion.set(version.toString())
    outputDirectory.set(layout.buildDirectory.dir("docs/api"))
    includes.from("docs/index.md")
    suppressInheritedMembers.set(true)
    suppressObviousFunctions.set(true)
  }
}

dependencies {
  dokka(project(":kpaths-annotations"))
  dokka(project(":kpaths-core"))
}
