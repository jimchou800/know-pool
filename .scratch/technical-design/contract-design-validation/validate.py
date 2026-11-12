import copy
import hashlib
import importlib.metadata
import json
import re
import sys
from datetime import datetime, timezone
from pathlib import Path

sys.path.insert(0, str(Path('.runtime/contract-tools').resolve()))
from jsonschema import Draft202012Validator, FormatChecker
from openapi_spec_validator import validate_spec

source = Path('docs/agents/api/openapi.yaml')
raw = source.read_bytes()
document = json.loads(raw)
methods = {'get', 'post', 'put', 'patch', 'delete', 'head', 'options'}


def inspect(value):
    references = 0
    operations = []

    def walk(node):
        nonlocal references
        if isinstance(node, dict):
            if '$ref' in node:
                ref = node['$ref']
                assert ref.startswith('#/'), ref
                resolved = value
                for key in ref[2:].split('/'):
                    resolved = resolved[key.replace('~1', '/').replace('~0', '~')]
                references += 1
            for child in node.values():
                walk(child)
        elif isinstance(node, list):
            for child in node:
                walk(child)

    walk(value)
    for path, item in value['paths'].items():
        for method, operation in item.items():
            if method not in methods:
                continue
            operations.append(operation['operationId'])
            parameters = item.get('parameters', []) + operation.get('parameters', [])
            parameters = [value['components']['parameters'][p['$ref'].split('/')[-1]] if '$ref' in p else p for p in parameters]
            assert set(re.findall(r'\{([^}]+)\}', path)) == {
                p['name'] for p in parameters if p['in'] == 'path' and p['required']
            }, path
            if operation['operationId'] != 'initializeSession':
                assert operation['security'] == [{'localSession': []}], path
            if method in {'post', 'put', 'patch', 'delete'}:
                assert any(p['name'] == 'X-CSRF-Token' and p['required'] for p in parameters), path
            assert any(code.startswith('2') for code in operation['responses']), path
            if 'text/event-stream' in operation['responses'].get('200', {}).get('content', {}):
                assert set(operation['x-sse']['events']) == {'phase', 'terminal'}, path
    assert len(operations) == len(set(operations)) and operations
    return references, operations


validate_spec(document)
references, operations = inspect(document)
for mutate in ['duplicate', 'reference']:
    broken = copy.deepcopy(document)
    if mutate == 'duplicate':
        broken['paths']['/invalid'] = {'get': copy.deepcopy(broken['paths']['/session']['get'])}
    else:
        broken['components']['schemas']['Invalid'] = {'$ref': '#/components/schemas/Missing'}
    try:
        inspect(broken)
    except (AssertionError, KeyError):
        pass
    else:
        raise AssertionError(mutate)

examples = 0
for name, schema in document['components']['schemas'].items():
    Draft202012Validator.check_schema(schema)
    validator = Draft202012Validator({**document, '$ref': '#/components/schemas/' + name}, format_checker=FormatChecker())
    for example in schema.get('examples', []):
        validator.validate(example)
        examples += 1

cases = []


def check(schema, instance, expected, label):
    validator = Draft202012Validator({**document, '$ref': '#/components/schemas/' + schema}, format_checker=FormatChecker())
    result = validator.is_valid(instance)
    assert result == expected, (schema, label, list(validator.iter_errors(instance)))
    cases.append({'场景': label, '预期接受': expected, '结果': '通过'})


for name in ['report.txt', '中文资料.pdf', ' 名称.md']:
    check('RenameFileInput', {'name': name}, True, '允许有效文件名：' + name)
for name in ['a/b', 'a\\b', '.', '..', 'a\n', 'a\x00b', 'a\x7f', '']:
    check('RenameFileInput', {'name': name}, False, '拒绝非法文件名：' + repr(name))
identifier = '00000000-0000-4000-8000-000000000001'
for question, accepted in [('中文问题', True), ('hello', True), ('\n内容\n', True), ('', False), (' \n\t', False)]:
    check('QuestionInput', {'question': question, 'knowledgeBaseIds': [identifier]}, accepted, '问题非空白边界：' + repr(question))
check('QuestionInput', {'question': '问题', 'knowledgeBaseIds': []}, False, '拒绝空知识库范围')
check('QuestionInput', {'question': '问题', 'knowledgeBaseIds': [identifier, identifier]}, False, '拒绝重复知识库范围')
check('QuestionInput', {'question': '问题', 'knowledgeBaseIds': ['invalid']}, False, '拒绝非法标识')
check('QuestionInput', {'question': '问题', 'knowledgeBaseIds': [identifier], 'unknown': True}, False, '拒绝未知字段')
for value, accepted in [({'action': 'keep'}, True), ({'action': 'clear'}, True), ({'action': 'replace', 'value': '非真实示例凭据'}, True), ({'action': 'replace'}, False), ({'action': 'keep', 'value': '非真实示例凭据'}, False)]:
    check('CredentialChange', value, accepted, '凭据三态边界：' + value['action'] + str(accepted))
for key, accepted in [('user_note', True), ('fileId', False)]:
    config = {'strategy': 'structure', 'overlapCodePoints': 200, 'removePageHeadersFooters': False, 'customMetadata': {key: '示例'}, 'embeddedMetadataFields': []}
    check('ChunkingConfig', config, accepted, '自定义元数据保护：' + key)

report = {
    '类型': '接口设计静态校验，不是实现对齐报告',
    '执行时间': datetime.now(timezone.utc).isoformat(),
    '契约路径': str(source),
    '契约版本': document['info']['version'],
    '契约SHA256': hashlib.sha256(raw).hexdigest(),
    '工具版本': {name: importlib.metadata.version(name) for name in ['openapi-spec-validator', 'jsonschema', 'referencing']},
    '路径数': len(document['paths']),
    '操作数': len(operations),
    '结构数': len(document['components']['schemas']),
    '引用数': references,
    '已验证内置样例数': examples,
    '检查': ['标准OpenAPI3.1校验', '全部结构的JSON模式合法性', '全部内部引用解析', '操作标识唯一', '路径参数完整', '会话安全与写请求保护', '事件结构定义', '重复操作与缺失引用反向自检'],
    '边界样例': cases,
    '操作清单': operations,
    '结论': '静态校验通过',
    '实现对齐率': None,
    '未验证': ['正式前后端业务实现', '真实接口提供方与消费方行为', '构建与持续集成', '运行期边界与竞态', '真实模型调用与性能效果'],
}
destination = Path('.scratch/technical-design/contract-design-validation')
destination.mkdir(parents=True, exist_ok=True)
(destination / 'static-report.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
print(json.dumps({key: report[key] for key in ['契约SHA256', '路径数', '操作数', '结构数', '引用数', '已验证内置样例数', '结论']}, ensure_ascii=False))
print('边界样例数：', len(cases))
