plugins {
  kotlin("multiplatform")
  id("dev.kpaths.compiler")
}
val kpathsVersion = rootProject.file("../gradle.properties").readLines()
  .single { it.startsWith("VERSION_NAME=") }.substringAfter('=')
val expectedKotlin = providers.gradleProperty("kpaths.testKotlinVersion").get()
val wrapper = Class.forName(
  "org.jetbrains.kotlin.gradle.plugin.KotlinPluginWrapperKt", true,
  extensions.getByName("kotlin").javaClass.classLoader,
)
val actualKotlin = wrapper.getMethod("getKotlinPluginVersion", org.gradle.api.Project::class.java)
  .invoke(null, project) as String
check(actualKotlin == expectedKotlin) { "Expected Kotlin $expectedKotlin, resolved $actualKotlin" }

kotlin {
  iosSimulatorArm64()
  sourceSets.commonTest.dependencies {
    implementation(kotlin("test"))
    implementation("io.github.brennanium:kpaths-core:$kpathsVersion")
    implementation("io.github.brennanium:kpaths-annotations:$kpathsVersion")
  }
}
