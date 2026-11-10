import copy
import unittest
from unittest.mock import patch

import verify_play_test_release as gate


class PlayTestReleaseGateTest(unittest.TestCase):
    sha = "a" * 40
    base = "b" * 40
    repository = "owner/repo"

    def run_fixture(self):
        return {"id": 1, "head_sha": self.sha,
                "head_repository": {"full_name": self.repository},
                "event": "pull_request", "path": ".github/workflows/security.yml",
                "run_number": 2, "run_attempt": 1,
                "status": "completed", "conclusion": "success",
                "pull_requests": [{"head": {"sha": self.sha, "repo": {"id": 7}},
                                   "base": {"sha": self.base, "ref": "main", "repo": {"id": 7}}}]}

    def test_exact_success(self):
        run = self.run_fixture()
        self.assertEqual(gate.require_successful_run([run], self.sha, self.repository, "security.yml"), run)

    def test_newer_failure_or_pending_cannot_use_old_success(self):
        old = self.run_fixture()
        for status, conclusion in [("completed", "failure"), ("in_progress", None)]:
            new = {**old, "run_attempt": 2, "status": status, "conclusion": conclusion}
            with self.assertRaises(ValueError):
                gate.require_successful_run([old, new], self.sha, self.repository, "security.yml")

    def test_wrong_sha_repository_event_workflow_rejected(self):
        for override in [{"head_sha": self.base}, {"head_repository": {"full_name": "fork/repo"}},
                         {"event": "push"}, {"path": ".github/workflows/other.yml"}]:
            with self.assertRaises(ValueError):
                gate.require_successful_run([{**self.run_fixture(), **override}], self.sha, self.repository, "security.yml")

    def test_all_required_jobs_must_really_pass(self):
        required = gate.REQUIRED["security.yml"]
        self.assertIn("V2 unit tests", required)
        jobs = [{"name": name, "status": "completed", "conclusion": "success"} for name in required]
        gate.require_jobs(jobs, required)
        for conclusion in ["failure", "skipped", "cancelled", None]:
            failed = copy.deepcopy(jobs)
            failed[0]["conclusion"] = conclusion
            with self.assertRaises(ValueError):
                gate.require_jobs(failed, required)
        with self.assertRaises(ValueError):
            gate.require_jobs(jobs[1:], required)

    def test_pr_tree_identity(self):
        comparison = {"status": "ahead", "merge_base_commit": {"sha": self.base}}
        self.assertEqual(gate.require_pr_tree(self.run_fixture(), self.sha, 7, comparison), self.base)
        for comparison in [{"status": "diverged", "merge_base_commit": {"sha": self.base}},
                           {"status": "ahead", "merge_base_commit": {"sha": self.sha}}]:
            with self.assertRaises(ValueError):
                gate.require_pr_tree(self.run_fixture(), self.sha, 7, comparison)

    def test_ambiguous_fork_and_stale_pr_identity_rejected(self):
        comparison = {"status": "ahead", "merge_base_commit": {"sha": self.base}}
        for mode in ["missing", "multiple", "fork", "stale", "other_base"]:
            run = self.run_fixture()
            if mode == "missing": run["pull_requests"] = []
            elif mode == "multiple": run["pull_requests"] *= 2
            elif mode == "fork": run["pull_requests"][0]["head"]["repo"]["id"] = 8
            elif mode == "stale": run["pull_requests"][0]["head"]["sha"] = self.base
            else: run["pull_requests"][0]["base"]["ref"] = "other"
            with self.assertRaises(ValueError):
                gate.require_pr_tree(run, self.sha, 7, comparison)

    def test_dispatch_identity_rejected_before_api(self):
        valid = {"EXPECTED_SOURCE_SHA": self.sha, "GITHUB_SHA": self.sha,
                 "GITHUB_EVENT_NAME": "workflow_dispatch", "GITHUB_REF": "refs/heads/candidate"}
        for override in [{"EXPECTED_SOURCE_SHA": "a" * 7}, {"GITHUB_SHA": self.base},
                         {"GITHUB_EVENT_NAME": "push"}, {"GITHUB_REF": "refs/heads/main"},
                         {"GITHUB_REF": "refs/tags/test"}]:
            with patch.dict("os.environ", {**valid, **override}, clear=True), patch.object(gate, "api") as api:
                with self.assertRaises(ValueError): gate.main()
                api.assert_not_called()


if __name__ == "__main__":
    unittest.main()
