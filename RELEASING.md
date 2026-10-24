# Releasing KPaths

KPaths uses ordinary release versions. `VERSION_NAME` in `gradle.properties` is the single source
of truth for Gradle coordinates and must agree with the release tag, for example `v0.1.0`.

## Local verification

Run the complete checks before tagging a release:

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew check docsBuild publishToMavenLocal
```

The Android consumer should then resolve that local publication and pass its unit tests and release
assembly.

## Maven Central prerequisites

Maven Central requires immutable coordinates, complete POM metadata, source and API-documentation
artifacts, and signatures. Before enabling publication, the maintainers must:

1. Register and verify the `dev.kpaths` namespace in the [Central Publisher Portal](https://central.sonatype.com/).
2. Create a Portal user token and an ASCII-armored signing key; store them as repository secrets.

The release workflow will use those secrets only on a protected tag or manual release dispatch. Do
not store Portal tokens or signing keys in this repository or `gradle.properties`.

## Documentation site

The `Deploy documentation` workflow builds `build/docs/site` and publishes it through GitHub Pages.
An administrator must select **GitHub Actions** as the repository's Pages source once in the Pages
settings. It deploys only from `main`.
