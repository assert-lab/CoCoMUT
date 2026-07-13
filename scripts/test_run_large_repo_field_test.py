#!/usr/bin/env python3

import argparse
import csv
import importlib.util
import tempfile
import unittest
from pathlib import Path


MODULE_PATH = Path(__file__).with_name("run_large_repo_field_test.py")
SPEC = importlib.util.spec_from_file_location("run_large_repo_field_test", MODULE_PATH)
FIELD_TEST = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(FIELD_TEST)


class FieldTestIdentityTest(unittest.TestCase):
    def test_default_launcher_materializes_artifact_before_hashing(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / "tool"
            launcher = root / "bin/cocomut"
            launcher.parent.mkdir(parents=True)
            launcher.write_text(
                "#!/bin/sh\n"
                "ROOT=$(CDPATH= cd -- \"$(dirname -- \"$0\")/..\" && pwd)\n"
                "mkdir -p \"$ROOT/dist\"\n"
                "printf materialized > \"$ROOT/dist/cocomut-cli.jar\"\n",
                encoding="utf-8",
            )
            launcher.chmod(0o755)

            identity = FIELD_TEST.cocomut_tool_identity(str(launcher))

            self.assertEqual("release-jar", identity["artifact"]["selection"])
            self.assertTrue(Path(identity["artifact"]["path"]).is_file())
            self.assertTrue(identity["artifact"]["sha256"])

    def test_resume_rejects_changed_selected_jar(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            launcher = root / "tool/bin/cocomut"
            jar = root / "tool/dist/cocomut-cli.jar"
            launcher.parent.mkdir(parents=True)
            jar.parent.mkdir(parents=True)
            launcher.write_text("#!/bin/sh\nexit 0\n", encoding="utf-8")
            launcher.chmod(0o755)
            jar.write_bytes(b"first artifact")

            repos_csv = root / "repos.csv"
            with repos_csv.open("w", newline="", encoding="utf-8") as handle:
                writer = csv.DictWriter(handle, ["repo"])
                writer.writeheader()
                writer.writerow({"repo": "owner/repository"})
            output_root = root / "results"
            output_root.mkdir()
            args = argparse.Namespace(
                repos_csv=repos_csv,
                output_root=output_root,
                cocomut_command=str(launcher),
                timeout=1800,
                compile_timeout=600,
                heap_gb=4,
                resume=False,
            )
            rows = [{"repo": "owner/repository"}]

            FIELD_TEST.ensure_environment(args, rows)
            first = FIELD_TEST.cocomut_tool_identity(str(launcher))
            self.assertEqual("release-jar", first["artifact"]["selection"])

            jar.write_bytes(b"second artifact")
            second = FIELD_TEST.cocomut_tool_identity(str(launcher))
            self.assertNotEqual(first["artifact"]["sha256"], second["artifact"]["sha256"])
            args.resume = True

            with self.assertRaisesRegex(SystemExit, "cocomut_tool"):
                FIELD_TEST.ensure_environment(args, rows)


if __name__ == "__main__":
    unittest.main()
