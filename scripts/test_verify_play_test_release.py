import unittest
from verify_play_test_release import require_successful_run, require_jobs, require_pr_tree, REQUIRED


class ReleaseGateTests(unittest.TestCase):
    def run_fixture(self, **changes):
        run = dict(id=1, head_sha='a'*40, head_repository={'full_name': 'owner/repo'},
                   event='pull_request', path='.github/workflows/security.yml',
                   run_number=10, run_attempt=1, status='completed', conclusion='success')
        return {**run, **changes}

    def check(self, runs):
        return require_successful_run(runs, 'a'*40, 'owner/repo', 'security.yml')

    def test_exact_success(self):
        self.assertEqual(self.check([self.run_fixture()])['id'], 1)

    def test_wrong_sha_fork_workflow_and_missing_rejected(self):
        for changes in [dict(head_sha='b'*40), dict(head_repository={'full_name': 'fork/repo'}),
                        dict(path='.github/workflows/other.yml'), dict(event='pull_request_target'), dict(event='pull_request_target')]:
            with self.assertRaises(ValueError):
                self.check([self.run_fixture(**changes)])
        with self.assertRaises(ValueError):
            self.check([])

    def test_new_failure_or_running_run_supersedes_old_success(self):
        for status, conclusion in [('completed', 'failure'), ('in_progress', None), ('completed', 'cancelled')]:
            with self.assertRaises(ValueError):
                self.check([self.run_fixture(), self.run_fixture(id=2, run_number=11, status=status, conclusion=conclusion)])

    def test_rerun_attempt_supersedes_old_success(self):
        with self.assertRaises(ValueError):
            self.check([self.run_fixture(), self.run_fixture(run_attempt=2, conclusion='failure')])

    def test_all_required_jobs_must_pass_not_skip(self):
        names = REQUIRED['security.yml']
        jobs = [dict(name=name, status='completed', conclusion='success') for name in names]
        require_jobs(jobs, names)
        for conclusion in ['skipped', 'failure', None]:
            altered = [dict(jobs[0], conclusion=conclusion)] + jobs[1:]
            with self.assertRaises(ValueError):
                require_jobs(altered, names)
        with self.assertRaises(ValueError):
            require_jobs([], names)

    def test_pr_tree_proof(self):
        pr = dict(head=dict(sha='a'*40, repo={'id': 12}),
                  base=dict(sha='b'*40, ref='main', repo={'id': 12}))
        run = self.run_fixture(pull_requests=[pr])
        comparison = dict(status='ahead', merge_base_commit={'sha': 'b'*40})
        self.assertEqual(require_pr_tree(run, 'a'*40, 12, comparison), 'b'*40)
        for altered in [dict(status='diverged', merge_base_commit={'sha': 'b'*40}),
                        dict(status='ahead', merge_base_commit={'sha': 'c'*40})]:
            with self.assertRaises(ValueError):
                require_pr_tree(run, 'a'*40, 12, altered)
        for altered_run in [self.run_fixture(pull_requests=[]), self.run_fixture(pull_requests=[pr, pr])]:
            with self.assertRaises(ValueError):
                require_pr_tree(altered_run, 'a'*40, 12, comparison)
        with self.assertRaises(ValueError):
            require_pr_tree(run, 'a'*40, 99, comparison)


if __name__ == '__main__':
    unittest.main()
