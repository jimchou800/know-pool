"""从唯一契约和真实测试证据生成不可变报告；缺证据时失败而非补写通过。"""

import argparse
import hashlib
import importlib.metadata
import json
import os
import platform
import sys
import uuid
import xml.etree.ElementTree as ET
from datetime import datetime, timezone
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
CONTRACT = ROOT / 'docs/agents/api/openapi.yaml'
TICKET = '21'
SCOPE = ('initializeSession', 'getSystemStatus', 'listDatasets', 'createDataset', 'getDataset', 'updateDataset',
         'listDatasetFiles', 'uploadFiles', 'getFile', 'previewLatestFile', 'downloadLatestFile',
         'renameFile', 'listFileVersions', 'getGenerationModel', 'updateGenerationModel',
         'testGenerationModel', 'getEmbeddingModel', 'updateEmbeddingModel', 'testEmbeddingModel',
         'listEmbeddingModelVersions', 'getEmbeddingModelVersion', 'updateEmbeddingVersionCredential',
         'getConnectionTestResult', 'getVectorConfig', 'updateVectorConfig', 'testVectorConfig', 'getTask',
         'listKnowledgeBases', 'createKnowledgeBase', 'getKnowledgeBase', 'updateKnowledgeBase',
         'getProcessingConfig', 'addKnowledgeBaseSource', 'listKnowledgeBaseSources', 'listKnowledgeBaseFiles',
         'watchTask', 'listKnowledgeBaseTasks', 'testKnowledgeBaseSearch', 'getSearchRequest', 'getSearchEvidence',
         'previewVersionFile', 'getChunkPreflight', 'updateProcessingConfig',
         'removeKnowledgeBaseSource', 'listKnowledgeBaseExclusions', 'excludeKnowledgeBaseFile',
         'includeKnowledgeBaseFile', 'listFileKnowledgeBases',
         'listIndexSwitches', 'downloadVersionFile', 'cancelTask',
         'publishKnowledgeBase', 'offlineKnowledgeBase', 'checkKnowledgeBase',
         'createSearchInstance', 'submitSearch',
         'listSearchInstances', 'getSearchInstance', 'renameSearchInstance',
         'updateSearchInstanceScope', 'deleteSearchInstance', 'cancelSearchRequest',
         'submitFirstQuestion', 'getQuestionTask', 'cancelQuestionTask',
         'watchQuestionTask', 'getConversationEvidence',
         'listConversations', 'getConversation', 'renameConversation', 'updateConversationScope',
         'listConversationTurns', 'submitQuestion', 'deleteConversation',
         'getProcessingConfigVersion', 'retryKnowledgeBaseFile', 'retryFailedKnowledgeBaseFiles',
         'getDatasetDeleteImpact', 'deleteDataset', 'getFileDeleteImpact', 'deleteFile',
         'getKnowledgeBaseDeleteImpact', 'deleteKnowledgeBase', 'listTasks', 'retryCleanupTask',
         'migrationPreflight', 'submitMigration', 'getMaintenanceTask', 'retryMaintenanceTask')
BUSINESS_OPERATIONS = {
    'maintenance': {'migrationPreflight', 'submitMigration', 'getMaintenanceTask', 'retryMaintenanceTask'},
    'indexSwitch': {'getTask', 'listKnowledgeBaseFiles', 'testKnowledgeBaseSearch', 'getSearchRequest', 'getSearchEvidence', 'updateProcessingConfig', 'submitSearch', 'submitFirstQuestion', 'getQuestionTask', 'getConversationEvidence', 'submitQuestion', 'listConversationTurns'},
    'publication': {'testKnowledgeBaseSearch', 'getKnowledgeBase', 'publishKnowledgeBase', 'offlineKnowledgeBase', 'checkKnowledgeBase', 'submitSearch', 'submitFirstQuestion', 'submitQuestion', 'deleteDataset', 'deleteFile', 'deleteKnowledgeBase'},
    'cost': {'getTask', 'testKnowledgeBaseSearch', 'checkKnowledgeBase', 'submitSearch', 'submitFirstQuestion', 'getQuestionTask', 'cancelQuestionTask', 'submitQuestion', 'deleteConversation', 'retryKnowledgeBaseFile', 'retryFailedKnowledgeBaseFiles'},
    'sse': {'watchTask', 'getTask', 'watchQuestionTask', 'getQuestionTask'},
}
SECURITY_BEHAVIORS = (
    '仅回环监听及 Host 白名单',
    'Origin 白名单与拒绝非法来源',
    '会话 Cookie 的 HttpOnly、SameSite=Strict、Path=/ 属性',
    'HTTP 不强制 Secure，HTTPS 安全请求设置 Secure',
    '缺失或失效会话拒绝访问受保护读取与写入',
    '非法写请求在业务路由前校验 CSRF，合法保护信息不绕过路由检查',
    '会话令牌不进入 URL 或前端持久化，报告日志不保存真实值',
    'SSE 使用 Cookie 会话，拒绝非法来源与失效会话，令牌不进入 URL，断线查询原任务不重新提交',
)
METHODS = {'get', 'post', 'put', 'patch', 'delete', 'head', 'options'}
KEYWORDS = {
    'type', 'required', 'additionalProperties', 'enum', 'const', 'format',
    'pattern', 'minimum', 'maximum', 'minLength', 'maxLength', 'minItems',
    'maxItems', 'uniqueItems', 'oneOf', 'anyOf', 'allOf', 'not', 'readOnly',
    'writeOnly', 'default', 'minProperties', 'maxProperties', 'multipleOf',
    'exclusiveMinimum', 'exclusiveMaximum', 'if', 'then', 'else',
}


