#!/usr/bin/env python3
import importlib.util
import base64
import json
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def load_module(name: str, relative: str):
    spec = importlib.util.spec_from_file_location(name, ROOT / relative)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(module)
    return module


router = load_module("agent_router", "scripts/agent_router.py")
validator = load_module("validate_agent_review", "scripts/validate_agent_review.py")


class AgentRouterTest(unittest.TestCase):
    def test_celestial_lifecycle_routes_all_relevant_specialists(self):
        files = [
            "app/src/main/java/com/amaury/pointage/EarthGlobeRendererV2.kt",
            "app/src/main/java/com/amaury/pointage/HpAnalogClockView.kt",
        ]
        result = router.route(files, "base", "head")
        self.assertIn("celestial_system", result["specialists"])
        self.assertIn("ui_ux", result["specialists"])
        self.assertIn("mobile_platforms", result["specialists"])
        self.assertIn("performance_battery", result["specialists"])

    def test_salary_change_routes_salary_without_inventing_celestial(self):
        files = ["app/src/main/java/com/amaury/pointage/V2SalaryNetBridgeV2.kt"]
        result = router.route(files, "base", "head")
        self.assertIn("salary_v2", result["specialists"])
        self.assertNotIn("celestial_system", result["specialists"])

    def test_governance_change_requires_security_and_people_ops(self):
        files = [".github/workflows/security.yml", "AGENTS.md"]
        result = router.route(files, "base", "head")
        self.assertTrue(result["governance_change"])
        self.assertIn("security_privacy", result["specialists"])
        self.assertIn("people_ops", result["specialists"])
        self.assertIn("release_store", result["specialists"])


class AgentReviewValidatorTest(unittest.TestCase):
    def route(self):
        return {
            "base_sha": "base123",
            "head_sha": "abc123",
            "specialists": ["celestial_system", "ui_ux"],
        }

    def valid_report(self):
        return {
            "schema_version": 2,
            "base_sha": "base123",
            "head_sha": "abc123",
            "required_specialists": ["celestial_system", "ui_ux"],
            "specialist_reviews": [
                {"agent": "celestial_system", "status": "PASS", "summary": "ok"},
                {"agent": "ui_ux", "status": "PASS", "summary": "ok"},
            ],
            "team_lead": {"agent": "team_lead", "status": "PASS", "summary": "ok"},
            "qa_reviewer": {"agent": "qa_reviewer", "status": "PASS", "summary": "ok"},
            "control_gate": {
                "agent": "control_gate",
                "decision": "PASS",
                "fusion_authorisee": True,
                "summary": "ok",
            },
            "tests": [],
            "blocking_issues": [],
        }

    def test_valid_report_passes(self):
        self.assertEqual([], validator.validate(self.route(), self.valid_report(), "abc123"))

    def test_stale_head_fails(self):
        errors = validator.validate(self.route(), self.valid_report(), "new456")
        self.assertTrue(any("head_sha" in error for error in errors))

    def test_route_head_must_match_requested_head(self):
        route = self.route()
        route["head_sha"] = "other789"
        errors = validator.validate(route, self.valid_report(), "abc123")
        self.assertTrue(any("routeur" in error for error in errors))

    def test_retargeted_base_invalidates_report(self):
        route = self.route()
        route["base_sha"] = "newbase456"
        errors = validator.validate(route, self.valid_report(), "abc123")
        self.assertTrue(any("base_sha" in error for error in errors))

    def test_missing_specialist_fails(self):
        report = self.valid_report()
        report["specialist_reviews"] = report["specialist_reviews"][:1]
        errors = validator.validate(self.route(), report, "abc123")
        self.assertTrue(any("ui_ux" in error for error in errors))

    def test_not_run_never_counts_as_pass(self):
        report = self.valid_report()
        report["specialist_reviews"][0]["status"] = "NOT_RUN"
        errors = validator.validate(self.route(), report, "abc123")
        self.assertTrue(any("NOT_RUN" in error for error in errors))

    def test_comment_evidence_requires_trusted_author(self):
        report = self.valid_report()
        marker = base64.urlsafe_b64encode(json.dumps(report).encode()).decode().rstrip("=")
        body = f"<!-- HORATRACK_AGENT_REVIEW_V1:{marker} -->"
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "comments.json"
            comments = [{"body": body, "user": {"login": "pull-request-author"},
                         "author_association": "CONTRIBUTOR"}]
            path.write_text(json.dumps(comments), encoding="utf-8")
            self.assertIsNone(validator.report_from_comments(str(path), "abc123"))
            comments[0]["user"]["login"] = validator.OWNER_LOGIN
            comments[0]["author_association"] = "OWNER"
            path.write_text(json.dumps(comments), encoding="utf-8")
            self.assertEqual(report, validator.report_from_comments(str(path), "abc123"))
            self.assertIsNone(validator.report_from_comments(str(path), "abc123", "otherbase"))

    def test_action_comment_requires_app_provenance(self):
        report = self.valid_report()
        marker = base64.urlsafe_b64encode(json.dumps(report).encode()).decode().rstrip("=")
        comment = {"body": f"<!-- HORATRACK_AGENT_REVIEW_V1:{marker} -->",
                   "user": {"login": validator.ACTION_LOGIN}}
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "comments.json"
            path.write_text(json.dumps([comment]), encoding="utf-8")
            self.assertIsNone(validator.report_from_comments(str(path), "abc123"))
            comment["performed_via_github_app"] = {"slug": "github-actions"}
            path.write_text(json.dumps([comment]), encoding="utf-8")
            self.assertEqual(report, validator.report_from_comments(str(path), "abc123"))


if __name__ == "__main__":
    unittest.main()
