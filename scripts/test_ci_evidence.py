import unittest
from collect_ci_evidence import collect

HEAD = "a" * 40


class CiEvidenceTest(unittest.TestCase):
    def run_data(self, run_id, head=HEAD, conclusion="success", attempt=1):
        return dict(id=run_id, workflow_id=7, head_sha=head, run_attempt=attempt,
                    name="Security checks", status="completed", conclusion=conclusion)

    def test_excludes_other_commits_and_keeps_current_failed_attempt(self):
        runs = [self.run_data(10), self.run_data(11, conclusion="failure", attempt=2),
                self.run_data(12, head="b" * 40)]
        fetched = []
        def fetch(url):
            fetched.append(url)
            if "/jobs?" in url:
                return {"total_count": 1, "jobs": [dict(id=1, status="completed", conclusion="failure")]}
            return {"total_count": len(runs), "workflow_runs": runs}
        result = collect("owner/repo", HEAD, fetch)
        self.assertEqual([11], [r["id"] for r in result["runs"]])
        self.assertEqual("failure", result["runs"][0]["conclusion"])
        self.assertEqual(2, result["runs"][0]["run_attempt"])
        self.assertTrue(any("filter=latest" in url for url in fetched))

    def test_network_failure_does_not_retain_partial_success_evidence(self):
        def fetch(url):
            if "/jobs?" in url:
                raise OSError("offline")
            return {"total_count": 1, "workflow_runs": [self.run_data(10)]}
        self.assertEqual("unavailable", collect("owner/repo", HEAD, fetch)["collection"])
        self.assertEqual([], collect("owner/repo", HEAD, fetch)["runs"])

    def test_truncated_job_results_are_explicitly_incomplete(self):
        def fetch(url):
            if "/jobs?" in url:
                return {"total_count": 2, "jobs": [dict(id=1, conclusion="success")]}
            return {"total_count": 1, "workflow_runs": [self.run_data(10)]}
        result = collect("owner/repo", HEAD, fetch)
        self.assertFalse(result["runs"][0]["jobs_complete"])

    def test_no_runs_remains_empty_without_success_verdict(self):
        result = collect("owner/repo", HEAD, lambda _: {"total_count": 0, "workflow_runs": []})
        self.assertEqual([], result["runs"])
        self.assertNotIn("PASS", str(result))


if __name__ == "__main__":
    unittest.main()