def digest(raw):
    return hashlib.sha256(raw).hexdigest()


def snapshot():
    """只绑定实现、测试及构建输入；报告和签认不会引发摘要循环。"""
    excluded = {'node_modules', 'target', 'dist', 'coverage', 'artifacts',
                '.vite', '.codex', '.runtime', '__pycache__', 'playwright-report', 'test-results'}
    files = []
    for base in ('front', 'server', 'verification', 'scripts/contract', '.github'):
        directory = ROOT / base
        if directory.exists():
            for parent, directories, names in os.walk(directory):
                directories[:] = [name for name in directories if name not in excluded]
                files.extend(Path(parent) / name for name in names)
    files.extend(ROOT / name for name in ('pom.xml', '.gitignore', '.gitattributes') if (ROOT / name).exists())
    entries = [
        {'path': p.relative_to(ROOT).as_posix(), 'sha256': digest(p.read_bytes())}
        for p in sorted(set(files)) if not excluded.intersection(p.relative_to(ROOT).parts)
    ]
    return digest(json.dumps(entries, sort_keys=True).encode()), entries


def resolve(document, reference):
    if not reference.startswith('#/'):
        raise ValueError('仅允许契约内部引用')
    value = document
    for part in reference[2:].split('/'):
        value = value[part.replace('~1', '/').replace('~0', '~')]
    return value


def escape(value):
    return value.replace('~', '~0').replace('/', '~1')


def inventory(document):
    operations = {}
    for path, item in document.get('paths', {}).items():
        for method, operation in item.items():
            if method not in METHODS:
                continue
            identifier = operation['operationId']
            if identifier in operations:
                raise ValueError('操作标识重复：' + identifier)
            prefix = '#/paths/' + escape(path) + '/' + method
            constraints = {prefix + '/method', prefix + '/path'}
            visited = set()

            def walk(value, pointer):
                if isinstance(value, dict):
                    if '$ref' in value:
                        reference = value['$ref']
                        if reference not in visited:
                            visited.add(reference)
                            walk(resolve(document, reference), reference)
                    for key, child in value.items():
                        child_pointer = pointer + '/' + escape(key)
                        if key in KEYWORDS or key in {'security', 'parameters', 'requestBody'}:
                            constraints.add(child_pointer)
                        if key == 'responses':
                            constraints.update(child_pointer + '/' + code for code in child)
                        walk(child, child_pointer)
                elif isinstance(value, list):
                    for index, child in enumerate(value):
                        walk(child, pointer + '/' + str(index))

            walk(operation, prefix)
            # 描述承载 Cookie、来源等行为约束，不能因缺少结构化关键字而漏掉。
            if operation.get('description'):
                constraints.add(prefix + '/description')
            constraints.add('#/x-business-constraints/security')
            for rule, identifiers in BUSINESS_OPERATIONS.items():
                if identifier in identifiers:
                    reference = '#/x-business-constraints/' + rule
                    resolve(document, reference)
                    constraints.add(reference)
            if identifier in {'watchTask', 'watchQuestionTask'}:
                for suffix in ('events/phase', 'events/terminal', 'id', 'lastEventIdHeader', 'terminalStates', 'reconnect'):
                    reference = prefix + '/x-sse/' + suffix
                    resolve(document, reference)
                    constraints.add(reference)
            operations[identifier] = {'path': path, 'method': method.upper(),
                                      'constraints': sorted(constraints)}
    if not operations:
        raise ValueError('空契约禁止通过')
    return operations


