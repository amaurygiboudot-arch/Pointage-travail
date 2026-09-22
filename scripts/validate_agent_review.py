#!/usr/bin/env python3
import argparse
import base64
import json
import re
import sys
from pathlib import Path

MARKER = re.compile(r"<!-- HORATRACK_AGENT_REVIEW_V1:([A-Za-z0-9_-]+) -->")


def load_json(path: str):
    return json.loads(Path(path).read_text(encoding="utf-8"))


def decode_marker(body: str):
    matches = MARKER.findall(body or "")
    reports = []
    for encoded in matches:
        try:
            padding = "=" * (-len(encoded) % 4)
            raw = base64.urlsafe_b64decode(encoded + padding).decode("utf-8")
            reports.append(json.loads(raw))
        except Exception:
            continue
    return reports


def report_from_comments(path: str, head: str):
    comments = load_json(path)
    candidates = []
    for comment in comments:
        body = comment.get("body", "") if isinstance(comment, dict) else ""
        for report in decode_marker(body):
            if report.get("head_sha") == head:
                candidates.append(report)
    return candidates[-1] if candidates else None


def validate(route: dict, report: dict, head: str) -> list[str]:
    errors = []
    if report is None:
        return ["aucun rapport d'agents valide trouvé pour ce HEAD"]

    if report.get("schema_version") != 1:
        errors.append("schema_version != 1")
    if report.get("head_sha") != head:
        errors.append("head_sha du rapport différent du HEAD courant")

    expected = route.get("specialists", [])
    required = report.get("required_specialists", [])
    if required != expected:
        errors.append(f"spécialistes requis différents du routeur: attendu {expected}, obtenu {required}")

    reviews = report.get("specialist_reviews", [])
    by_agent = {}
    for item in reviews:
        if isinstance(item, dict) and item.get("agent"):
            by_agent[item["agent"]] = item

    for agent in expected:
        item = by_agent.get(agent)
        if item is None:
            errors.append(f"{agent}: revue absente")
        elif item.get("status") != "PASS":
            errors.append(f"{agent}: statut {item.get('status')}")

    for field, expected_agent in (("team_lead", "team_lead"), ("qa_reviewer", "qa_reviewer")):
        item = report.get(field, {})
        if item.get("agent") != expected_agent:
            errors.append(f"{field}: agent incorrect")
        if item.get("status") != "PASS":
            errors.append(f"{field}: statut {item.get('status')}")

    gate = report.get("control_gate", {})
    if gate.get("agent") != "control_gate":
        errors.append("control_gate: agent incorrect")
    if gate.get("decision") != "PASS":
        errors.append(f"control_gate: décision {gate.get('decision')}")
    if gate.get("fusion_authorisee") is not True:
        errors.append("control_gate: fusion non autorisée")

    blockers = report.get("blocking_issues", [])
    if blockers:
        errors.append(f"anomalies bloquantes présentes: {len(blockers)}")

    failed_tests = [
        item.get("name", "?")
        for item in report.get("tests", [])
        if isinstance(item, dict) and item.get("status") == "FAIL"
    ]
    if failed_tests:
        errors.append("tests FAIL: " + ", ".join(failed_tests))

    return errors


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--route", required=True)
    parser.add_argument("--head", required=True)
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument("--report")
    group.add_argument("--comments")
    args = parser.parse_args()

    route = load_json(args.route)
    report = load_json(args.report) if args.report else report_from_comments(args.comments, args.head)
    errors = validate(route, report, args.head)

    if errors:
        print("AGENT REVIEW GATE: FAIL")
        for error in errors:
            print(f"- {error}")
        return 1

    print("AGENT REVIEW GATE: PASS")
    print("Spécialistes:", ", ".join(route.get("specialists", [])))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
