"""验证契约门禁拒绝缺项、过期和伪通过证据。"""

import copy
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import verify


class ContractGateTests(unittest.TestCase):
    def test_snapshot_ignores_runtime_logs_but_tracks_source_changes(self):
        """运行日志增写不改变实现快照，源码变化仍使证据失效。"""
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / 'server/src/Application.java'
            source.parent.mkdir(parents=True)
            source.write_text('class Application {}', encoding='utf-8')
            log = root / 'server/.runtime/logs/application.log'
            log.parent.mkdir(parents=True)
            log.write_text('首次启动', encoding='utf-8')
            with patch.object(verify, 'ROOT', root):
                initial, files = verify.snapshot()
                self.assertEqual([item['path'] for item in files], ['server/src/Application.java'])
                with log.open('a', encoding='utf-8') as output:
                    output.write('后端测试完成')
                self.assertEqual(verify.snapshot()[0], initial)
                source.write_text('class Application { int version; }', encoding='utf-8')
                self.assertNotEqual(verify.snapshot()[0], initial)

    def test_merges_provider_files_and_rejects_duplicate_or_stale_evidence(self):
        """独立证据均须有效，跨文件重名和过期不得被合并掩盖。"""
        with tempfile.TemporaryDirectory() as directory:
            paths = [Path(directory) / name for name in ('files.json', 'settings.json', 'knowledge-bases.json')]
            def write(index, name, implementation='实现'):
                paths[index].write_text(json.dumps({
                    'contractSha256': '契约', 'implementationSha256': implementation,
                    'tests': [{'name': name, 'passed': True, 'operationIds': ['getTask'], 'constraintIds': []}],
                    'routes': [{'method': 'GET', 'path': '/api/v1/tasks/{taskId}'}],
                }), encoding='utf-8')
            write(0, '资料'); write(1, '设置'); write(2, '知识库')
            merged = verify.read_evidence_files(paths, '契约', '实现')
            self.assertEqual([test['name'] for test in merged['tests']], ['资料', '设置', '知识库'])
            self.assertEqual(len(merged['routes']), 1)
            write(1, '资料')
            with self.assertRaisesRegex(ValueError, '重复'):
                verify.read_evidence_files(paths, '契约', '实现')
            write(1, '设置', '过期')
            with self.assertRaisesRegex(ValueError, '过期'):
                verify.read_evidence_files(paths, '契约', '实现')
            write(1, '设置')
            paths[2].unlink()
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
        mapping = json.loads((verify.ROOT / 'scripts/contract/scope-07.json').read_text(encoding='utf-8'))
        operations = verify.inventory(self.document)
        self.assertEqual(set(mapping['操作']), set(verify.SCOPE[:35]))
        for operation, conditions in mapping['操作'].items():
            self.assertTrue(set(conditions).issubset(operations[operation]['constraints']))
        self.assertEqual(len(mapping['当前安全行为']), 7)
        self.assertIn('SSE', mapping['后续保留'][0])
        with tempfile.TemporaryDirectory() as directory:
            with patch.object(verify, 'ROOT', Path(directory)):
                with self.assertRaises(FileNotFoundError):
                    verify.scope_mapping('契约')

    def test_knowledge_base_scope_keeps_live_resource_and_task_branches(self):
        """创建成功资源与实际任务分支不得沿用连接测试豁免。"""
        mapping = json.loads((verify.ROOT / 'scripts/contract/scope-07.json').read_bytes())
        self.assertTrue(set(verify.SCOPE[27:35]).issubset(mapping['操作']))
        for field in ('knowledgeBaseId', 'resultResourceId', 'fileId', 'targetContentVersionId'):
            self.assertNotIn('#/components/schemas/Task/properties/' + field + '/anyOf/0/type',
                             mapping['操作']['getTask'])
        self.assertFalse(any(pointer.startswith('#/components/schemas/Task/allOf/2/then')
                             for pointer in mapping['操作']['getTask']))
        self.assertIn('#/components/schemas/Task/allOf/2/then',
                      mapping['操作']['testVectorConfig'])

    def test_indexing_inventory_adds_exact_sse_and_business_obligations(self):
        """事件语义及直接所属业务规则新增为证据要求，不扩散到无关操作。"""
        operations = verify.inventory(self.document)
        self.assertEqual(verify.TICKET, '23')
        self.assertEqual(len(verify.SCOPE), 95)
        self.assertEqual(len(set(verify.SCOPE)), 95)
        prefix = '#/paths/~1tasks~1{taskId}~1events/get/x-sse/'
        for suffix in ('events/phase', 'events/terminal', 'id', 'lastEventIdHeader', 'terminalStates', 'reconnect'):
            pointer = prefix + suffix
            self.assertIn(pointer, operations['watchTask']['constraints'])
            self.assertEqual(verify.coverage({'tests': []}, 'watchTask', pointer), [])
        rules = {
            'maintenance': {'migrationPreflight', 'submitMigration', 'getMaintenanceTask', 'retryMaintenanceTask', 'backupPreflight', 'submitBackup', 'restorePreflight', 'submitRestore'},
            'indexSwitch': {'updateRuntimeSettings', 'getTask', 'listKnowledgeBaseFiles', 'testKnowledgeBaseSearch', 'getSearchRequest', 'getSearchEvidence', 'updateProcessingConfig', 'submitSearch', 'submitFirstQuestion', 'getQuestionTask', 'getConversationEvidence', 'submitQuestion', 'listConversationTurns'},
            'publication': {'testKnowledgeBaseSearch', 'getKnowledgeBase', 'publishKnowledgeBase', 'offlineKnowledgeBase', 'checkKnowledgeBase', 'submitSearch', 'submitFirstQuestion', 'submitQuestion', 'deleteDataset', 'deleteFile', 'deleteKnowledgeBase'},
            'cost': {'updateRuntimeSettings', 'getTask', 'testKnowledgeBaseSearch', 'checkKnowledgeBase', 'submitSearch', 'submitFirstQuestion', 'getQuestionTask', 'cancelQuestionTask', 'submitQuestion', 'deleteConversation', 'retryKnowledgeBaseFile', 'retryFailedKnowledgeBaseFiles'},
            'sse': {'watchTask', 'getTask', 'watchQuestionTask', 'getQuestionTask'},
        }
        for rule, expected in rules.items():
            pointer = '#/x-business-constraints/' + rule
            self.assertEqual({name for name, operation in operations.items()
                              if pointer in operation['constraints']}, expected)

    def test_indexing_security_requires_cookie_event_stream(self):
        """当前安全基线包含事件流，不能再把全局安全规则列作后续保留。"""
        self.assertEqual(len(verify.SECURITY_BEHAVIORS), 8)
        self.assertIn('SSE', verify.SECURITY_BEHAVIORS[-1])
        self.assertIn('Cookie', verify.SECURITY_BEHAVIORS[-1])
        mapping = {'操作': {operation: {} for operation in verify.SCOPE},
                   '当前安全行为': list(verify.SECURITY_BEHAVIORS)}
        verify.check_scope(mapping, verify.inventory(self.document))
        mapping['操作']['getTask']['#/x-business-constraints/security'] = '后续SSE'
        with self.assertRaisesRegex(ValueError, '安全'):
            verify.check_scope(mapping, verify.inventory(self.document))
        del mapping['操作']['getTask']['#/x-business-constraints/security']
        mapping['当前安全行为'].pop()
        with self.assertRaisesRegex(ValueError, '安全'):
            verify.check_scope(mapping, verify.inventory(self.document))

    def test_indexing_scope_keeps_reachable_publication_and_result_branches(self):
        """索引执行后已可达的版本、进度、失败及结果分支不得沿用旧豁免。"""
        mapping = json.loads((verify.ROOT / f'scripts/contract/scope-{verify.TICKET}.json').read_bytes())
        operations = verify.inventory(self.document)
        verify.check_scope(mapping, operations)
        for identifier, conditions in mapping['操作'].items():
            for pointer in conditions:
                self.assertFalse('/KnowledgeBase/properties/effectiveConfigVersionId/anyOf/0/' in pointer)
                self.assertFalse('/KnowledgeBase/properties/effectiveIndexVersionId/anyOf/0/' in pointer)
                self.assertFalse('/KnowledgeBase/properties/availabilityReason/anyOf/0/' in pointer)
            for rule, identifiers in verify.BUSINESS_OPERATIONS.items():
                if identifier in identifiers:
                    self.assertNotIn('#/x-business-constraints/' + rule, conditions)
        for identifier in ('getTask', 'listKnowledgeBaseFiles', 'listKnowledgeBaseTasks',
                           'testKnowledgeBaseSearch', 'getSearchRequest'):
            for pointer in mapping['操作'][identifier]:
                self.assertFalse(pointer.startswith('#/components/schemas/Task/allOf/3/then'))
                if identifier in ('getTask', 'listKnowledgeBaseFiles', 'listKnowledgeBaseTasks'):
                    self.assertFalse('/Task/properties/progressTotal/anyOf/0/' in pointer)
        self.assertFalse(any('/UploadItem/properties/indexTaskIds/items/' in pointer
                             for pointer in mapping['操作']['uploadFiles']))
        self.assertFalse(any('/x-sse/' in pointer for pointer in mapping['操作']['watchTask']))
        self.assertNotIn('#/paths/~1tasks~1{taskId}~1events/get/responses/429',
                         mapping['操作']['watchTask'])
        for conditions in mapping['操作'].values():
            self.assertFalse(any(pointer.startswith('#/components/schemas/Location/') for pointer in conditions))
        for identifier in ('listKnowledgeBaseFiles', 'listKnowledgeBaseTasks'):
            self.assertNotIn('#/components/schemas/Task/properties/warnings/items/type', mapping['操作'][identifier])
        self.assertEqual(set(mapping['操作']['previewVersionFile']), {
            '#/paths/~1files~1{fileId}~1versions~1{contentVersionId}~1preview/get/responses/429',
            '#/paths/~1files~1{fileId}~1versions~1{contentVersionId}~1preview/get/responses/503',
        })

    def test_preflight_scope_keeps_statistics_and_rebuild_identities_required(self):
        """预检统计与整库任务的空文件身份不能因缺测被保留。"""
        mapping = json.loads((verify.ROOT / 'scripts/contract/scope-10.json').read_bytes())
        self.assertEqual(mapping['操作']['updateProcessingConfig'], {})
        self.assertEqual(set(mapping['操作']['getChunkPreflight']), {
            '#/paths/~1tasks~1{taskId}~1chunk-preflight/get/responses/429',
            '#/paths/~1tasks~1{taskId}~1chunk-preflight/get/responses/503',
        })
        for field in ('fileId', 'targetContentVersionId'):
            self.assertNotIn('#/components/schemas/Task/properties/' + field + '/anyOf/1/type',
                             mapping['操作']['listKnowledgeBaseTasks'])

    def test_excluded_files_require_nullable_target_and_task_evidence(self):
        """排除可保留无来源条目，三个空目标分支须有实际证据。"""
        mapping = json.loads((verify.ROOT / 'scripts/contract/scope-11.json').read_bytes())
        for field in ('targetConfigVersionId', 'targetContentVersionId', 'task'):
            self.assertNotIn('#/components/schemas/KnowledgeBaseFile/properties/' + field + '/anyOf/1/type',
                             mapping['操作']['listKnowledgeBaseFiles'])
        self.assertNotIn('#/components/schemas/Task/properties/targetConfigVersionId/anyOf/1/type',
                         mapping['操作']['listKnowledgeBaseTasks'])
        for operation in ('removeKnowledgeBaseSource', 'listKnowledgeBaseExclusions',
                          'excludeKnowledgeBaseFile', 'includeKnowledgeBaseFile', 'listFileKnowledgeBases'):
            for pointer in mapping['操作'][operation]:
                self.assertIn(pointer.rsplit('/', 1)[-1], {'409', '422', '429', '503'})

    def test_task_cancellation_requires_real_conflict_and_success_evidence(self):
        """取消冲突与终态结果不能用共享错误声明豁免。"""
        mapping = json.loads((verify.ROOT / 'scripts/contract/scope-12.json').read_bytes())
        prefix = '#/paths/~1tasks~1{taskId}~1cancel/post/responses/'
        self.assertEqual(set(mapping['操作']['cancelTask']), {prefix + code for code in ('422', '429', '503')})
        self.assertNotIn(prefix + '409', mapping['操作']['cancelTask'])
        self.assertNotIn(prefix + '200', mapping['操作']['cancelTask'])
        for operation in ('listIndexSwitches', 'downloadVersionFile'):
            self.assertTrue(all(pointer.rsplit('/', 1)[-1] in {'429', '503'}
                                for pointer in mapping['操作'][operation]))

    def test_publication_scope_requires_real_publication_history(self):
        """发布与下线历史不能继续按空库条件保留。"""
        mapping = json.loads((verify.ROOT / 'scripts/contract/scope-13.json').read_bytes())
        for operation in ('listKnowledgeBases', 'getKnowledgeBase'):
            for pointer in mapping['操作'][operation]:
                self.assertFalse(any('/KnowledgeBase/properties/' + field + '/' in pointer
                                     for field in ('publishedAt', 'offlineAt', 'offlineReason')))
        self.assertNotIn('#/paths/~1knowledge-bases~1{knowledgeBaseId}~1publish/post/responses/422',
                         mapping['操作']['publishKnowledgeBase'])
        self.assertNotIn('#/paths/~1knowledge-bases~1{knowledgeBaseId}~1offline/post/responses/409',
                         mapping['操作']['offlineKnowledgeBase'])
        self.assertNotIn('#/paths/~1knowledge-bases~1{knowledgeBaseId}~1check/post/responses/429',
                         mapping['操作']['checkKnowledgeBase'])

    def test_multisearch_requires_instance_partial_scope_and_latest_request(self):
        """普通检索不得沿用管理入口的空实例及无部分范围豁免。"""
        mapping = json.loads((verify.ROOT / 'scripts/contract/scope-14.json').read_bytes())
        for operation in ('submitSearch', 'getSearchRequest'):
            conditions = mapping['操作'][operation]
            self.assertFalse(any('/instanceId/anyOf/0/' in key for key in conditions))
            self.assertFalse(any('/warnings/items/' in key for key in conditions))
        prefix = '#/paths/~1search-instances~1{instanceId}~1requests/post/responses/'
        for code in ('404', '409', '429'):
            self.assertNotIn(prefix + code, mapping['操作']['submitSearch'])
        self.assertNotIn('#/components/schemas/Task/properties/knowledgeBaseId/anyOf/1/type',
                         mapping['操作']['getSearchRequest'])
        self.assertIn('createSearchInstance', verify.SCOPE)
        self.assertIn('submitSearch', verify.SCOPE)

    def test_instance_reads_require_submitted_metadata_branches(self):
        """实例读取不能继承创建响应的空查询与空范围条件保留。"""
        mapping = json.loads((verify.ROOT / 'scripts/contract/scope-15.json').read_bytes())
        for name in ('listSearchInstances', 'getSearchInstance', 'renameSearchInstance', 'updateSearchInstanceScope'):
            self.assertFalse(any('/SearchInstance/' in key for key in mapping['操作'][name]))
        self.assertNotIn('#/paths/~1search-requests~1{requestId}~1cancel/post/responses/410',
                         mapping['操作']['cancelSearchRequest'])
        self.assertIn('deleteSearchInstance', verify.SCOPE)

    def test_question_stream_requires_exact_notification_obligations(self):
        """问答事件必须核对完整协议，不能仅复用普通任务路径证据。"""
        operations = verify.inventory(self.document)
        prefix = '#/paths/~1question-tasks~1{taskId}~1events/get/x-sse/'
        for suffix in ('events/phase', 'events/terminal', 'id', 'lastEventIdHeader', 'terminalStates', 'reconnect'):
            self.assertIn(prefix + suffix, operations['watchQuestionTask']['constraints'])
        for name in ('submitFirstQuestion', 'getQuestionTask', 'cancelQuestionTask', 'watchQuestionTask', 'getConversationEvidence'):
            self.assertIn(name, verify.SCOPE)

    def test_conversation_followup_requires_active_conflict_and_history(self):
        """追问活动冲突、真实容量及完整历史上下文不得通过条件保留豁免。"""
        mapping = json.loads((verify.ROOT / 'scripts/contract/scope-17.json').read_bytes())
        prefix = '#/paths/~1conversations~1{conversationId}~1questions/post/responses/'
        for code in ('404', '409', '429'):
            self.assertNotIn(prefix + code, mapping['操作']['submitQuestion'])
        for name in ('submitQuestion', 'listConversationTurns'):
            self.assertFalse(any('/Answer/properties/historyContext/' in key or
                                 '/Answer/properties/omittedHistoryTurnCount/' in key
                                 for key in mapping['操作'][name]))
        self.assertIn('deleteConversation', verify.SCOPE)

    def test_historical_processing_config_requires_real_version_identity(self):
        """历史处理配置只有本地容量及维护状态保留，版本归属与全部字段仍须真实验证。"""
        mapping = json.loads((verify.ROOT / 'scripts/contract/scope-18.json').read_bytes())
        prefix = '#/paths/~1knowledge-bases~1{knowledgeBaseId}~1processing-config~1versions~1{configVersionId}/get/responses/'
        self.assertEqual(set(mapping['操作']['getProcessingConfigVersion']), {prefix + '429', prefix + '503'})
        self.assertIn('getProcessingConfigVersion', verify.SCOPE)

    def test_explicit_retry_requires_nonempty_original_task_identity(self):
        """显式重试后索引读取不得继续豁免非空原失败任务标识。"""
        mapping = json.loads((verify.ROOT / 'scripts/contract/scope-19.json').read_bytes())
        for name in ('getTask', 'listKnowledgeBaseFiles', 'listKnowledgeBaseTasks'):
            self.assertFalse(any('/retryOfTaskId/anyOf/0/' in key for key in mapping['操作'][name]))
        for name in ('retryKnowledgeBaseFile', 'retryFailedKnowledgeBaseFiles'):
            self.assertTrue(all(key.rsplit('/', 1)[-1] in {'409', '422', '429', '503'} for key in mapping['操作'][name]))
            self.assertIn(name, verify.SCOPE)

    def test_deletion_requires_missing_current_filename_and_cleanup_conflict(self):
        """公开删除后历史快照缺失当前文件名及清理重试状态冲突均不可豁免。"""
        mapping = json.loads((verify.ROOT / 'scripts/contract/scope-20.json').read_bytes())
        self.assertFalse(any('/Evidence/properties/currentFilename/anyOf/1/' in key
                             for conditions in mapping['操作'].values() for key in conditions))
        self.assertNotIn('#/paths/~1tasks~1{taskId}~1retry-cleanup/post/responses/409',
                         mapping['操作']['retryCleanupTask'])
        self.assertIn('listTasks', verify.SCOPE)

    def test_maintenance_safety_cannot_be_removed_from_migration_scope(self):
        """迁移安全义务与失败阶段必须保留，不能以未测试缩减分母。"""
        mapping = json.loads((verify.ROOT / 'scripts/contract/scope-21.json').read_bytes())
        operations = verify.inventory(self.document)
        for name in ('migrationPreflight', 'submitMigration', 'getMaintenanceTask', 'retryMaintenanceTask'):
            self.assertIn(name, verify.SCOPE)
            self.assertIn('#/x-business-constraints/maintenance', operations[name]['constraints'])
            self.assertNotIn('#/x-business-constraints/maintenance', mapping['操作'][name])
        for pointer in ('#/components/schemas/MaintenanceResult/properties/configurationSwitched/type',
                        '#/components/schemas/MaintenanceResult/properties/oldDirectoryResidue/type',
                        '#/components/schemas/MaintenanceResult/properties/failureStage/anyOf/0/enum'):
            self.assertNotIn(pointer, mapping['操作']['getMaintenanceTask'])

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
