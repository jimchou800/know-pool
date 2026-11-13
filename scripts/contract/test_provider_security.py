"""校验安全证据只接受真实成功路径、完整前提和预期拒绝结果。"""

import json
import tempfile
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path
from unittest.mock import Mock, patch

import provider_security
import verify


class ProviderSecurityTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.document = json.loads(verify.CONTRACT.read_bytes())

    def report(self):
        return {'routes': [{'method': 'GET', 'path': '/api/v1/datasets/{datasetId}'}], 'tests': [{
            'name': '固定真实成功观察', 'passed': True, 'operationIds': ['getDataset'],
            'constraintIds': [], 'request': {'method': 'GET', 'path': '/datasets/00000000-0000-4000-8000-000000000001'},
            'response': {'status': 200},
        }]}

    def test_only_successful_registered_path_is_selected(self):
        """正常路径保留原证据身份，不从操作清单自动造通过。"""
        with patch.object(verify, 'SCOPE', ('getDataset',)):
            proven, routes = provider_security.collect_routes(self.document, [('原始证据.json', self.report())])
        self.assertEqual(set(proven), {'getDataset'})
        self.assertEqual(proven['getDataset']['success'], {'file': '原始证据.json', 'test': '固定真实成功观察', 'status': 200})
        self.assertEqual(proven['getDataset']['path'], '/api/v1/datasets/00000000-0000-4000-8000-000000000001')
        self.assertEqual(len(routes), 1)

    def test_missing_registration_failure_or_wrong_operation_cannot_prove_route(self):
        """未注册、非成功或伪造操作身份均不能生成安全映射。"""
        for mutation in ('registration', 'failure', 'operation', 'missing'):
            with self.subTest(mutation=mutation), patch.object(verify, 'SCOPE', ('getDataset',)):
                report = self.report()
                if mutation == 'registration':
                    report['routes'] = []
                elif mutation == 'failure':
                    report['tests'][0]['response']['status'] = 404
                elif mutation == 'operation':
                    report['tests'][0]['operationIds'] = ['listDatasets']
                else:
                    report['tests'] = []
                with self.assertRaises(ValueError):
                    provider_security.collect_routes(self.document, [('原始证据.json', report)])

    def test_sensitive_or_external_request_path_is_rejected(self):
        """不允许令牌查询参数或外部地址进入安全重放。"""
        for path in ('https://example.invalid/api/v1/datasets', '/datasets?csrf=固定值',
                     '/datasets?sessionId=固定值', '/datasets#片段'):
            with self.subTest(path=path), self.assertRaises(ValueError):
                provider_security.request_path(path)

    def make_prerequisites(self, root):
        for classname, names in provider_security.REQUIRED_TESTS.items():
            suite = ET.Element('testsuite', name=classname, failures='0', errors='0', skipped='0', flakes='0')
            for name in names:
                ET.SubElement(suite, 'testcase', name=name, classname=classname)
            path = root / 'server/target/surefire-reports' / ('TEST-' + classname + '.xml')
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(ET.tostring(suite))
        return [('原始证据.json', {'tests': [{'name': '固定安全前提', 'passed': True,
                 'constraintIds': ['security.' + str(index) for index in range(1, 9)]}]})]

    def test_shared_prerequisites_require_every_named_test_without_skip_or_retry(self):
        """缺项、跳过、重跑及行为缺证据均使共同前提失败。"""
        for mutation in ('none', 'missing', 'skip', 'retry', 'behavior'):
            with self.subTest(mutation=mutation), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                reports = self.make_prerequisites(root)
                path = root / 'server/target/surefire-reports/TEST-cn.knowpool.TaskEventsHttpTest.xml'
                suite = ET.fromstring(path.read_bytes())
                if mutation == 'missing':
                    suite.remove(suite.find('testcase'))
                elif mutation == 'skip':
                    ET.SubElement(suite.find('testcase'), 'skipped')
                elif mutation == 'retry':
                    ET.SubElement(suite.find('testcase'), 'flakyFailure')
                elif mutation == 'behavior':
                    reports[0][1]['tests'][0]['constraintIds'].remove('security.8')
                path.write_bytes(ET.tostring(suite))
                if mutation == 'none':
                    self.assertEqual(len(provider_security.prerequisites(root, reports)['junit']), 8)
                else:
                    with self.assertRaises(ValueError):
                        provider_security.prerequisites(root, reports)

    def test_rejection_must_match_status_code_and_request_identity(self):
        """状态、错误码或请求身份错误不能报告拒绝成功。"""
        headers = {'content-type': 'application/json', 'cache-control': 'no-store',
                   'x-content-type-options': 'nosniff', 'x-request-id': '固定请求身份'}
        body = {'code': 'SESSION_EXPIRED', 'requestId': '固定请求身份'}
        for mutation in ('none', 'status', 'code', 'identity'):
            actual_headers, actual_body = dict(headers), dict(body)
            status = 403 if mutation == 'status' else 401
            if mutation == 'code':
                actual_body['code'] = 'NOT_FOUND'
            elif mutation == 'identity':
                actual_headers['x-request-id'] = '其他身份'
            with self.subTest(mutation=mutation), patch.object(provider_security, 'exchange', return_value=(status, actual_headers, actual_body)):
                if mutation == 'none':
                    result = provider_security.reject('http://127.0.0.1:1', 'GET', '/api/v1/datasets', {}, 401, 'SESSION_EXPIRED', Mock())
                    self.assertEqual(result, {'status': 401, 'code': 'SESSION_EXPIRED'})
                else:
                    with self.assertRaises(ValueError):
                        provider_security.reject('http://127.0.0.1:1', 'GET', '/api/v1/datasets', {}, 401, 'SESSION_EXPIRED', Mock())


if __name__ == '__main__':
    unittest.main()
