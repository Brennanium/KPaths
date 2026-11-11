# Compiler snapshots

The compiler contract tests primarily use inline `assertCompiler` expectations. Omit both
`fir` and `ir` to request both snapshots, or pass `fir = snapshot()` or `ir = snapshot()` to
request a single missing expectation. Missing expectations are recorded locally by default;
CI defaults to verification only. Use `-Pkpaths.snapshotRecording=none` to disable recording,
`missing` to write missing expectations, `failed` to update mismatches, or `all` to replace
all requested expectations. The assertion can also set `record = SnapshotRecording.MISSING`
directly. Recording deliberately fails so the changes can be reviewed; rerun normally to verify.

Inline recording requires an unambiguous `assertCompiler` call and literal expectations. It
reports unsupported shapes without changing the file. With
`-Pkpaths.snapshotRecordVersion=true`, existing shared expectations can be supplemented with
an override for `-Pkpaths.testKotlinVersion=<version>`.

## File-backed snapshots

The compiler contract tests compare FIR and IR captures with files in this directory. Each
scenario has a shared expectation such as `enum-cases.fir.txt`. If a compiler version needs a
different expectation, `enum-cases.2.4.20.fir.txt` takes precedence for that exact version.
The other phases and versions continue to use their shared files.

To record missing snapshots, run the relevant tests with
`-Pkpaths.snapshotRecording=missing`. To replace existing snapshots, use
`-Pkpaths.snapshotRecording=all`. Add `-Pkpaths.snapshotRecordVersion=true` and
`-Pkpaths.testKotlinVersion=<version>` to record version-specific files. Recording deliberately
fails the tests so that changes must be reviewed; rerun without the recording options to verify.
Existing snapshots are not changed in `missing` mode. Commit expected files after reviewing them.

For example:

```sh
./gradlew :kpaths-compiler-plugin-fixture:compilerPluginContractTest \
  --tests '*snapshot*' -Pkpaths.snapshotRecording=missing
```

