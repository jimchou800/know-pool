"""验证契约门禁拒绝缺项、过期和伪通过证据。"""

import copy
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import verify


class ContractGateTests(unittest.TestCase):
    def setUp(self):
        self.document = json.loads(verify.CONTRACT.read_text(encoding='utf-8'))

    def test_空契约拒绝(self):
        with self.assertRaisesRegex(ValueError, '空契约'):
            verify.inventory({'paths': {}})

    def test_适用映射不得删除原约束或单方批准(self):
        mapping = json.loads((verify.ROOT / 'scripts/contract/scope-01.json').read_text(encoding='utf-8'))
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

    def test_重复操作拒绝(self):
        self.document['paths']['/重复'] = copy.deepcopy(self.document['paths']['/session'])
        with self.assertRaisesRegex(ValueError, '操作标识重复'):
            verify.inventory(self.document)

    def test_缺失引用拒绝(self):
        self.document['paths']['/session']['get']['responses']['200']['content']['application/json']['schema']['$ref'] = '#/components/schemas/不存在'
        with self.assertRaises(KeyError):
            verify.inventory(self.document)

    def test_分母来自契约且包含共享约束(self):
        operations = verify.inventory(self.document)
        self.assertEqual(len(operations), 95)
        self.assertIn('#/components/schemas/Session/properties/csrfToken/minLength',
                      operations['initializeSession']['constraints'])
        self.assertIn('#/paths/~1system~1status/get/responses/401',
                      operations['getSystemStatus']['constraints'])

    def test_自称通过但无约束映射不计覆盖(self):
        self.assertEqual(verify.coverage({'tests': [{'name': '空声明', 'passed': True,
                         'operationIds': ['initializeSession']}]}, 'initializeSession', '约束'), [])

    def test_额外公开接口缺失路由拒绝(self):
        routes = [{'method': 'GET', 'path': '/api/v1/session'},
                  {'method': 'GET', 'path': '/api/v1/system/status'}]
        verify.check_routes(self.document, routes)
        for invalid in ([], routes[:1], routes + [{'method': 'POST', 'path': '/api/v1/未声明'}]):
            with self.assertRaises(ValueError):
                verify.check_routes(self.document, invalid)

    def test_另一操作或失败例不计覆盖(self):
        evidence = {'tests': [{'name': '失败例', 'passed': False,
                              'operationIds': ['initializeSession'], 'constraintIds': ['约束']}]}
        self.assertEqual(verify.coverage(evidence, 'initializeSession', '约束'), [])
        evidence['tests'][0]['passed'] = True
        self.assertEqual(verify.coverage(evidence, 'getSystemStatus', '约束'), [])
        self.assertEqual(verify.coverage(evidence, 'initializeSession', '约束'), ['失败例'])

    def test_过期契约实现和空测试拒绝(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'evidence.json'
            for evidence in ({'contractSha256': '旧'},
                             {'contractSha256': '契约', 'implementationSha256': '旧'},
                             {'contractSha256': '契约', 'implementationSha256': '实现', 'tests': []}):
                path.write_text(json.dumps(evidence), encoding='utf-8')
                with self.assertRaises(ValueError):
                    verify.read_evidence(path, '契约', '实现')

    def test_畸形证据拒绝而非未捕获异常(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'evidence.json'
            for tests in ([True], [{'passed': True}],
                          [{'name': '缺映射', 'passed': True}],
                          [{'name': '错映射', 'passed': True, 'operationIds': '操作', 'constraintIds': []}]):
                path.write_text(json.dumps({'contractSha256': '契约',
                                'implementationSha256': '实现', 'tests': tests}), encoding='utf-8')
                with self.assertRaises(ValueError):
                    verify.read_evidence(path, '契约', '实现')

    def test_失败报告拒绝验收(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'report.json'
            path.write_text(json.dumps({'失败': ['未验证']}), encoding='utf-8')
            with self.assertRaisesRegex(ValueError, '不能验收'):
                verify.acceptance(path)

    def test_原始测试失败或跳过不得由映射覆盖(self):
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

    def test_缺单方或同代理代签拒绝且不修改原报告(self):
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

    def test_报告过期拒绝(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'report.json'
            path.write_text(json.dumps({'失败': [], '结论': '校验通过，仍待双方独立验收',
                                      '实现快照': '过期实现'}), encoding='utf-8')
            with patch.object(verify, 'snapshot', return_value=('最新实现', [])):
                with self.assertRaisesRegex(ValueError, '报告已过期'):
                    verify.acceptance(path)


if __name__ == '__main__':
    unittest.main()