def read_evidence(path, contract_hash, implementation_hash):
    evidence = json.loads(path.read_text(encoding='utf-8'))
    if not isinstance(evidence, dict):
        raise ValueError('证据必须为对象：' + str(path))
    if evidence.get('contractSha256') != contract_hash:
        raise ValueError('证据契约摘要不一致：' + str(path))
    if evidence.get('implementationSha256') != implementation_hash:
        raise ValueError('证据实现摘要已过期：' + str(path))
    tests = evidence.get('tests')
    if not isinstance(tests, list) or not tests:
        raise ValueError('测试为空或存在失败：' + str(path))
    names = set()
    for test in tests:
        if (not isinstance(test, dict) or not isinstance(test.get('name'), str)
                or not test['name'] or test['name'] in names or test.get('passed') is not True):
            raise ValueError('测试名称无效、重复或未通过：' + str(path))
        names.add(test['name'])
        for key in ('operationIds', 'constraintIds'):
            value = test.get(key)
            if (not isinstance(value, list) or any(not isinstance(item, str) for item in value)
                    or len(value) != len(set(value)) or (key == 'operationIds' and not value)):
                raise ValueError('测试映射无效：' + key)
    return evidence


def coverage(evidence, operation_id, constraint):
    return [test['name'] for test in evidence.get('tests', [])
            if operation_id in test.get('operationIds', [])
            and constraint in test.get('constraintIds', []) and test.get('passed') is True]


def read_evidence_files(paths, contract_hash, implementation_hash):
    """逐份验证后合并，缺文件或跨文件重名均拒绝。"""
    merged = {'tests': [], 'routes': []}
    names = set()
    for path in paths:
        evidence = read_evidence(path, contract_hash, implementation_hash)
        for test in evidence['tests']:
            if test['name'] in names:
                raise ValueError('跨文件测试名称重复：' + test['name'])
            names.add(test['name'])
            merged['tests'].append(test)
        routes = evidence.get('routes', [])
        if not isinstance(routes, list):
            raise ValueError('路由清单必须为数组：' + str(path))
        for route in routes:
            if route not in merged['routes']:
                merged['routes'].append(route)
    return merged


def operation_complete(constraints):
    """完整操作要求全部原始约束通过，保留项不计完成。"""
    return bool(constraints) and all(row['状态'] == '通过' for row in constraints)


def scope_mapping(contract_hash):
    path = ROOT / f'scripts/contract/scope-{TICKET}.json'
    raw = path.read_bytes()
    mapping = json.loads(raw)
    check_scope(mapping, inventory(json.loads(CONTRACT.read_bytes())))
    actors = set()
    for role in ('frontend', 'backend'):
        approval = json.loads((ROOT / f'docs/agents/api/reviews/scope-{TICKET}-{role}.json').read_text(encoding='utf-8'))
        if (approval.get('mapSha256') != digest(raw) or approval.get('contractSha256') != contract_hash
                or approval.get('role') != role or approval.get('conclusion') != '通过'
                or not approval.get('actor') or not approval.get('evidence')):
            raise ValueError('缺少有效适用映射独立签认：' + role)
        actors.add(approval['actor'])
    if len(actors) != 2:
        raise ValueError('适用映射不得由同一代理代签')
    return mapping, digest(raw)


def check_scope(mapping, operations):
    """适用映射只解释真实不可达分支，当前事件安全不得豁免。"""
    if mapping.get('当前安全行为') != list(SECURITY_BEHAVIORS):
        raise ValueError('当前安全基线必须包含八项行为及SSE会话验证')
    if set(mapping.get('操作', {})) != set(SCOPE):
        raise ValueError('适用映射操作范围不完整')
    for identifier, conditions in mapping['操作'].items():
        if not isinstance(conditions, dict) or not set(conditions).issubset(operations[identifier]['constraints']):
            raise ValueError('适用映射引用未声明约束：' + identifier)
        if '#/x-business-constraints/security' in conditions:
            raise ValueError('当前安全规则不得作为后续SSE豁免：' + identifier)
        if any(not isinstance(reason, str) or not reason.strip() for reason in conditions.values()):
            raise ValueError('适用映射缺少逐指针理由：' + identifier)


