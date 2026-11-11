# IDE artifact release strategy

KEFS loads a compiler plugin built for the Kotlin compiler embedded in the IDE. KPaths' normal
Gradle compiler plugin and its IDE compiler artifacts serve different environments. For each KPaths
release, the intended distribution is to build and test IDE artifacts for the IDE/compiler
combinations in [the compatibility matrix](ide-compatibility.json), then make them available in a
public Maven repository on GitHub Pages. The artifact version combines the IDE compiler version and
KPaths version, for example `2.4.20-ij262-52-0.2.0`.

When a later KPaths version is released, its artifacts should be added alongside the earlier ones.
New IDE builds can be added to the matrix and artifacts produced for existing KPaths versions when
the versions are compatible and pass their tests. The matrix describes combinations we have tested,
not every IDE or compiler that might work. An artifact for an earlier IDE compiler may happen to
work on a newer one; ordinary KEFS resolution currently selects by compiler version, and such reuse
must be tested and explicitly supported rather than assumed. See the
[compatibility experiment](range-compatibility-research.md).

The library's ordinary artifacts continue to be published to Maven Central. The IDE repository is a
separate distribution channel and does not require another Maven Central release for each IDE build.
An end user will still need KEFS and a project configuration pointing it at this repository; KPaths
is not yet a built-in KEFS provider. The final setup and repository URL remain to be established.

## Try the CI draft

The [KEFS IDE artifacts workflow](../.github/workflows/kefs-ide-artifacts.yml) runs when a branch
under `ci/kefs-artifacts/` is pushed, using its default inputs. This provides a way to test the
workflow before merging it to the default branch. It can also be triggered manually: GitHub only
offers `workflow_dispatch` for workflows present on the default branch, after which a different
branch can be selected for the run.

* **test_version** defaults to `0.2.0-ci.1`. It changes the version only in the disposable CI
  checkout, including the test consumer. A stable-looking version such as `0.2.0` can also be
  tested; that does not release it.
* **ide_build** defaults to `all`; specify a build number from the matrix to test one IDE.
* **ide_probe** optionally launches the imported consumer with KEFS enabled and disabled. This is
  experimental on a hosted runner; the normal run already executes the KEFS IDE test fixture.

Each job downloads the pinned Android Studio installer, checks its SHA-256, validates the installed
build against the matrix, builds the IDE artifact, runs the fixture and the standalone consumer
build, checks the expected Maven coordinates, JAR, and `.md5` checksum, and uploads the resulting
local Maven repository as a GitHub Actions artifact for 14 days. KEFS's remote resolution requires
the JAR's `.md5` file: the [HTTPS resolution experiment](README.md#remote-maven-resolution-experiment)
verified both the missing-checksum failure and successful resolution with the checksum present.
This is a dry run: it does not publish to Pages or Maven Central, create a release, or persist the
temporary version in the repository. A successful build on a developer's machine does not prove
that the hosted runner has enough resources or supports all IDE test operations. The full draft
workflow has passed on GitHub Actions for the Android Studio build currently in the matrix; that run
predated the additional Maven validation step.

## Work before public hosting

GitHub Pages deploys the complete site, and the existing documentation workflow rebuilds that site
from scratch. A separate Pages deployment for Maven artifacts would replace the documentation, and
a subsequent documentation deployment could remove the Maven repository. Before public hosting,
the deployment must assemble **both** the complete documentation site and the Maven repository in
one Pages artifact, with a durable source for all previously published versions. Actions artifacts
are temporary and are not that source. The release process also needs to merge matrix outputs,
preserve Maven metadata correctly, verify the resulting repository and consumer setup, coordinate
concurrent deployments, and define how historical releases gain support for new IDE versions.
The assembly must retain the JAR checksum files and validate the combined repository, not only
the individual matrix outputs.

The current workflow intentionally stops before those steps; it is a first draft of the build and
verification pipeline, not a public deployment pipeline.
