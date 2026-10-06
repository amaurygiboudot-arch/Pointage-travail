#!/usr/bin/env python3
"""Collect exact-head Actions data outside the review sandbox; never issue a verdict."""
import argparse
import json
import re
import subprocess
from pathlib import Path


def gh_json(endpoint):
    result = subprocess.run(["gh", "api", endpoint], check=True, capture_output=True,
                            text=True, timeout=25)
    return json.loads(result.stdout)


def collect(repo, head, fetch):
    result = {"repository": repo, "head_sha": head, "collection": "available", "runs": []}
    try:
        payload = fetch(f"repos/{repo}/actions/runs?head_sha={head}&event=pull_request&per_page=100")
        runs = [run for run in payload["workflow_runs"] if run.get("head_sha") == head]
        latest = {}
        for run in runs:
            key = run["workflow_id"]
            if key not in latest or run["id"] > latest[key]["id"]:
                latest[key] = run
        for run in latest.values():
            evidence = {key: run.get(key) for key in ("id", "workflow_id", "name", "head_sha",
                       "run_attempt", "status", "conclusion", "html_url")}
            jobs = fetch(f"repos/{repo}/actions/runs/{run['id']}/jobs?filter=latest&per_page=100")
            evidence["jobs_complete"] = jobs.get("total_count") == len(jobs.get("jobs", []))
            evidence["jobs"] = [{key: job.get(key) for key in ("id", "name", "status",
                               "conclusion", "html_url")} for job in jobs.get("jobs", [])]
            result["runs"].append(evidence)
        if payload.get("total_count", 0) > len(payload["workflow_runs"]):
            result["collection"] = "incomplete"
    except (KeyError, ValueError, TypeError, subprocess.SubprocessError, OSError):
        result["collection"] = "unavailable"
        result["runs"] = []
    return result


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--head", required=True)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    if not re.fullmatch(r"[a-f0-9]{40}", args.head):
        parser.error("A full commit SHA is required")
    try:
        repo = subprocess.run(["gh", "repo", "view", "--json", "nameWithOwner", "-q", ".nameWithOwner"],
                              check=True, capture_output=True, text=True, timeout=15).stdout.strip()
        if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repo):
            raise ValueError("Invalid repository")
        result = collect(repo, args.head, gh_json)
    except (ValueError, subprocess.SubprocessError, OSError):
        result = {"head_sha": args.head, "collection": "unavailable", "runs": []}
    Path(args.output).write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n")


if __name__ == "__main__":
    main()
