import unittest

from scripts.kotlin_version_resolver import ARTIFACTS, resolve


class KotlinVersionResolverTest(unittest.TestCase):
    def test_exact_versions_do_not_query_network(self):
        def fail(*_):
            raise AssertionError("Exact versions should not fetch metadata")
        self.assertEqual(resolve(["2.4.0", "2.5.0-dev-42", "2.5.0-RC2"], fetch=fail),
                         [("2.4.0", "2.4.0"), ("2.5.0-dev-42", "2.5.0-dev-42"), ("2.5.0-RC2", "2.5.0-RC2")])

    def test_latest_is_numeric_and_available_for_all_required_artifacts(self):
        def fetch(channel, artifact):
            if channel == "dev":
                return {"2.5.0-dev-9", "2.5.0-dev-100", "2.5.0-dev-999"} if artifact != ARTIFACTS[1] else {"2.5.0-dev-9", "2.5.0-dev-100"}
            return {"2.5.0-dev-99"} if channel == "bootstrap" else set()
        self.assertEqual(resolve(["2.5.0-dev*", "2.5.0-dev-*"], fetch=fetch),
                         [("2.5.0-dev*", "2.5.0-dev-100"), ("2.5.0-dev-*", "2.5.0-dev-100")])

    def test_no_complete_build_or_malformed_selector_fails(self):
        with self.assertRaisesRegex(ValueError, "both compiler and Gradle"):
            resolve(["2.5.0-dev*"], fetch=lambda *_: set())
        with self.assertRaisesRegex(ValueError, "Invalid Kotlin version"):
            resolve(["2.5.*"], fetch=lambda *_: set())

    def test_metadata_error_does_not_silently_select_an_older_build(self):
        def fetch(channel, _):
            if channel == "dev":
                raise ValueError("repository unavailable")
            return {"2.5.0-dev-1"}
        with self.assertRaisesRegex(ValueError, "repository unavailable"):
            resolve(["2.5.0-dev*"], fetch=fetch)


if __name__ == "__main__":
    unittest.main()
