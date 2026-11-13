"""验证契约门禁拒绝缺项、过期和伪通过证据。"""

import copy
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import verify


class ContractGateTests(unittest.TestCase):
    def test_merges_provider_files_and_rejects_duplicate_or_stale_evidence(self):
        """独立证据均须有效，跨文件重名和过期不得被合并掩盖。"""
        with tempfile.TemporaryDirectory() as directory:
            paths = [Path(directory) / name for name in ('files.json', 'settings.json')]
            def write(index, name, implementation='实现'):
                paths[index].write_text(json.dumps({
                    'contractSha256': '契约', 'implementationSha256': implementation,
                    'tests': [{'name': name, 'passed': True, 'operationIds': ['getTask'], 'constraintIds': []}],
                    'routes': [{'method': 'GET', 'path': '/api/v1/tasks/{taskId}'}],
                }), encoding='utf-8')
            write(0, '资料'); write(1, '设置')
            merged = verify.read_evidence_files(paths, '契约', '实现')
            self.assertEqual([test['name'] for test in merged['tests']], ['资料', '设置'])
            self.assertEqual(len(merged['routes']), 1)
            write(1, '资料')
            with self.assertRaisesRegex(ValueError, '重复'):
                verify.read_evidence_files(paths, '契约', '实现')
            write(1, '设置', '过期')
            with self.assertRaisesRegex(ValueError, '过期'):
                verify.read_evidence_files(paths, '契约', '实现')
            paths[1].unlink()
            with self.assertRaises(FileNotFoundError):
                verify.read_evidence_files(paths, '契约', '实现')

    def setUp(self):
        self.document = json.loads(verify.CONTRACT.read_text(encoding='utf-8'))

    def test_rejects_empty_contract(self):
        """拒绝空契约。"""
        with self.assertRaisesRegex(ValueError, '空契约'):
            verify.inventory({'paths': {}})

    def test_scope_preserves_constraints_and_requires_both_approvals(self):
        """适用映射保留原约束并要求双方批准。"""
        mapping = json.loads((verify.ROOT / f'scripts/contract/scope-{verify.TICKET}.json').read_text(encoding='utf-8'))
        operations = verify.inventory(self.document)
        self.assertEqual(set(mapping['操作']), set(verify.SCOPE))
        for operation, conditions in mapping['操作'].items():
            self.assertTrue(set(conditions).issubset(operations[operation]['constraints']))
        self.assertEqual(len(mapping['当前安全行为']), 7)
        self.assertIn('SSE', mapping['后续保留'][0])
        with tempfile.TemporaryDirectory() as directory:
            with patch.object(verify, 'ROOT', Path(directory)):
                with self.assertRaises(FileNotFoundError):
                    verify.scope_mapping('契约')

    def test_rejects_duplicate_operations(self):
        """拒绝重复操作。"""
        self.document['paths']['/重复'] = copy.deepcopy(self.document['paths']['/session'])
        with self.assertRaisesRegex(ValueError, '操作标识重复'):
            verify.inventory(self.document)

    def test_rejects_missing_references(self):
        """拒绝缺失引用。"""
        self.document['paths']['/session']['get']['responses']['200']['content']['application/json']['schema']['$ref'] = '#/components/schemas/不存在'
        with self.assertRaises(KeyError):
            verify.inventory(self.document)

    def test_inventory_includes_shared_constraints(self):
        """分母来自契约并包含共享约束。"""
        operations = verify.inventory(self.document)
        self.assertEqual(len(operations), 95)
        self.assertIn('#/components/schemas/Session/properties/csrfToken/minLength',
                      operations['initializeSession']['constraints'])
        self.assertIn('#/paths/~1system~1status/get/responses/401',
                      operations['getSystemStatus']['constraints'])

    def test_unmapped_claim_does_not_count_as_coverage(self):
        """无约束映射的通过声明不计覆盖。"""
        self.assertEqual(verify.coverage({'tests': [{'name': '空声明', 'passed': True,
                         'operationIds': ['initializeSession']}]}, 'initializeSession', '约束'), [])

    def test_partial_scope_is_not_complete_operation(self):
        """当前适用项全过也不能把后续保留项算作完整操作通过。"""
        constraints = [{'状态': '通过'}, {'状态': '条件未触发或后续保留'}]
        self.assertFalse(verify.operation_complete(constraints))
        self.assertTrue(verify.operation_complete([{'状态': '通过'}]))
        self.assertFalse(verify.operation_complete([]))

    def test_rejects_extra_or_missing_routes(self):
        """拒绝额外公开接口和缺失路由。"""
        routes = [{'method': operation['method'], 'path': '/api/v1' + operation['path']}
                  for identifier, operation in verify.inventory(self.document).items() if identifier in verify.SCOPE]
        verify.check_routes(self.document, routes)
        for invalid in ([], routes[:1], routes + [{'method': 'POST', 'path': '/api/v1/未声明'}]):
            with self.assertRaises(ValueError):
                verify.check_routes(self.document, invalid)

    def test_failed_or_unrelated_tests_do_not_count_as_coverage(self):
        """其他操作或失败用例不计覆盖。"""
        evidence = {'tests': [{'name': '失败例', 'passed': False,
                              'operationIds': ['initializeSession'], 'constraintIds': ['约束']}]}
        self.assertEqual(verify.coverage(evidence, 'initializeSession', '约束'), [])
        evidence['tests'][0]['passed'] = True
        self.assertEqual(verify.coverage(evidence, 'getSystemStatus', '约束'), [])
        self.assertEqual(verify.coverage(evidence, 'initializeSession', '约束'), ['失败例'])

    def test_rejects_stale_evidence_and_empty_tests(self):
        """拒绝过期契约、实现和空测试。"""
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'evidence.json'
            for evidence in ({'contractSha256': '旧'},
                             {'contractSha256': '契约', 'implementationSha256': '旧'},
                             {'contractSha256': '契约', 'implementationSha256': '实现', 'tests': []}):
                path.write_text(json.dumps(evidence), encoding='utf-8')
                with self.assertRaises(ValueError):
                    verify.read_evidence(path, '契约', '实现')

    def test_rejects_malformed_evidence(self):
        """畸形证据按验证错误拒绝。"""
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'evidence.json'
            for tests in ([True], [{'passed': True}],
                          [{'name': '缺映射', 'passed': True}],
                          [{'name': '错映射', 'passed': True, 'operationIds': '操作', 'constraintIds': []}]):
                path.write_text(json.dumps({'contractSha256': '契约',
                                'implementationSha256': '实现', 'tests': tests}), encoding='utf-8')
                with self.assertRaises(ValueError):
                    verify.read_evidence(path, '契约', '实现')

    def test_rejects_failed_report_acceptance(self):
        """失败报告不能验收。"""
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'report.json'
            path.write_text(json.dumps({'失败': ['未验证']}), encoding='utf-8')
            with self.assertRaisesRegex(ValueError, '不能验收'):
                verify.acceptance(path)

    def test_mapping_cannot_hide_failed_or_skipped_tests(self):
        """映射不能覆盖原始测试失败或跳过。"""
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            frontend = root / 'front/artifacts/vitest.json'
            frontend.parent.mkdir(parents=True)
            backend = root / 'server/target/surefire-reports/TEST-例子.xml'
            backend.parent.mkdir(parents=True)
            success = {'numFailedTests': 0, 'numFailedTestSuites': 0,
                       'numPassedTests': 1, 'success': True}
            frontend.write_text(json.dumps(success), encoding='utf-8')
            with patch.object(verify, 'ROOT', root):
                for result in ('failures="1"', 'errors="1"', 'skipped="1"', 'tests="0"'):
                    backend.write_text('<testsuite ' + result + '/>', encoding='utf-8')
                    with self.assertRaises(ValueError):
                        verify.check_test_results()
                backend.write_text('<testsuite tests="1" failures="0" errors="0" skipped="0"/>', encoding='utf-8')
                self.assertEqual(verify.check_test_results()['后端通过测试'], 1)
                frontend.write_text(json.dumps({**success, 'numFailedTests': 1}), encoding='utf-8')
                with self.assertRaises(ValueError):
                    verify.check_test_results()

    def test_acceptance_requires_independent_approvals_and_preserves_report(self):
        """拒绝漏签或代签，并保持原报告不变。"""
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            contract = root / 'contract.json'
            contract.write_bytes(b'{}')
            path = root / 'run' / 'report.json'
            path.parent.mkdir()
            report = {'失败': [], '结论': '校验通过，仍待双方独立验收',
                      '实现快照': '实现', '契约SHA256': verify.digest(b'{}')}
            path.write_text(json.dumps(report), encoding='utf-8')
            original = path.read_bytes()
            reviews = root / 'docs/agents/api/reviews'
            reviews.mkdir(parents=True)
            approval = {'actor': '/root/同一代理', 'conclusion': '通过',
                        'contractSha256': report['契约SHA256'], 'implementationSha256': '实现',
                        'reportSha256': verify.digest(original), 'evidence': '实际审阅依据'}
            with patch.object(verify, 'ROOT', root), patch.object(verify, 'CONTRACT', contract), patch.object(verify, 'snapshot', return_value=('实现', [])):
                for role in ('frontend', 'backend'):
                    approval['role'] = role
                    (reviews / f'run-{role}.json').write_text(json.dumps(approval), encoding='utf-8')
                    if role == 'frontend':
                        with self.assertRaises(FileNotFoundError):
                            verify.acceptance(path)
                with self.assertRaisesRegex(ValueError, '同一代理'):
                    verify.acceptance(path)
                approval['actor'] = '/root/独立后端'
                (reviews / 'run-backend.json').write_text(json.dumps(approval), encoding='utf-8')
                self.assertIn('双方独立签认', verify.acceptance(path))
                self.assertEqual(path.read_bytes(), original)

    def test_rejects_stale_report(self):
        """拒绝过期报告。"""
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'report.json'
            path.write_text(json.dumps({'失败': [], '结论': '校验通过，仍待双方独立验收',
                                      '实现快照': '过期实现'}), encoding='utf-8')
            with patch.object(verify, 'snapshot', return_value=('最新实现', [])):
                with self.assertRaisesRegex(ValueError, '报告已过期'):
                    verify.acceptance(path)


if __name__ == '__main__':
    unittest.main()
