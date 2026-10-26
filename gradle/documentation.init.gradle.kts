// Builds documentation for release tags that predate per-module Dokka setup.
// Applying an already-applied plugin is a no-op, so the same init script is safe
// for current sources and future releases.
gradle.beforeProject {
  if (path in setOf(":kpaths-annotations", ":kpaths-core", ":kpaths-reflection-jvm")) {
    pluginManager.apply("org.jetbrains.dokka")
  }
}
