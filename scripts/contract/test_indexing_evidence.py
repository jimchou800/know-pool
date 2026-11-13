"""核对索引证据只记录真实事件及成功测试明确声明的行为。"""

import io
import json
import runpy
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import verify


class IndexingEvidenceTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.directory = tempfile.TemporaryDirectory()
        root = Path(cls.directory.name)
        for name in ('scripts/contract/verify.py', 'docs/agents/api/openapi.yaml'):
            target = root / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes((verify.ROOT / name).read_bytes())
        script = verify.ROOT / 'server/src/test/resources/contract/settings-evidence.py'
        dependencies = [str(candidate) for candidate in (
            verify.ROOT / '.runtime/contract-tools', verify.ROOT.parent.parent / '.runtime/contract-tools')
            if candidate.exists()]
        with patch('sys.path', dependencies + sys.path), patch('sys.argv', [str(script), str(root), 'indexing']), patch('sys.stdin', io.StringIO(
                json.dumps([{'path': '/outside', 'method': 'GET', 'routes': []}]))):
            cls.module = runpy.run_path(str(script))

    @classmethod
    def tearDownClass(cls):
        cls.directory.cleanup()

    def event(self, state='succeeded', event='terminal'):
        task = '00000000-0000-4000-8000-000000000003'
        data = {'taskId': task, 'sequence': 2, 'state': state,
                'phase': 'completed' if state == 'succeeded' else 'parsing',
                'occurredAt': '2026-11-13T00:00:00Z'}
        return {'path': '/api/v1/tasks/' + task + '/events', 'contentType': 'text/event-stream',
                'response': ': 心跳\n\nid:' + task + ':2\nevent:' + event + '\ndata:' + json.dumps(data) + '\n\n'}

    def test_event_frames_reject_wrong_identity_body_and_terminal(self):
        prefix = '#/paths/~1tasks~1{taskId}~1events/get'
        check = self.module['event_constraints']
        found = check(self.event(), prefix)
        self.assertIn(prefix + '/x-sse/events/terminal', found)
        self.assertNotIn(prefix + '/x-sse/events/phase', found)
        self.assertNotIn(prefix + '/x-sse/reconnect', found)
        self.assertIn(prefix + '/x-sse/events/phase', check(self.event('running', 'phase'), prefix))
        for item in (self.event('running', 'terminal'),
                     {**self.event(), 'contentType': 'application/json'},
                     {**self.event(), 'response': self.event()['response'].replace(':2\n', ':3\n')},
                     {**self.event(), 'response': ': 只有心跳\n\n'},
                     {**self.event(), 'response': self.event()['response'] + self.event()['response']}):
            with self.assertRaises((AssertionError, ValueError)):
                check(item, prefix)

    def test_behavior_requires_successful_named_test_and_explicit_check(self):
        check = self.module['indexing_assertions']
        item = {'behavior': 'textIndexBecomesEffective', 'behaviorChecks': ['currentIndexVersions']}
        self.assertIn('#/x-business-constraints/indexSwitch', check(item, 'getTask')[0])
        self.assertEqual(check({**item, 'behavior': ''}, 'getTask'), (set(), []))
        self.assertEqual(check({'behavior': 'textIndexBecomesEffective'}, 'getTask'), (set(), []))
        self.assertEqual(check(item, 'getSystemStatus'), (set(), []))
        with self.assertRaisesRegex(AssertionError, '未声明'):
            check({**item, 'behaviorChecks': ['everythingPassed']}, 'getTask')
        description = self.module['behavior_assertions']
        observed = {'behavior': 'everyOperationRejectsUnsafeRequests'}
        self.assertTrue(description(observed, 'testKnowledgeBaseSearch'))
        self.assertEqual(description({**observed, 'behavior': ''}, 'testKnowledgeBaseSearch'), [])
        self.assertEqual(check(observed, 'testKnowledgeBaseSearch'), (set(), []))
        self.assertTrue(description({'behavior': 'textIndexBecomesEffective'}, 'listKnowledgeBaseTasks'))
        self.assertEqual(check({'behavior': 'textIndexBecomesEffective'}, 'listKnowledgeBaseTasks'), (set(), []))
        retry = {'behavior': 'textIndexBecomesEffective', 'behaviorChecks': ['indexRetriesAreBounded']}
        self.assertEqual(check(retry, 'getTask')[0], set())
        recovery = {'behavior': 'persistedEmbeddingResumesWithoutModelCall',
                    'behaviorChecks': ['persistedEmbeddingNoRepeat']}
        self.assertEqual(check(recovery, 'getTask')[0], {'#/x-business-constraints/cost'})
        self.assertEqual(check(recovery, 'listKnowledgeBaseFiles'), (set(), []))
        self.assertEqual(check({**recovery, 'behavior': 'textIndexBecomesEffective'}, 'getTask'), (set(), []))

    def test_profile_records_observed_sse_without_body_or_unclaimed_behavior(self):
        root = Path(self.directory.name)
        script = verify.ROOT / 'server/src/test/resources/contract/settings-evidence.py'
        item = {**self.event(), 'method': 'GET', 'status': 200, 'body': None,
                'parameters': {}, 'routes': [], 'behavior': 'terminalSnapshotCanBeResumed'}
        with patch('sys.argv', [str(script), str(root), 'indexing']), patch('sys.stdin', io.StringIO(json.dumps([item]))):
            runpy.run_path(str(script))
        report = json.loads((root / 'server/target/contract/provider-indexing-evidence.json').read_bytes())
        self.assertEqual(len(report['tests']), 1)
        test = report['tests'][0]
        self.assertEqual(test['operationIds'], ['watchTask'])
        self.assertNotIn('security.8', test['constraintIds'])
        self.assertNotIn('#/x-business-constraints/sse', test['constraintIds'])
        self.assertNotIn('data:', json.dumps(report))

    def test_multipart_observation_does_not_claim_json_request_constraints(self):
        root = Path(self.directory.name)
        script = verify.ROOT / 'server/src/test/resources/contract/settings-evidence.py'
        item = {'path': '/api/v1/datasets/00000000-0000-4000-8000-000000000003/files:upload',
                'method': 'POST', 'status': 400, 'contentType': 'application/json', 'body': None,
                'parameters': {}, 'routes': [], 'behavior': '',
                'response': {'code': 'INVALID_REQUEST', 'message': '固定拒绝', 'retryable': False,
                             'requestId': '00000000-0000-4000-8000-000000000004', 'fieldErrors': []}}
        with patch('sys.argv', [str(script), str(root), 'indexing']), patch('sys.stdin', io.StringIO(json.dumps([item]))):
            runpy.run_path(str(script))
        report = json.loads((root / 'server/target/contract/provider-indexing-evidence.json').read_bytes())
        self.assertEqual(report['tests'][0]['operationIds'], ['uploadFiles'])
        self.assertFalse(any('/requestBody' in pointer for pointer in report['tests'][0]['constraintIds']))

    def test_event_profile_preserves_indexing_output_and_does_not_invent_capacity_pointer(self):
        root = Path(self.directory.name)
        script = verify.ROOT / 'server/src/test/resources/contract/settings-evidence.py'
        original = root / 'server/target/contract/provider-indexing-evidence.json'
        original.write_bytes(b'original-indexing-evidence')
        item = {**self.event(), 'method': 'GET', 'status': 200, 'body': None,
                'parameters': {}, 'routes': [],
                'behavior': 'boundedSubscriptionsReleaseOnDisconnectAndEmitHeartbeat',
                'behaviorChecks': ['eventCapacity']}
        with patch('sys.argv', [str(script), str(root), 'indexing-events']), patch('sys.stdin', io.StringIO(json.dumps([item]))):
            runpy.run_path(str(script))
        report = json.loads((root / 'server/target/contract/provider-indexing-events-evidence.json').read_bytes())
        self.assertEqual(original.read_bytes(), b'original-indexing-evidence')
        test = report['tests'][0]
        self.assertIn('真实事件请求', test['name'])
        self.assertTrue(any('容量' in note for note in test['assertions']))
        self.assertFalse(any('eventCapacity' in pointer for pointer in test['constraintIds']))
        self.assertNotIn('#/paths/~1tasks~1{taskId}~1events/get/responses/429', test['constraintIds'])


if __name__ == '__main__':
    unittest.main()
