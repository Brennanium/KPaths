import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
  kotlin("jvm") version "2.4.21"
  id("org.jetbrains.intellij.platform") version "2.19.0"
  `maven-publish`
}

repositories {
  mavenCentral()
  intellijPlatform { defaultRepositories() }
}

dependencies {
  implementation("dev.kpaths.testing:editor-testing:0.0.0-dev")
  intellijPlatform {
    local(providers.gradleProperty("kpaths.idePath").orElse("/Applications/Android Studio.app"))
    bundledPlugin("org.jetbrains.kotlin")
    plugin("com.github.mr3zee.kotlinPlugins:${providers.gradleProperty("kpaths.kefsVersion").orElse("0.5.0-262").get()}")
    testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
  }
  testImplementation(kotlin("test"))
  testImplementation("junit:junit:4.13.2")
}

val ideKotlinStdlib = configurations.create("ideKotlinStdlib") { isTransitive = false }
dependencies { ideKotlinStdlib("org.jetbrains.kotlin:kotlin-stdlib:2.4.21") }

kotlin { jvmToolchain(25) }
configurations.runtimeClasspath { exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib") }

// Bridge the FirResolvedQualifier API change in this IDE compiler. Keep the build compiler
// source unchanged; the IDE-specific copy is compiled and tested separately.
val prepareIdeCompilerSources = tasks.register<Sync>("prepareIdeCompilerSources") {
  doFirst {
    val source = rootProject.file("../kpaths-compiler-plugin/src/main/kotlin/dev/kpaths/compiler/KPathsCompilerGenerationApi.kt").readText()
    check(source.split("argument.qualifierSymbol?.classId").size == 2) {
      "The IDE compatibility adaptation no longer matches; review the FIR API for this IDE."
    }
  }
  from("../kpaths-compiler-plugin/src/main/kotlin") {
    filter { line: String -> line.replace("argument.qualifierSymbol?.classId", "argument.symbol?.classId") }
  }
  into(layout.buildDirectory.dir("generated/ide-compiler-sources"))
}

// Compile against the compiler bundled in the selected IDE, not the build compiler.
val ideCompilerPlugin = sourceSets.create("ideCompilerPlugin") {
  kotlin.srcDir(prepareIdeCompilerSources)
  kotlin.srcDir("../kpaths-codegen-model/src/main/kotlin")
  resources.srcDir("../kpaths-compiler-plugin/src/main/resources")
  compileClasspath += sourceSets.main.get().compileClasspath
}
tasks.named<KotlinCompile>("compileIdeCompilerPluginKotlin") {
  compilerOptions.freeCompilerArgs.add("-opt-in=org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi")
  providers.gradleProperty("kpaths.idePluginJvmTarget").orNull?.let {
    compilerOptions.jvmTarget.set(JvmTarget.fromTarget(it))
  }
}
tasks.named<JavaCompile>("compileIdeCompilerPluginJava") {
  providers.gradleProperty("kpaths.idePluginJvmTarget").orNull?.let { options.release.set(it.toInt()) }
}
val ideCompilerPluginJar = tasks.register<Jar>("ideCompilerPluginJar") {
  archiveFileName = "kpaths-ide-compiler-plugin.jar"
  destinationDirectory = layout.buildDirectory.dir("ide-compiler-plugin")
  from(ideCompilerPlugin.output)
  exclude("META-INF/plugin.xml")
}
val kpathsVersion = Properties().apply {
  rootProject.file("../gradle.properties").inputStream().use(::load)
}.getProperty("VERSION_NAME") ?: error("VERSION_NAME is missing from the root gradle.properties")

// This experimental publication is built against the locally selected IDE. The version must
// match the Kotlin compiler reported by KEFS for that IDE; this is not a release publication.
val ideKotlinVersion = providers.gradleProperty("kpaths.ideKotlinVersion")
publishing {
  publications {
    create<MavenPublication>("ideCompilerPlugin") {
      groupId = "io.github.brennanium"
      artifactId = "kpaths-compiler-plugin-jvm"
      version = "${ideKotlinVersion.orElse("UNSPECIFIED").get()}-$kpathsVersion"
      artifact(ideCompilerPluginJar)
    }
  }
  repositories {
    maven {
      name = "kefsLocal"
      url = uri(layout.buildDirectory.dir("kefs-repository"))
    }
  }
}
tasks.withType<PublishToMavenRepository>().configureEach {
  doFirst {
    require(ideKotlinVersion.isPresent) {
      "Set -Pkpaths.ideKotlinVersion to the version from 'KEFS: Copy Kotlin IDE Version'."
    }
  }
}

tasks.test {
  if (!providers.gradleProperty("kpaths.rangeTaggedCandidateJar").isPresent) {
    exclude("**/KefsRangeTaggedJarTest.class")
  }
  if (!providers.gradleProperty("kpaths.remoteRepositoryUrl").isPresent) {
    exclude("**/KefsRemoteRepositoryTest.class")
  } else {
    // Remote availability is part of this opt-in experiment; do not reuse a Gradle test result.
    outputs.upToDateWhen { false }
  }
  listOf("remoteRepositoryUrl", "remoteTestVersion", "remoteExpectedJar").forEach { name ->
    providers.gradleProperty("kpaths.$name").orNull?.let { systemProperty("kpaths.$name", it) }
  }
  forkEvery = 1
  dependsOn(ideCompilerPluginJar)
  inputs.file(ideCompilerPluginJar.flatMap { it.archiveFile })
  inputs.files(ideKotlinStdlib)
  val buildPluginJar = providers.gradleProperty("kpaths.buildCompilerPluginJar")
    .orElse(rootProject.layout.projectDirectory.file("../kpaths-compiler-plugin/build/libs/kpaths-compiler-plugin-jvm-$kpathsVersion.jar").asFile.absolutePath)
  inputs.file(buildPluginJar)
  systemProperty("kpaths.buildCompilerPluginJar", buildPluginJar.get())
  val coreJar = rootProject.file("../kpaths-core/build/libs/kpaths-core-jvm-$kpathsVersion.jar")
  val annotationsJar = rootProject.file("../kpaths-annotations/build/libs/kpaths-annotations-jvm-$kpathsVersion.jar")
  inputs.files(coreJar, annotationsJar)
  systemProperty("kpaths.coreJar", coreJar.absolutePath)
  systemProperty("kpaths.annotationsJar", annotationsJar.absolutePath)
  systemProperty("kpaths.ideCompilerPluginJar", ideCompilerPluginJar.flatMap { it.archiveFile }.get().asFile.absolutePath)
  providers.gradleProperty("kpaths.ideCandidateJar").orNull?.let {
    inputs.file(file(it))
    systemProperty("kpaths.ideCandidateJar", file(it).absolutePath)
  }
  providers.gradleProperty("kpaths.rangeTaggedCandidateJar").orNull?.let {
    inputs.file(file(it))
    systemProperty("kpaths.rangeTaggedCandidateJar", file(it).absolutePath)
  }
  providers.gradleProperty("kpaths.untaggedCandidateJar").orNull?.let {
    inputs.file(file(it))
    systemProperty("kpaths.untaggedCandidateJar", file(it).absolutePath)
  }
  systemProperty("kpaths.kefsScenario", providers.gradleProperty("kpaths.kefsScenario").orElse("full").get())
  systemProperty("kpaths.ideKotlinStdlib", ideKotlinStdlib.singleFile.absolutePath)
  ideKotlinVersion.orNull?.let { systemProperty("kpaths.expectedIdeKotlinVersion", it) }
}

// A local IDE artifact must be tested against the selected IDE before it is published with
// that IDE's compiler version. The test asks KEFS for the version rather than trusting a label.
tasks.named("publishIdeCompilerPluginPublicationToKefsLocalRepository") {
  dependsOn(tasks.test)
  doFirst {
    require(!providers.gradleProperty("kpaths.ideCandidateJar").isPresent &&
      !providers.gradleProperty("kpaths.rangeTaggedCandidateJar").isPresent &&
      !providers.gradleProperty("kpaths.untaggedCandidateJar").isPresent &&
      providers.gradleProperty("kpaths.kefsScenario").orElse("full").get() == "full") {
      "Experimental candidate or partial-scenario tests cannot certify a published IDE artifact."
    }
  }
}

intellijPlatform { buildSearchableOptions = false; instrumentCode = false }

val ideContributor = sourceSets.create("ideContributor") {
  kotlin.srcDir("../kpaths-compiler-plugin-contributor/src/main/kotlin")
  resources.srcDir("../kpaths-compiler-plugin-contributor/src/main/resources")
  compileClasspath += sourceSets.main.get().compileClasspath + ideCompilerPlugin.output
}
tasks.named<KotlinCompile>("compileIdeContributorKotlin") {
  compilerOptions.freeCompilerArgs.add("-opt-in=org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi")
}
val ideContributorJar = tasks.register<Jar>("ideContributorJar") {
  archiveFileName = "contributor-ide.jar"
  destinationDirectory = layout.buildDirectory.dir("ide-compiler-plugin")
  from(ideContributor.output)
  exclude("META-INF/plugin.xml")
}
tasks.test {
  dependsOn(ideContributorJar)
  inputs.file(ideContributorJar.flatMap { it.archiveFile })
  systemProperty("kpaths.ideContributorJar", ideContributorJar.flatMap { it.archiveFile }.get().asFile.absolutePath)
  systemProperty("kpaths.buildContributorJar", rootProject.file("../kpaths-compiler-plugin-contributor/build/libs/kpaths-compiler-plugin-contributor-jvm-$kpathsVersion.jar").absolutePath)
}

// Only the sandbox launched for this fixture enables the consumer analysis probe.
tasks.runIde {
  val report = layout.buildDirectory.file("consumer-probe.txt")
  val mode = providers.gradleProperty("kpaths.consumerExpected").orElse("enabled")
  val attempt = providers.gradleProperty("kpaths.consumerAttempt").orElse("1")
  val runId = providers.gradleProperty("kpaths.consumerRunId").orElse("manual")
  val consumerProject = providers.gradleProperty("kpaths.consumerProject")
    .orElse(rootProject.file("consumer").absolutePath)
  val probeDirectory = layout.buildDirectory.dir("ide-probe/${runId.get()}/${mode.get()}-${attempt.get()}")
  val probePreferences = probeDirectory.map { it.dir("preferences") }
  doFirst {
    report.get().asFile.delete()
    // Hermetic first-run settings, following Metro's Android Studio integration fixture. The
    // synthetic identity and disabled publishing keep this isolated from a developer's settings.
    val preferences = probePreferences.get().asFile
    val common = preferences.resolve("common/consentOptions")
    val android = preferences.resolve("android")
    val androidSettings = android.resolve(".android")
    // The Gradle plugin omits sandbox JVM arguments for directories that don't exist.
    probeDirectory.get().dir("system").asFile.mkdirs()
    probeDirectory.get().dir("log").asFile.mkdirs()
    common.mkdirs()
    androidSettings.mkdirs()
    common.resolve("accepted").writeText("rsch.send.usage.stat:1.0:0:${System.currentTimeMillis()}")
    val analytics = """{"userId":"00000000-0000-0000-0000-000000000000","hasOptedIn":true,"debugDisablePublishing":true,"saltSkew":-1,"lastOptinPromptVersion":"9999.9999"}"""
    // Different Android Studio builds resolve ANDROID_PREFS_ROOT at either level.
    android.resolve("analytics.settings").writeText(analytics)
    androidSettings.resolve("analytics.settings").writeText(analytics)
  }
  systemProperty("kpaths.consumerProbe", report.get().asFile.absolutePath)
  systemProperty("kpaths.consumerProject", consumerProject.get())
  systemProperty("kpaths.consumerSandbox", probeDirectory.get().asFile.absolutePath)
  systemProperty("kpaths.consumerExpected", mode.get())
  // RunIde's sandbox argument provider owns these paths; ordinary systemProperty calls
  // are overwritten by that provider, so set its directory properties directly.
  sandboxConfigDirectory.set(probeDirectory.map { it.dir("config") })
  sandboxSystemDirectory.set(probeDirectory.map { it.dir("system") })
  sandboxLogDirectory.set(probeDirectory.map { it.dir("log") })
  systemProperty("idea.common.data.path", probePreferences.get().dir("common").asFile.absolutePath)
  systemProperty("ANDROID_PREFS_ROOT", probePreferences.get().dir("android").asFile.absolutePath)
  systemProperty("jb.consents.confirmation.enabled", "false")
  systemProperty("idea.initially.ask.config", "never")
  systemProperty("disable.android.first.run", "true")
  systemProperty("jb.privacy.policy.text", "<!--999.999-->")
  systemProperty("disable.android.analytics.consent.dialog", "true")
  systemProperty("google.login.enable.startup.sign.in.dialog", "false")
  systemProperty("ide.show.tips.on.startup.default.value", "false")
  systemProperty("git.showDialogsOnUnversionedFiles", "false")
}
