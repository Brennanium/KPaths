import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(libs.plugins.kotlin.multiplatform)
  alias(libs.plugins.vanniktech.maven.publish)
}

mavenPublishing {
  publishToMavenCentral()
  if (providers.gradleProperty("signingInMemoryKey").isPresent) {
    signAllPublications()
  }
  pom {
    name.set("KPaths KSP")
    description.set("KSP processor for KPaths generated catalogs.")
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

  sourceSets {
    jvmMain {
      kotlin.srcDir("src/main/kotlin")
      resources.srcDir("src/main/resources")
      dependencies {
        implementation(project(":kpaths-annotations"))
        implementation(project(":kpaths-ksp-codegen"))
        implementation(libs.ksp.api)
      }
    }
    jvmTest {
      kotlin.srcDir("src/test/kotlin")
      kotlin.srcDir("src/knapshotsTest/kotlin")
      dependencies { implementation("dev.knapshots:knapshots-ksp:0.1.0-SNAPSHOT") }
      dependencies {
        implementation(kotlin("test"))
        implementation(libs.junit.jupiter)
        runtimeOnly("org.junit.platform:junit-platform-launcher")
      }
    }
  }
}

tasks.named<Test>("jvmTest") {
  dependsOn(
    ":kpaths-annotations:jvmJar",
    ":kpaths-codegen-model:jvmJar",
    ":kpaths-core:jvmJar",
    ":kpaths-ksp-codegen:jvmJar",
    ":kpaths-ksp:jvmJar",
  )
  systemProperty("kpaths.projectDir", rootProject.projectDir.absolutePath)
  run {
    fun artifact(module: String) = rootProject.file("$module/build/libs/$module-jvm-${project.version}.jar")
    systemProperty("knapshots.ksp.version", libs.versions.ksp.get())
    systemProperty("knapshots.ksp.kotlinVersion", libs.versions.kotlin.get())
    systemProperty("knapshots.ksp.record", providers.gradleProperty("knapshots.ksp.record").orElse("none").get())
    systemProperty("knapshots.ksp.processorClasspath", files(artifact("kpaths-ksp"), configurations.getByName("jvmRuntimeClasspath")).asPath)
    systemProperty("knapshots.ksp.classpath", files(artifact("kpaths-core"), artifact("kpaths-annotations")).asPath)
  }
  useJUnitPlatform()
}
