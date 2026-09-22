#!/usr/bin/env python3
import argparse
import base64
import json
from pathlib import Path


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--route", required=True)
    parser.add_argument("--report", required=True)
    args = parser.parse_args()

    route = json.loads(Path(args.route).read_text(encoding="utf-8"))
    report = json.loads(Path(args.report).read_text(encoding="utf-8"))
    compact = json.dumps(report, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    marker = base64.urlsafe_b64encode(compact).decode("ascii").rstrip("=")

    specialist_lines = "\n".join(
        f"- **{item['agent']}** : {item['status']} — {item['summary']}"
        for item in report.get("specialist_reviews", [])
    )
    tests = report.get("tests", [])
    test_lines = "\n".join(
        f"- {item['name']} : {item['status']} — {item['details']}"
        for item in tests
    ) or "- Aucun contrôle local déclaré."

    print(f"<!-- HORATRACK_AGENT_REVIEW_V1:{marker} -->")
    print("## 🤖 Revue multi-agents HoraTrack")
    print()
    print(f"**HEAD :** `{report.get('head_sha', '')}`")
    print(f"**Route :** {', '.join(route.get('specialists', []))}")
    print()
    print("### Spécialistes")
    print(specialist_lines or "- Aucun spécialiste.")
    print()
    print("### Chaîne de contrôle")
    print(f"- **team_lead** : {report['team_lead']['status']} — {report['team_lead']['summary']}")
    print(f"- **qa_reviewer** : {report['qa_reviewer']['status']} — {report['qa_reviewer']['summary']}")
    gate = report["control_gate"]
    print(f"- **control_gate** : {gate['decision']} — fusion autorisée : {'OUI' if gate['fusion_authorisee'] else 'NON'}")
    print()
    print("### Contrôles")
    print(test_lines)
    print()
    blockers = report.get("blocking_issues", [])
    print("### Anomalies bloquantes")
    print("\n".join(f"- {item}" for item in blockers) if blockers else "- Aucune.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
