"""Runner lifecycle tests; stubs isolate transport/validation, never claim a real review."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

RUNNER = Path(__file__).with_name('run-agent-review.sh')


class ReviewRunnerTests(unittest.TestCase):
    def run_case(self, case):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            scripts = root / 'scripts'
            scripts.mkdir()
            shutil.copyfile(RUNNER, scripts / RUNNER.name)
            (scripts / 'agent-review-prompt.md').write_text('test prompt')
            binaries = root / 'bin'
            binaries.mkdir()
            # Restricted PATH makes the missing-gh case independent of host installations.
            for command in ('dirname', 'mkdir', 'mktemp', 'cat', 'pwd'):
                executable = shutil.which(command)
                if executable:
                    (binaries / command).symlink_to(executable)

            def stub(name, body):
                path = binaries / name
                path.write_text('#!/bin/bash\nset -eu\n' + body)
                path.chmod(0o755)

            stub('git', '''if [[ "$2" == "--git-path" ]]; then
  echo .git/agent-review-reports
else
  echo aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
fi
''')
            stub('codex', '''while [[ "$#" -gt 0 ]]; do
  if [[ "$1" == "-o" ]]; then echo '{"test":"report"}' > "$2"; break; fi
  shift
done
[[ "$TEST_CASE" != codex_failed ]] || exit 9
''')
            stub('python3', '''script="$1"; shift
case "$script" in
  *validate_agent_review.py)
    if [[ "$TEST_CASE" == invalid ]]; then echo 'INVALID REPORT' >&2; exit 7; fi ;;
  *format_agent_review_comment.py) echo 'VALIDATED TEST COMMENT' ;;
  *) while [[ "$#" -gt 0 ]]; do
       if [[ "$1" == --output ]]; then echo '{}' > "$2"; break; fi
       shift
     done ;;
esac
''')
            if case != 'missing_gh':
                stub('gh', '''if [[ "$2" == view ]]; then
  [[ "$TEST_CASE" != no_pr ]] || exit 1
  echo 689
else
  echo attempted > publication-attempted
  [[ "$TEST_CASE" != publication_failed ]] || exit 1
  echo published > publication-success
fi
''')
            env = {**os.environ, 'PATH': str(binaries), 'TEST_CASE': case}
            env.pop('PR_NUMBER', None)
            result = subprocess.run(['/bin/bash', str(scripts / RUNNER.name)],
                                    cwd=root, env=env, capture_output=True, text=True)
            evidence = list((root / '.git/agent-review-reports').glob('review-*'))
            self.assertEqual(len(evidence), 1)
            files = {p.name: p.read_text() for p in evidence[0].iterdir()}
            self.assertIn('report.json', files)
            self.assertIn('route.json', files)
            self.assertIn('ci-evidence.json', files)
            self.assertIn(str(evidence[0]), result.stderr)
            if case in ('invalid', 'codex_failed'):
                self.assertEqual(result.returncode, 7 if case == 'invalid' else 9)
                self.assertNotIn('comment.md', files)
                self.assertNotIn('Rapport local validé', result.stdout)
                self.assertFalse((root / 'publication-attempted').exists())
            else:
                self.assertIn('comment.md', files)
                self.assertEqual(result.returncode, 1 if case == 'publication_failed' else 0)
                self.assertEqual((root / 'publication-success').exists(), case == 'success')
                if case != 'success':
                    self.assertIn('non publié', result.stdout + result.stderr)
                    self.assertNotIn('Rapport publié sur', result.stdout)
            return result

    def test_success(self):
        self.run_case('success')

    def test_missing_gh(self):
        self.run_case('missing_gh')

    def test_missing_pr(self):
        self.run_case('no_pr')

    def test_failed_publication(self):
        self.run_case('publication_failed')

    def test_codex_failure_preserves_failure_and_does_not_publish(self):
        self.run_case('codex_failed')

    def test_invalid_report_stays_invalid_and_unpublished(self):
        self.run_case('invalid')


if __name__ == '__main__':
    unittest.main()
