import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.gradle.jvm.tasks.Jar

plugins {
  alias(libs.plugins.kotlin.multiplatform)
  `maven-publish`
}

/**
 * A compiler plugin is loaded from the single JAR passed to `-Xplugin`, rather than from an
 * application's normal Gradle runtime classpath. Keep the small, dependency-free policy model
 * inside this experimental plugin JAR so the FIR extension can share it with KSP.
 */
val embeddedCodegenModel = configurations.create("embeddedCodegenModel")

/**
 * Research implementation of a K2 compiler-plugin version of `@Pathable`.
 *
 * Publishing is configured so a local Maven repository can exercise the consumer integration.
 * The plugin remains experimental and is not included in the public release workflow yet.
 */
kotlin {
  jvm {
    compilerOptions {
      jvmTarget.set(JvmTarget.JVM_11)
      freeCompilerArgs.add("-opt-in=org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi")
    }
  }

  sourceSets {
    jvmMain {
      kotlin.srcDir("src/main/kotlin")
      resources.srcDir("src/main/resources")
      dependencies {
        compileOnly("org.jetbrains.kotlin:kotlin-compiler-embeddable:${libs.versions.kotlin.get()}")
        compileOnly(project(":kpaths-codegen-model"))
      }
    }
  }
}

dependencies {
  add(
    embeddedCodegenModel.name,
    project(mapOf(
      "path" to ":kpaths-codegen-model",
      "configuration" to "jvmRuntimeElements",
    )),
  )
}

tasks.named<Jar>("jvmJar") {
  dependsOn(embeddedCodegenModel)
  from(embeddedCodegenModel.map(::zipTree)) {
    exclude("META-INF/**")
  }
}
