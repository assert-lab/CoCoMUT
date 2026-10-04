"""Exercise the independent auditor against a real Spoon model and omitted roots."""
import argparse
import json
import pathlib
import subprocess
import tempfile


def run(command):
    return subprocess.run(command, check=True, capture_output=True, text=True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--jar", type=pathlib.Path, required=True)
    parser.add_argument("--java", default="java")
    args = parser.parse_args()
    scripts = pathlib.Path(__file__).resolve().parent
    java = [args.java, "-Xmx2g", "-XX:ActiveProcessorCount=2", "-cp", str(args.jar.resolve())]
    with tempfile.TemporaryDirectory(prefix="cocomut-population-audit-") as directory:
        root = pathlib.Path(directory)
        source = root / "selected/src/main/java"
        source.mkdir(parents=True)
        (source / "Fixture.java").write_text("""package fixture;
class Fixture {
    @Deprecated
\tpublic void annotated() {}
\tprivate void hidden() {}
    void over(int value) {} void over(String value) {}
    /** Default constructor. */
    Fixture() {}
    void container() {class Local {Local(){} void local(){}}}
    enum Mode {ONE; Mode(){} private void modeMethod(){}}
}
""")
        run(["git", "init", "-q", str(root)])
        run(["git", "-C", str(root), "add", "."])
        run(["git", "-C", str(root), "-c", "user.name=Audit Fixture", "-c",
             "user.email=audit@example.invalid", "commit", "-qm", "fixture"])
        manifest = root / "manifest.json"
        manifest.write_text(json.dumps({"project": {"path": str(root), "build_system": "unknown",
                                                  "java_version": "17"},
                                        "artifacts": {"source_roots": [str(source)]}}))
        output = root / "probe"
        run(java + [str(scripts / "SourcePopulationProbe.java"), str(manifest), str(output)])

        def audit():
            target = root / "audit.json"
            run(java + [str(scripts / "JavacPopulationAudit.java"), str(root),
                        str(output / "methods.jsonl"), str(target), str(output / "source-roots.json")])
            return json.loads(target.read_text())

        clean = audit()
        assert clean["declarations"] == clean["matched"] == 10, clean
        assert not clean["missing"] and not clean["ambiguous"] and not clean["parse_failures"], clean
        print("PASS: annotated/tabbed declarations, same-line overloads, private methods and nested constructors")

        discovered = root / "discovered"
        run(java + [str(scripts / "SourcePopulationProbe.java"), str(manifest),
                    str(discovered), "--discover-main"])
        expected_uris = {json.loads(line)["method_uri"]
                         for line in (output / "methods.jsonl").read_text().splitlines()}
        discovered_methods = [json.loads(line)
                              for line in (discovered / "methods.jsonl").read_text().splitlines()]
        assert {method["method_uri"] for method in discovered_methods} == expected_uris
        assert all(method["source_set"] == "main" and not method["generated"]
                   for method in discovered_methods)
        print("PASS: automatic adapter discovery uses main roots and retains population provenance")

        omitted = root / "omitted/src/main/java"
        omitted.mkdir(parents=True)
        (omitted / "Missing.java").write_text("class Missing {public void one(){} private void two(){}}")
        run(["git", "-C", str(root), "add", "omitted"])
        missing = audit()
        assert missing["declarations"] == 12 and missing["matched"] == 10, missing
        assert len(missing["missing"]) == 2 and not missing["parse_failures"], missing
        print("PASS: an entirely omitted production root is detected")

        (omitted / "Broken.java").write_text("class Broken {void broken( { }")
        run(["git", "-C", str(root), "add", "omitted"])
        broken = audit()
        assert len(broken["parse_failures"]) == 1 and len(broken["missing"]) == 2, broken
        print("PASS: javac syntax failures remain explicit instead of counting as complete coverage")


if __name__ == "__main__":
    main()
