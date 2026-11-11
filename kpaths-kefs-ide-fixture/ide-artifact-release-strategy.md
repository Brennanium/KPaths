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
* **replace_version** allows a dry run to build and preview an explicitly replaced full Maven
  version. Publication of a replacement is performed by the separate maintenance workflow.

Each job downloads the pinned Android Studio installer, checks its SHA-256, validates the installed
build against the matrix, builds the IDE artifact, runs the fixture and standalone consumer, and
checks the Maven coordinates, JAR, and `.md5` checksum. The assembly job combines those outputs
with any existing published versions and validates the resulting repository. It also builds the
documentation and puts the merged repository under `maven/`, uploading both the Maven repository and
the combined site as Actions artifacts for 14 days. The preview includes development documentation
and the release-documentation snapshots, using the same snapshot builder as the Pages workflow.

The default is a **dry run**: it does not publish to Pages or Maven Central, create a release, or
persist the temporary version. KEFS's remote lookup requires the JAR's `.md5` file; the
[HTTPS resolution experiment](README.md#remote-maven-resolution-experiment) verified both the
missing-checksum failure and successful resolution when the checksum is present.

## Publish a release

Once this workflow and the updated documentation workflow are on `main`, run the IDE artifact
workflow manually with **publish** enabled, **test_version** set to the release version, and the
matching release tag selected as the workflow ref. Publishing requires the full IDE matrix and the
tagged `VERSION_NAME` to match; it does not create a tag, a GitHub release, or a Maven Central
publication. Only run it after the regular release artifacts are available.

The publish job merges the validated outputs with the latest contents of the `kpaths-kefs-maven`
branch, creates the branch if necessary, and pushes the result. A version already present there must
be byte-for-byte identical; conflicting artifacts fail instead of replacing an existing version.
Release publications are serialized, and a non-fast-forward push fails rather than discarding an
external update. If the branch update succeeds but the Pages request fails, rerunning with the same
artifacts can request deployment again. A byte-different rebuild of an existing version is rejected.

The publish job explicitly dispatches the documentation workflow because a push made with
`GITHUB_TOKEN` does not trigger an ordinary push workflow. The documentation workflow fetches and
validates the durable Maven branch and deploys the complete site, with the repository under
`/maven/`. Documentation and Maven artifacts therefore share one Pages deployment; normal
documentation updates retain all published Maven versions. Before the first publication, the branch
may be absent and documentation deploys without `/maven/`. A fetch error fails the build rather than
silently deploying a site without the repository.

The assembled Maven metadata lists all versions and has regenerated checksums; each version's
artifacts and checksums remain unchanged. The repository is validated both during assembly and
before Pages deployment. The dry-run preview exercises assembly but does not establish that a live
Pages deployment or end-user KEFS configuration has been verified.

## Repository maintenance

The [maintenance workflow](../.github/workflows/kefs-maven-maintenance.yml) defaults to a dry run
and uploads a preview of the resulting repository. It can delete a full Maven version or a specific
nonessential file, or explicitly replace one version with an artifact from a successful IDE
artifact-build run. For replacement, provide both the build run ID and the full IDE-prefixed Maven
version; the workflow extracts only that version from the build artifact. To apply a reviewed
operation, rerun it on `main` with **dry_run** disabled. The apply job rereads the latest durable
branch, serializes with release publishing, and dispatches the combined Pages update.

The corresponding local assembly flags can be repeated:

```sh
# Merge the existing repository and incoming build; preserve all existing versions by default.
python3 scripts/assemble-kefs-maven-repository.py --output /tmp/kpaths-preview old-repo new-repo

# Explicitly replace one version with the copy from the last matching input.
python3 scripts/assemble-kefs-maven-repository.py --output /tmp/kpaths-replacement \
  --replace-version 2.4.20-ij262-52-0.2.0 old-repo new-repo

# Remove a version and its complete directory, or remove an optional sidecar file.
python3 scripts/assemble-kefs-maven-repository.py --output /tmp/kpaths-deletion \
  --delete-version 2.4.20-ij262-52-0.2.0 old-repo
python3 scripts/assemble-kefs-maven-repository.py --output /tmp/kpaths-file-deletion \
  --delete-file io/github/brennanium/kpaths-compiler-plugin-jvm/2.4.20-ij262-52-0.2.0/kpaths-compiler-plugin-jvm-2.4.20-ij262-52-0.2.0.jar.sha512 old-repo
```

Output must be a new directory. The assembler refuses an unapproved conflict, a missing requested
file or version, or a replacement without both existing and incoming copies. It regenerates Maven
metadata and its checksums. The JAR, its `.md5`, and its POM are essential: remove the complete
version instead of deleting one of those files, so KEFS will not discover a broken entry. Replacing
an existing coordinate is an explicit escape hatch; IDE caches can retain the previous JAR, so a new
KPaths version is generally preferable. A maintenance operation cannot replace and delete the same
version in one run.
