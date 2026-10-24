# Releasing KPaths

KPaths uses ordinary release versions. `VERSION_NAME` in `gradle.properties` is the single source
of truth for Gradle coordinates and must agree with the release tag, for example `v0.1.0`.

## Local verification

Run the complete checks before tagging a release:

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew check docsBuild verifyMavenPublications publishToMavenLocal
```

`publishToMavenLocal` does not require a signing key. The release workflow supplies a signing key
through its protected GitHub Actions secrets and signs every publication before upload.

The Android consumer should then resolve that local publication and pass its unit tests and release
assembly.

## Maven Central setup

Maven Central requires immutable coordinates, complete POM metadata, source and API-documentation
artifacts, and signatures. KPaths publishes as `io.github.brennanium:<artifact>:<version>`.

Before the first release:

1. In the [Central Publisher Portal](https://central.sonatype.com/), confirm that
   `io.github.brennanium` is **verified**, not merely registered. GitHub-based verification creates
   a temporary public repository whose name is the Portal-provided verification key; the Portal can
   then verify the namespace and that temporary repository can be deleted.
2. Generate a Portal user token from the Portal's token page. This gives a separate username and
   password for publishing; it is not the GitHub or Portal sign-in password.
3. Create a PGP signing key and upload its public half to a supported public keyserver. Keep the
   private key and its passphrase private.
4. Export the private key in ASCII-armored form. Do not add it to this repository.
5. In GitHub repository settings, add these Actions secrets:
   `MAVEN_CENTRAL_USERNAME`, `MAVEN_CENTRAL_PASSWORD`, `SIGNING_KEY_ID`,
   `SIGNING_PASSWORD`, and `GPG_KEY_CONTENTS`.

The manual **Upload Maven Central deployment** workflow signs all published artifacts and uploads a
deployment; it deliberately does **not** release it. Inspect the deployment in the Portal after it
validates, then choose **Publish** there. Maven Central commonly takes another 10–30 minutes before
the artifacts resolve publicly.

Do not store Portal tokens or signing keys in this repository or `gradle.properties`.

## First release sequence

1. Finish the Maven Central setup above and add the five GitHub Actions secrets.
2. Run the local verification command; `verifyMavenPublications` checks every generated POM.
3. Run **Upload Maven Central deployment** manually from the Actions tab with `version: 0.1.0`.
4. In the Portal, wait for validation, inspect the deployment, and click **Publish**.
5. Once `io.github.brennanium` artifacts resolve from Maven Central, create the `v0.1.0` GitHub
   release from `main` using the prepared release notes command.

## Documentation site

The `Deploy documentation` workflow builds `build/docs/site` and publishes it through GitHub Pages.
An administrator must select **GitHub Actions** as the repository's Pages source once in the Pages
settings. It deploys only from `main`.
