#!/usr/bin/env python3
"""Read-only, fail-closed exact-SHA CI prerequisite for pre-merge Play testing.

This is not the merge agent gate and cannot authorize a merge.
"""
import json
import os
import re
import subprocess

REQUIRED = {
    "security.yml": {"dependency-review", "Firebase Functions tests", "CodeQL Android", "V2 unit tests", "Security update build check", "Google Play distribution check"},
    "build-ios.yml": {"build-ios"},
}


def require_successful_run(runs, sha, repository, workflow, events=frozenset({"pull_request"})):
    matching = [r for r in runs if r.get("head_sha") == sha
                and r.get("head_repository", {}).get("full_name") == repository
                and r.get("event") in events
                and r.get("path") == f".github/workflows/{workflow}"]
    if not matching:
        raise ValueError(f"Missing exact-SHA CI: {workflow}")
    latest = max(matching, key=lambda r: (r["run_number"], r.get("run_attempt", 1)))
    if latest.get("status") != "completed" or latest.get("conclusion") != "success":
        raise ValueError(f"Latest exact-SHA CI not successful: {workflow}")
    return latest


def require_jobs(jobs, required):
    passed = {job["name"] for job in jobs if job.get("status") == "completed" and job.get("conclusion") == "success"}
    for name in required:
        if name not in passed:
            raise ValueError(f"Required job not successful: {name}")


def require_pr_tree(run, sha, repository_id, comparison):
    prs = run.get("pull_requests", [])
    if len(prs) != 1:
        raise ValueError("Missing or ambiguous PR source-tree evidence")
    pr = prs[0]
    head, base = pr.get("head", {}), pr.get("base", {})
    if (head.get("sha") != sha or head.get("repo", {}).get("id") != repository_id
            or base.get("repo", {}).get("id") != repository_id or base.get("ref") != "main"
            or not re.fullmatch(r"[0-9a-f]{40}", base.get("sha", ""))):
        raise ValueError("PR source/base identity mismatch")
    if comparison.get("status") not in {"ahead", "identical"} or comparison.get("merge_base_commit", {}).get("sha") != base["sha"]:
        raise ValueError("PR merge tree is not proven identical to source tree")
    return base["sha"]


def require_test_trigger(event_name, ref):
    if event_name == "workflow_dispatch":
        if not ref.startswith("refs/heads/") or ref == "refs/heads/main":
            raise ValueError("Pre-merge publication requires an explicit non-main branch dispatch")
        return
    if event_name == "push" and ref == "refs/heads/play-internal":
        return
    raise ValueError("Pre-merge publication requires workflow_dispatch or the play-internal delivery pointer")


def api(path):
    return json.loads(subprocess.check_output(["gh", "api", path], text=True))


def main():
    sha = os.environ.get("EXPECTED_SOURCE_SHA", "")
    if not re.fullmatch(r"[0-9a-f]{40}", sha) or sha != os.environ.get("GITHUB_SHA"):
        raise ValueError("Expected SHA must equal the exact dispatched commit")
    require_test_trigger(os.environ.get("GITHUB_EVENT_NAME", ""), os.environ.get("GITHUB_REF", ""))
    repository = os.environ["GITHUB_REPOSITORY"]
    repository_id = api(f"repos/{repository}")["id"]
    for workflow, required in REQUIRED.items():
        runs = api(f"repos/{repository}/actions/workflows/{workflow}/runs?head_sha={sha}&per_page=100")["workflow_runs"]
        run = require_successful_run(runs, sha, repository, workflow)
        prs = run.get("pull_requests", [])
        if len(prs) != 1:
            raise ValueError("Missing or ambiguous PR metadata")
        base = prs[0].get("base", {}).get("sha", "")
        if not re.fullmatch(r"[0-9a-f]{40}", base):
            raise ValueError("Invalid PR base SHA")
        comparison = api(f"repos/{repository}/compare/{base}...{sha}")
        verified_base = require_pr_tree(run, sha, repository_id, comparison)
        print(f"Identical source/PR-merge trees: base={verified_base} head={sha} merge-base={comparison['merge_base_commit']['sha']}")
        jobs = api(f"repos/{repository}/actions/runs/{run['id']}/attempts/{run.get('run_attempt', 1)}/jobs?per_page=100")["jobs"]
        require_jobs(jobs, required)
        print(f"PASS {workflow}: run {run['id']} SHA {sha}")


if __name__ == "__main__":
    main()
