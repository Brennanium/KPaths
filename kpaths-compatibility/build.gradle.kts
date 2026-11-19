plugins {
  id("dev.knapshots.compatibility")
}

val knapshotsCheckout = providers.gradleProperty("knapshots.checkout")
  .map { rootProject.file(it).absolutePath }
  .orElse(providers.provider {
    val checkout = rootProject.file(".knapshots")
    if (checkout.resolve("settings.gradle.kts").isFile) checkout.absolutePath
    else rootProject.file("../Knapshots").absolutePath
  })

knapshotsCompatibility {
  compilerDriver.set(rootProject.layout.projectDirectory.file("scripts/knapshots-compiler-driver.sh"))
  editorDriver.set(rootProject.layout.projectDirectory.file("scripts/knapshots-editor-driver.sh"))
  useKefs.set(true)
  useXvfb.set(providers.environmentVariable("KPATHS_USE_XVFB").map { it.toBooleanStrict() }.orElse(false))
  driverEnvironment.put("KNAPSHOTS_CHECKOUT", knapshotsCheckout)
  driverEnvironment.put("ORG_GRADLE_PROJECT_knapshots.checkout", knapshotsCheckout)
}
