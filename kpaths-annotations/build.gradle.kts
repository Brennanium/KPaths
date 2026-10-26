import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(libs.plugins.kotlin.multiplatform)
  alias(libs.plugins.dokka)
  alias(libs.plugins.vanniktech.maven.publish)
}

mavenPublishing {
  publishToMavenCentral()
  if (providers.gradleProperty("signingInMemoryKey").isPresent) {
    signAllPublications()
  }
  pom {
    name.set("KPaths annotations")
    description.set("Annotations for KPaths generated catalogs.")
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
kotlin {
  jvm {
    compilerOptions.jvmTarget.set(JvmTarget.JVM_11)
  }
  iosArm64()
  iosSimulatorArm64()

  sourceSets {
    commonMain {
      kotlin.srcDir("src/main/kotlin")
    }
  }
}