def check_routes(document, routes):
    if not isinstance(routes, list) or not routes:
        raise ValueError('缺少真实应用路由清单')
    if any(not isinstance(route, dict) or not isinstance(route.get('method'), str)
           or not isinstance(route.get('path'), str) for route in routes):
        raise ValueError('实际路由清单结构无效')
    declared = {(method.upper(), server['url'].rstrip('/') + path)
                for server in document['servers'] for path, item in document['paths'].items()
                for method in item if method in METHODS}
    actual = {(route['method'], route['path']) for route in routes}
    extra = actual - declared
    if extra:
        raise ValueError('存在未声明公开接口：' + str(sorted(extra)))
    required = {(method.upper(), '/api/v1' + path) for path, item in document['paths'].items()
                for method, operation in item.items()
                if method in METHODS and operation['operationId'] in SCOPE}
    if not required.issubset(actual):
        raise ValueError('切片实际路由缺失')


def check_test_results():
    frontend = ROOT / 'front/artifacts/vitest.json'
    result = json.loads(frontend.read_text(encoding='utf-8'))
    if (result.get('numFailedTests') != 0 or result.get('numFailedTestSuites') != 0
            or result.get('numPendingTests', 0) or result.get('numTodoTests', 0)
            or not result.get('numPassedTests', 0) or result.get('success') is not True):
        raise ValueError('前端原始测试结果未通过')
    backend = list((ROOT / 'server/target/surefire-reports').glob('TEST-*.xml'))
    if not backend:
        raise ValueError('缺少后端原始测试结果')
    tests = 0
    for path in backend:
        suite = ET.parse(path).getroot()
        if int(suite.get('failures', '0')) or int(suite.get('errors', '0')) or int(suite.get('skipped', '0')):
            raise ValueError('后端存在失败、错误或跳过测试')
        tests += int(suite.get('tests', '0'))
    if not tests:
        raise ValueError('后端测试为空')
    return {'前端原始结果': frontend.relative_to(ROOT).as_posix(), '前端通过测试': result['numPassedTests'],
            '后端原始结果': [path.relative_to(ROOT).as_posix() for path in backend], '后端通过测试': tests}


def acceptance(report_path):
    """第二阶段只核对已生成报告与两份独立签认，绝不重新生成报告。"""
    report = json.loads(report_path.read_text(encoding='utf-8'))
    if report.get('失败') or report.get('结论') != '校验通过，仍待双方独立验收':
        raise ValueError('失败或未验证报告不能验收')
    if report.get('实现快照') != snapshot()[0] or report.get('契约SHA256') != digest(CONTRACT.read_bytes()):
        raise ValueError('报告已过期，必须重新执行验证')
    actors = set()
    for role in ('frontend', 'backend'):
        path = ROOT / 'docs/agents/api/reviews' / (report_path.parent.name + '-' + role + '.json')
        approval = json.loads(path.read_text(encoding='utf-8'))
        if (approval.get('role') != role or approval.get('conclusion') != '通过'
                or approval.get('contractSha256') != report['契约SHA256']
                or approval.get('implementationSha256') != report['实现快照']
                or approval.get('reportSha256') != digest(report_path.read_bytes())
                or not approval.get('actor') or not approval.get('evidence')):
            raise ValueError('签认不完整或摘要不一致：' + role)
        actors.add(approval['actor'])
    if len(actors) != 2:
        raise ValueError('双方不得由同一代理代签')
    return '双方独立签认与原报告一致，协调仍须核对端到端及全部工程证据'


