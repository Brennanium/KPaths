plugins {
  kotlin("jvm") version "2.4.21"
}

group = "dev.kpaths.testing"
version = "0.0.0-dev"

dependencies {
  implementation(if (findProject(":snapshot-testing") != null) project(":snapshot-testing") else "dev.kpaths.testing:snapshot-testing:0.0.0-dev")
  testImplementation(kotlin("test"))
  testImplementation("org.junit.jupiter:junit-jupiter:5.13.4")
}

tasks.test { useJUnitPlatform() }
