"""检查缺失证据、范围污染与质量空分母不能被隐藏。"""

import copy
import json
import tempfile
import unittest
from pathlib import Path

from evaluate import ROOT, evaluate
from materialize import REPOSITORY, materialize


class EvaluationTests(unittest.TestCase):
    def setUp(self):
        self.suite = json.loads((ROOT / 'suite.json').read_text(encoding='utf-8'))
        self.run = {'mode': 'mock', 'runId': '固定测试', 'implementation': '测试快照',
                    'evidence': '测试原始观察', 'results': []}

    def test_missing_and_empty_expected_stay_unknown(self):
        report = evaluate(self.suite, self.run)
        self.assertTrue(all(row['metrics']['recall'] is None for row in report['cases']))
        self.run['results'] = [{'caseId': 'no-answer', 'retrievedUnitIds': [], 'outcome': 'no_answer'}]
        row = next(row for row in evaluate(self.suite, self.run)['cases'] if row['id'] == 'no-answer')
        self.assertIsNone(row['metrics']['recall'])
        self.assertIsNone(row['metrics']['accuracy'])
        self.assertTrue(row['metrics']['outcomeMatch'])

    def test_ranking_and_scope_are_separate(self):
        self.run['results'] = [{'caseId': 'term', 'retrievedUnitIds': ['other-rule', 'term-rule']}]
        row = evaluate(self.suite, self.run)['cases'][0]
        self.assertEqual(row['metrics']['recall'], 1)
        self.assertEqual(row['metrics']['reciprocalRank'], 0.5)
        self.assertEqual(row['metrics']['scopeLeakCount'], 1)

    def test_rejects_false_quality_and_unauthorized_real(self):
        for entry in ({'passed': 0, 'total': 0, 'evidence': '人工复核'},
                      {'passed': 2, 'total': 1, 'evidence': '人工复核'}):
            run = copy.deepcopy(self.run)
            run['results'] = [{'caseId': 'term', 'reviewer': '测试复核者', 'review': {'accuracy': entry}}]
            with self.assertRaises(ValueError):
                evaluate(self.suite, run)
        self.run['mode'] = 'real'
        with self.assertRaises(ValueError):
            evaluate(self.suite, self.run)

    def test_materialization_preserves_pdf_and_markdown_table(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / '资料'
            materialize(self.suite, output)
            self.assertEqual((output / 'maintenance.pdf').read_bytes(),
                             (REPOSITORY / 'server/src/test/resources/documents/chinese-cross-page.pdf').read_bytes())
            self.assertIn('| 过滤芯 | 80元 |', (output / 'manual.md').read_text(encoding='utf-8'))
            with self.assertRaises(FileExistsError):
                materialize(self.suite, output)

    def test_rejects_non_object_and_wrong_field_types(self):
        for suite, run in (([], self.run), (self.suite, [])):
            with self.assertRaisesRegex(ValueError, '对象'):
                evaluate(suite, run)
        for result in ('无效结果', {'caseId': []}, {'caseId': 'term', 'outcome': []}):
            run = copy.deepcopy(self.run)
            run['results'] = [result]
            with self.assertRaises(ValueError):
                evaluate(self.suite, run)
        run = copy.deepcopy(self.run)
        run.update(mode='real', authorization=123)
        with self.assertRaisesRegex(ValueError, '授权'):
            evaluate(self.suite, run)


if __name__ == '__main__':
    unittest.main()
