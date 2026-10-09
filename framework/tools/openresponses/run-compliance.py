#!/usr/bin/env python3
import argparse
import hashlib
import json
import os
import shutil
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from pathlib import Path


def read_json(path):
    with open(path, "r", encoding="utf-8") as handle:
        return json.load(handle)


def sha256(path):
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for chunk in iter(lambda: handle.read(65536), b""):
            digest.update(chunk)
    return digest.hexdigest()


def run(command, cwd=None, timeout=None, env=None):
    return subprocess.run(command, cwd=cwd, timeout=timeout, text=True, stdout=subprocess.PIPE,
                          stderr=subprocess.PIPE, env=env)


def find_bun():
    bun_path = shutil.which("bun")
    if bun_path:
        return bun_path
    home_bun = Path.home() / ".bun" / "bin" / "bun"
    if home_bun.exists() and os.access(home_bun, os.X_OK):
        return str(home_bun)
    return None


def bun_version(bun_path):
    result = run([bun_path, "--version"], timeout=30)
    if result.returncode:
        return None
    return result.stdout.strip()


def ensure_repo(manifest, repo_dir):
    repo_dir.mkdir(parents=True, exist_ok=True)
    if not (repo_dir / ".git").exists():
        result = run(["git", "init"], cwd=repo_dir)
        if result.returncode:
            raise RuntimeError(result.stderr or result.stdout)
        result = run(["git", "remote", "add", "origin", manifest["repoUrl"]], cwd=repo_dir)
        if result.returncode:
            raise RuntimeError(result.stderr or result.stdout)
    result = run(["git", "fetch", "--depth", "1", "origin", manifest["commit"]], cwd=repo_dir, timeout=120)
    if result.returncode:
        raise RuntimeError(result.stderr or result.stdout)
    result = run(["git", "checkout", "--detach", manifest["commit"]], cwd=repo_dir)
    if result.returncode:
        raise RuntimeError(result.stderr or result.stdout)


def verify_hashes(manifest, repo_dir):
    mismatches = []
    for relative_path, expected in manifest["files"].items():
        actual = sha256(repo_dir / relative_path)
        if actual != expected:
            mismatches.append({"path": relative_path, "expected": expected, "actual": actual})
    return mismatches


def sanitize(value, secrets):
    if isinstance(value, dict):
        result = {}
        for key, item in value.items():
            if key in ("request", "response"):
                continue
            result[key] = sanitize(item, secrets)
        return result
    if isinstance(value, list):
        return [sanitize(item, secrets) for item in value]
    if isinstance(value, str):
        clean = value
        for secret in secrets:
            if secret:
                clean = clean.replace(secret, "<redacted>")
        return clean
    return value


def write_reports(report, json_path, junit_path):
    json_path.parent.mkdir(parents=True, exist_ok=True)
    junit_path.parent.mkdir(parents=True, exist_ok=True)
    json_path.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    suite = ET.Element("testsuite", {
        "name": "openresponses-provider-compliance",
        "tests": str(report["summary"].get("total", 0)),
        "failures": str(0 if report["status"] == "blocked"
                        else report["summary"].get("failed", 0) + len(report.get("missingTestIds", []))),
        "skipped": str(report["summary"].get("skipped", 0)),
        "time": str(round(report.get("durationSeconds", 0), 3))
    })
    for result in report.get("results", []):
        case = ET.SubElement(suite, "testcase", {
            "classname": "openresponses",
            "name": result.get("id", result.get("name", "unknown")),
            "time": str((result.get("duration") or 0) / 1000.0)
        })
        if result.get("status") == "failed":
            failure = ET.SubElement(case, "failure", {"message": "; ".join(result.get("errors") or [])})
            failure.text = "\n".join(result.get("errors") or [])
        elif result.get("status") == "skipped":
            ET.SubElement(case, "skipped", {"message": "; ".join(result.get("errors") or [])})
    ET.ElementTree(suite).write(junit_path, encoding="utf-8", xml_declaration=True)


def blocked(manifest, args, reason):
    expected_ids = selected_expected(manifest, args.filter)
    skipped_tests = [{"id": test_id, "status": "skipped", "errors": [reason], "duration": 0}
                     for test_id in expected_ids]
    report = {
        "status": "blocked",
        "blockReason": reason,
        "manifest": manifest,
        "expectedTestIds": expected_ids,
        "actualTestIds": [],
        "executedTestIds": [],
        "skippedTestIds": expected_ids,
        "missingTestIds": expected_ids,
        "unexpectedTestIds": [],
        "summary": {"passed": 0, "failed": 0, "skipped": len(expected_ids), "total": len(expected_ids)},
        "results": skipped_tests,
        "durationSeconds": 0
    }
    write_reports(report, Path(args.json_report), Path(args.junit_report))
    return 2 if args.required else 0


