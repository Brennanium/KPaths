plugins {
  alias(libs.plugins.kotlin.multiplatform)
  alias(libs.plugins.vanniktech.maven.publish)
}

kotlin {
  iosArm64()
  iosSimulatorArm64()

  sourceSets {
    commonMain {
      dependencies {
        api(project(":kpaths-core"))
        api(project(":kpaths-annotations"))
      }
    }
  }
}

mavenPublishing {
  publishToMavenCentral()
  if (providers.gradleProperty("signingInMemoryKey").isPresent) {
    signAllPublications()
  }
  pom {
    name.set("KPaths Native")
    description.set("Kotlin/Native convenience module for KPaths core and annotations.")
    inceptionYear.set("2026")
    url.set("https://github.com/Brennanium/KPaths")
    licenses {
      license {
        name.set("The MIT License")
        url.set("https://opensource.org/licenses/MIT")
        distribution.set("repo")
      }
    }
    developers {
      developer {
        id.set("brennanium")
        name.set("Brennanium")
        email.set("63215941+Brennanium@users.noreply.github.com")
        organization.set("Brennanium")
        organizationUrl.set("https://github.com/Brennanium")
        url.set("https://github.com/Brennanium")
      }
    }
    scm {
      url.set("https://github.com/Brennanium/KPaths")
      connection.set("scm:git:git://github.com/Brennanium/KPaths.git")
      developerConnection.set("scm:git:ssh://git@github.com/Brennanium/KPaths.git")
    }
  }
}