def main():
    parser = argparse.ArgumentParser(description='生成契约实现校验报告')
    parser.add_argument('--snapshot', action='store_true', help='输出实现快照摘要')
    parser.add_argument('--inventory', action='store_true', help='输出当前切片约束清单')
    parser.add_argument('--acceptance', type=Path, help='核对已有报告的双方独立签认，不重跑生成')
    parser.add_argument('--provider', type=Path, action='append', help='逐份验证并合并提供方证据，可重复指定')
    parser.add_argument('--consumer', type=Path, default=ROOT / 'front/artifacts/contract-consumer.json')
    parser.add_argument('--browser', type=Path, default=ROOT / 'front/artifacts/contract-browser.json')
    args = parser.parse_args()
    providers = args.provider or [ROOT / 'server/target/contract/provider-evidence.json',
                                  ROOT / 'server/target/contract/provider-settings-evidence.json',
                                  ROOT / 'server/target/contract/provider-knowledge-bases-evidence.json',
                                  ROOT / 'server/target/contract/provider-indexing-evidence.json',
                                  ROOT / 'server/target/contract/provider-indexing-events-evidence.json',
                                  ROOT / 'server/target/contract/provider-indexing-gaps-evidence.json',
                                  ROOT / 'server/target/contract/provider-security-evidence.json']
    if args.acceptance:
        try:
            print(acceptance(args.acceptance))
            return 0
        except (OSError, ValueError, KeyError) as error:
            print('验收拒绝：' + str(error))
            return 1
    implementation_hash, files = snapshot()
    if args.snapshot:
        print(implementation_hash)
        return 0
    raw = CONTRACT.read_bytes()
    contract_hash = digest(raw)
    parse_failure = None
    try:
        document = json.loads(raw)
        operations = inventory(document)
        if not set(SCOPE).issubset(operations):
            raise ValueError('切片必需操作缺失')
    except (ValueError, KeyError, TypeError, AttributeError) as error:
        document, operations = {}, {}
        parse_failure = str(error)
    if args.inventory:
        if parse_failure:
            raise ValueError(parse_failure)
        print(json.dumps({key: operations[key] for key in SCOPE}, ensure_ascii=False, indent=2))
        return 0
    report = {'执行时间': datetime.now(timezone.utc).isoformat(), '契约SHA256': contract_hash,
              '契约版本': document.get('info', {}).get('version'), '实现快照': implementation_hash,
              '实现文件': files, '环境': {'Python': platform.python_version(), '系统': platform.system()},
              '范围': list(SCOPE), '失败': [], '操作': [], '双方报告验收': '待分别签认，不由生成器代签'}
    evidence = {}
    mapping = {}
    try:
        if parse_failure:
            raise ValueError(parse_failure)
        sys.path.insert(0, str(ROOT / '.runtime/contract-tools'))
        from openapi_spec_validator import validate_spec
        validate_spec(document)
        report['校验工具'] = {name: importlib.metadata.version(name)
                            for name in ('openapi-spec-validator', 'jsonschema', 'referencing')}
        for role in ('frontend', 'backend'):
            approval = ROOT / f'docs/agents/api/reviews/contract-{TICKET}-{role}.md'
            if contract_hash not in approval.read_text(encoding='utf-8'):
                raise ValueError('缺少同一契约的设计签认：' + role)
        mapping, mapping_hash = scope_mapping(contract_hash)
        report['适用映射SHA256'] = mapping_hash
        report['适用映射说明'] = mapping['说明']
        for role, paths in (('提供方', providers), ('消费方', [args.consumer])):
            try:
                evidence[role] = read_evidence_files(paths, contract_hash, implementation_hash)
                for test in evidence[role]['tests']:
                    if any(identifier not in operations for identifier in test['operationIds']):
                        raise ValueError('测试引用未声明操作：' + test['name'])
                    known = set().union(*(set(operations[identifier]['constraints']) for identifier in test['operationIds']))
                    known.update('security.' + str(index + 1) for index in range(len(mapping.get('当前安全行为', []))))
                    if not set(test['constraintIds']).issubset(known):
                        raise ValueError('测试引用未声明约束：' + test['name'])
                sources = [{'路径': path.relative_to(ROOT).as_posix(), 'SHA256': digest(path.read_bytes())}
                           for path in paths]
                report[role + '证据'] = sources[0] if len(sources) == 1 else {'文件': sources}
            except (OSError, ValueError) as error:
                report['失败'].append(str(error))
        browser = read_evidence(args.browser, contract_hash, implementation_hash)
        report['浏览器证据'] = {'路径': str(args.browser.relative_to(ROOT)), 'SHA256': digest(args.browser.read_bytes())}
        if '消费方' in evidence:
            evidence['消费方']['tests'] = evidence['消费方']['tests'] + browser['tests']
        report['原始测试结果'] = check_test_results()
        if '提供方' in evidence:
            check_routes(document, evidence['提供方'].get('routes'))
    except Exception as error:
        report['失败'].append(str(error))
    for identifier, operation in operations.items():
        rows = []
        for constraint in operation['constraints']:
            checks = {role: coverage(evidence.get(role, {}), identifier, constraint)
                      for role in ('提供方', '消费方')}
            state = ('通过' if all(checks.values()) else '未验证') if identifier in SCOPE else '未实现'
            reason = mapping.get('操作', {}).get(identifier, {}).get(constraint)
            if reason:
                state = '条件未触发或后续保留'
            rows.append({'约束': constraint, '状态': state, '证据': checks, '适用说明': reason})
        applicable = [row for row in rows if row['适用说明'] is None]
        report['操作'].append({'标识': identifier, '方法': operation['method'], '路径': operation['path'],
                               '完整操作状态': '通过' if operation_complete(rows) else '未完成',
                               '状态': '通过' if applicable and all(row['状态'] == '通过' for row in applicable)
                               else ('未验证' if identifier in SCOPE else '未实现'), '约束': rows})
    scoped = [row for row in report['操作'] if row['标识'] in SCOPE]
    raw_constraints = [constraint for row in scoped for constraint in row['约束']]
    constraints = [row for row in raw_constraints if row['适用说明'] is None]
    passed = sum(row['状态'] == '通过' for row in scoped)
    verified = sum(row['状态'] == '通过' for row in constraints)
    report['切片路径覆盖'] = {'通过': passed, '全部': len(SCOPE)}
    report['切片约束覆盖'] = {'通过': verified, '全部': len(constraints)}
    report['切片原始约束'] = {'全部': len(raw_constraints), '适用': len(constraints),
                              '条件未触发或后续保留': len(raw_constraints) - len(constraints)}
    security = []
    for index, behavior in enumerate(mapping.get('当前安全行为', [])):
        identifier = 'security.' + str(index + 1)
        checks = {role: [test['name'] for test in evidence.get(role, {}).get('tests', [])
                        if identifier in test.get('constraintIds', []) and test.get('passed') is True]
                  for role in ('提供方', '消费方')}
        security.append({'标识': identifier, '行为': behavior, '证据': checks,
                         '状态': '通过' if all(checks.values()) else '未验证'})
    report['当前安全行为'] = security
    if not security or any(row['状态'] != '通过' for row in security):
        report['失败'].append('当前安全行为存在未验证项')
    complete = [row for row in report['操作'] if row['完整操作状态'] == '通过']
    report['全项目路径覆盖'] = {'通过': len(complete), '全部': len(operations)}
    report['全项目约束覆盖'] = {'通过': verified, '全部': sum(len(row['约束']) for row in report['操作'])}
    report['全项目未完成'] = [row['标识'] for row in report['操作'] if row['完整操作状态'] != '通过']
    report['全项目保留规则'] = mapping.get('后续保留', [])
    report['全项目未完成约束'] = [
        {'操作': operation['标识'], '约束': row['约束'], '状态': row['状态'], '说明': row['适用说明']}
        for operation in report['操作'] for row in operation['约束'] if row['状态'] != '通过'
    ]
    if passed != len(SCOPE) or not constraints or verified != len(constraints):
        report['失败'].append('切片存在未验证操作或约束')
    report['结论'] = '校验通过，仍待双方独立验收' if not report['失败'] else '校验未通过'
    run_id = datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ') + '-' + uuid.uuid4().hex[:8]
    output = ROOT / '.scratch/technical-design/contract-reports' / run_id
    output.mkdir(parents=True, exist_ok=False)
    (output / 'report.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    text = [f"# 契约校验报告\n\n{report['结论']}\n", f'契约摘要：`{contract_hash}`。',
            f'实现快照：`{implementation_hash}`。',
            f'切片路径通过 {passed}/{len(SCOPE)}，约束通过 {verified}/{len(constraints)}。',
            f'原始约束 {len(raw_constraints)} 项，条件未触发或后续保留 {len(raw_constraints) - len(constraints)} 项，均未记为通过。',
            '切片适用路径完成不等于全部原始约束完成；机器报告逐项保留全项目未完成约束和后续规则。',
            f"全项目共 {len(operations)} 个操作，未完成 {len(report['全项目未完成'])} 个。",
            '双方报告验收：待前端和后端实际代理分别签认本报告及摘要。', '\n## 失败与未验证\n']
    text.extend('- ' + failure for failure in report['失败'])
    text.extend('\n- ' + row['标识'] + '：' + row['状态'] for row in report['操作'])
    (output / 'report.md').write_text('\n\n'.join(text) + '\n', encoding='utf-8')
    print(str(output.relative_to(ROOT)) + '：' + report['结论'])
    return 1 if report['失败'] else 0


if __name__ == '__main__':
    sys.exit(main())