def selected_expected(manifest, filter_csv):
    expected = manifest["expectedTestIds"]
    if not filter_csv:
        return expected
    wanted = [item.strip() for item in filter_csv.split(",") if item.strip()]
    return [item for item in expected if item in wanted]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--manifest", required=True)
    parser.add_argument("--work-dir", required=True)
    parser.add_argument("--json-report", required=True)
    parser.add_argument("--junit-report", required=True)
    parser.add_argument("--base-url", default=os.environ.get("OPENRESPONSES_BASE_URL"))
    parser.add_argument("--api-key", default=os.environ.get("OPENRESPONSES_API_KEY"))
    parser.add_argument("--model", default=os.environ.get("OPENRESPONSES_MODEL", "gpt-4o-mini"))
    parser.add_argument("--filter", default=os.environ.get("OPENRESPONSES_FILTER"))
    parser.add_argument("--timeout-seconds", type=int, default=int(os.environ.get("OPENRESPONSES_TIMEOUT_SECONDS", "300")))
    parser.add_argument("--required", action="store_true")
    args = parser.parse_args()

    manifest = read_json(args.manifest)
    if not args.base_url:
        return blocked(manifest, args, "OPENRESPONSES_BASE_URL is not configured")
    if not args.api_key:
        return blocked(manifest, args, "OPENRESPONSES_API_KEY is not configured")
    bun_path = find_bun()
    if not bun_path:
        return blocked(manifest, args, "bun is not installed")
    actual_bun_version = bun_version(bun_path)
    if actual_bun_version != manifest["bunVersion"]:
        return blocked(manifest, args, "bun %s is required but %s is installed" %
                       (manifest["bunVersion"], actual_bun_version or "unknown"))

    work_dir = Path(args.work_dir)
    repo_dir = work_dir / "openresponses"
    start = time.time()
    ensure_repo(manifest, repo_dir)
    mismatches = verify_hashes(manifest, repo_dir)
    if mismatches:
        report = {
            "status": "failed",
            "blockReason": "manifest hash mismatch",
            "manifest": manifest,
            "hashMismatches": mismatches,
            "expectedTestIds": selected_expected(manifest, args.filter),
            "actualTestIds": [],
            "missingTestIds": selected_expected(manifest, args.filter),
            "unexpectedTestIds": [],
            "summary": {"passed": 0, "failed": 1, "skipped": 0, "total": 0},
            "results": [],
            "durationSeconds": round(time.time() - start, 3)
        }
        write_reports(report, Path(args.json_report), Path(args.junit_report))
        return 1

    runner_env = os.environ.copy()
    bun_dir = str(Path(bun_path).resolve().parent)
    runner_env["PATH"] = bun_dir + os.pathsep + runner_env.get("PATH", "")
    install = run([bun_path, "install", "--frozen-lockfile"], cwd=repo_dir, timeout=args.timeout_seconds,
                  env=runner_env)
    if install.returncode:
        raise RuntimeError(install.stderr or install.stdout)
    command = [bun_path, "run", "test:compliance", "--base-url", args.base_url,
               "--api-key", args.api_key, "--model", args.model, "--json"]
    if args.filter:
        command.extend(["--filter", args.filter])
    result = run(command, cwd=repo_dir, timeout=args.timeout_seconds, env=runner_env)
    raw = result.stdout[result.stdout.find("{"):] if "{" in result.stdout else "{}"
    parsed = json.loads(raw)
    parsed = sanitize(parsed, [args.api_key])
    actual_ids = [item.get("id") for item in parsed.get("results", []) if item.get("id")]
    expected_ids = selected_expected(manifest, args.filter)
    skipped_ids = [item.get("id") for item in parsed.get("results", [])
                   if item.get("id") and item.get("status") == "skipped"]
    missing = [item for item in expected_ids if item not in actual_ids]
    unexpected = [item for item in actual_ids if item not in expected_ids]
    report = {
        "status": "failed" if result.returncode or missing or unexpected else "passed",
        "manifest": manifest,
        "command": [part if part != args.api_key else "<redacted>" for part in command],
        "stdout": "" if result.returncode == 0 else sanitize(result.stdout[-4000:], [args.api_key]),
        "stderr": "" if result.returncode == 0 else sanitize(result.stderr[-4000:], [args.api_key]),
        "expectedTestIds": expected_ids,
        "actualTestIds": actual_ids,
        "executedTestIds": actual_ids,
        "skippedTestIds": skipped_ids,
        "missingTestIds": missing,
        "unexpectedTestIds": unexpected,
        "summary": parsed.get("summary", {}),
        "results": parsed.get("results", []),
        "durationSeconds": round(time.time() - start, 3)
    }
    write_reports(report, Path(args.json_report), Path(args.junit_report))
    return 1 if report["status"] == "failed" else 0


if __name__ == "__main__":
    sys.exit(main())
