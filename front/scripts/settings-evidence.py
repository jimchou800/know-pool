"""仅把实际通过客户端消费的请求响应分支写入脱敏证据。"""

import hashlib
import importlib.util
import json
from datetime import datetime, timezone
from pathlib import Path
import sys

root = Path(__file__).resolve().parents[2]
for directory in (root / '.runtime/contract-tools', root.parent.parent / '.runtime/contract-tools'):
    if directory.exists():
        sys.path.insert(0, str(directory))
from jsonschema import Draft202012Validator, FormatChecker

module = importlib.util.spec_from_file_location('contract_verify', root / 'scripts/contract/verify.py')
verification = importlib.util.module_from_spec(module)
module.loader.exec_module(verification)
document = json.loads((root / 'docs/agents/api/openapi.yaml').read_bytes())
inventory = verification.inventory(document)


def validator(schema):
    return Draft202012Validator({**schema, 'components': document['components']}, format_checker=FormatChecker())


def reached(schema, pointer, value, found, visited=None):
    visited = set() if visited is None else visited
    if '$ref' in schema:
        reference = schema['$ref']
        if reference not in visited:
            visited.add(reference)
            reached(verification.resolve(document, reference), reference, value, found, visited)
    for key, child in schema.items():
        here = pointer + '/' + verification.escape(key)
        if key in verification.KEYWORDS and key not in ('then', 'else'):
            found.add(here)
        if key == 'properties' and isinstance(value, dict):
            for name, part in child.items():
                if name in value:
                    if name in verification.KEYWORDS:
                        found.add(here + '/' + verification.escape(name))
                    reached(part, here + '/' + verification.escape(name), value[name], found, visited)
        elif key == 'propertyNames' and isinstance(value, dict):
            for name in value:
                reached(child, here, name, found, set(visited))
        elif key == 'additionalProperties' and isinstance(child, dict) and isinstance(value, dict):
            for name, item in value.items():
                if name not in schema.get('properties', {}):
                    reached(child, here, item, found, set(visited))
        elif key == 'items' and isinstance(value, list):
            for item in value:
                reached(child, here, item, found, set(visited))
        elif key in ('oneOf', 'anyOf', 'allOf'):
            for index, part in enumerate(child):
                if key == 'allOf' or validator(part).is_valid(value):
                    reached(part, here + '/' + str(index), value, found, set(visited))
        elif key == 'if':
            reached(child, here, value, found, set(visited))
            branch = 'then' if validator(child).is_valid(value) else 'else'
            if branch in schema:
                found.add(pointer + '/' + branch)
                reached(schema[branch], pointer + '/' + branch, value, found, set(visited))
        elif key == 'not' and not validator(child).is_valid(value):
            reached(child, here, value, found, set(visited))


def rejected_field(schema, pointer, field, value, found):
    if '$ref' in schema:
        pointer = schema['$ref']
        schema = verification.resolve(document, pointer)
    child = schema.get('properties', {}).get(field)
    if child is not None and validator(child).is_valid(value):
        reached(child, pointer + '/properties/' + verification.escape(field), value, found)
    for index, part in enumerate(schema.get('allOf', [])):
        rejected_field(part, pointer + '/allOf/' + str(index), field, value, found)


def validate_response(schema, response):
    if not validator(schema).is_valid(response):
        raise AssertionError('消费响应不符合契约。')


def self_check():
    found = set()
    schema = {'type': 'object', 'properties': {'type': {'type': 'string', 'enum': ['file', 'dataset']}}}
    reached(schema, '#/test', {'type': 'file'}, found)
    assert '#/test/properties/type' in found
    assert '#/test/properties/type/enum' in found
    found = set()
    reached(schema, '#/test', {}, found)
    assert not any(pointer.startswith('#/test/properties/type') for pointer in found)
    found = set()
    reached({'type': 'object', 'propertyNames': {'pattern': '^user_'}, 'additionalProperties': {'type': 'string', 'maxLength': 2000}}, '#/test', {'user_topic': '内容'}, found)
    assert '#/test/propertyNames/pattern' in found
    assert '#/test/additionalProperties/maxLength' in found
    found = set()
    reached({'type': 'object', 'propertyNames': {'pattern': '^user_'}, 'additionalProperties': {'type': 'string'}}, '#/test', {}, found)
    assert '#/test/propertyNames/pattern' not in found
    found = set()
    reached({'$ref': '#/components/schemas/CredentialChange'}, '#/test', {'action': 'clear'}, found)
    assert not any(pointer.startswith('#/components/schemas/ReplaceCredential/') for pointer in found)
    found = set()
    reached({'anyOf': [{'type': 'integer', 'minimum': 1}, {'type': 'null'}]}, '#/test', None, found)
    assert '#/test/anyOf/0/type' not in found
    assert '#/test/anyOf/1/type' in found
    found = set()
    reached({'if': {'type': 'null'}, 'then': {'type': 'null'}, 'else': {'type': 'integer'}}, '#/test', None, found)
    assert '#/test/then/type' in found
    assert '#/test/else' not in found
    found = set()
    rejected_field({'allOf': [{'$ref': '#/components/schemas/ModelConfigInput'}]}, '#/test', 'contextWindowTokens', None, found)
    assert '#/components/schemas/ModelConfigInput/properties/contextWindowTokens/anyOf/1/type' in found
    assert not any('/allOf/' in pointer or '/anyOf/0/' in pointer for pointer in found)
    try:
        validate_response({'type': 'object', 'required': ['value']}, {})
    except AssertionError:
        pass
    else:
        raise AssertionError('反向自检未拒绝缺少必填字段的响应。')


self_check()
ticket = sys.argv[1] if len(sys.argv) > 1 else '06'
assert ticket in ('06', '07')
domain = {'06': '设置', '07': '知识库'}[ticket]
tests = []
for index, item in enumerate(json.load(sys.stdin)):
    name = item['operationId']
    operation = inventory[name]
    method = item['method'].lower()
    assert method.upper() == operation['method']
    expected = operation['path'].split('/')
    actual = item['url'].split('/')
    assert len(actual) == len(expected)
    assert all(left == right or left.startswith('{') for left, right in zip(expected, actual))
    definition = document['paths'][operation['path']][method]
    prefix = '#/paths/' + verification.escape(operation['path']) + '/' + method
    if item.get('rejectedInput'):
        schema = definition['requestBody']['content']['application/json']['schema']
        assert not validator(schema).is_valid(item['input'])
        found = set()
        rejected_field(schema, prefix + '/requestBody/content/application~1json/schema', item['field'], item['input'][item['field']], found)
        tests.append({'name': domain + '：' + name + '：调用方拒绝空生成预算：' + item['field'], 'passed': True, 'operationIds': [name],
                      'constraintIds': sorted(found & set(operation['constraints'])),
                      'assertions': ['实际提交空预算后客户端拒绝，断言无Axios请求；非法输入经正式契约校验，秘密和正文不输出。']})
        continue
    status = str(item['status'])
    response_definition = definition['responses'].get(status)
    if response_definition is None:
        continue
    found = {prefix + '/method', prefix + '/path', prefix + '/parameters', prefix + '/security', prefix + '/responses/' + status}
    pointer = prefix + '/responses/' + status
    if '$ref' in response_definition:
        pointer = response_definition['$ref']
        response_definition = verification.resolve(document, pointer)
    schema = response_definition['content']['application/json']['schema']
    validate_response(schema, item['response'])
    reached(schema, pointer + '/content/application~1json/schema', item['response'], found)
    if 'requestBody' in definition:
        request = json.loads(item['body']) if item['body'] is not None else None
        schema = definition['requestBody']['content']['application/json']['schema']
        if validator(schema).is_valid(request):
            found.update((prefix + '/requestBody', prefix + '/requestBody/required'))
            reached(schema, prefix + '/requestBody/content/application~1json/schema', request, found)
        elif item['status'] < 400:
            raise AssertionError('消费成功请求正文不符合契约：' + name)
    headers = {key.lower(): value for key, value in item['headers'].items()}
    for number, parameter in enumerate(definition.get('parameters', [])):
        pointer = prefix + '/parameters/' + str(number)
        if '$ref' in parameter:
            pointer = parameter['$ref']
            parameter = verification.resolve(document, pointer)
        parameter_name = parameter['name']
        value = (headers.get(parameter_name.lower()) if parameter['in'] == 'header'
                 else item['params'].get(parameter_name) if parameter['in'] == 'query'
                 else actual[expected.index('{' + parameter_name + '}')])
        if value is None:
            if parameter.get('required'):
                raise AssertionError('实际消费请求缺少必填参数：' + name + ':' + parameter_name)
            if 'required' in parameter:
                found.add(pointer + '/required')
            continue
        schema = parameter.get('schema', {})
        if not validator(schema).is_valid(value):
            raise AssertionError('实际消费请求参数不符合契约：' + name + ':' + parameter_name)
        if 'required' in parameter:
            found.add(pointer + '/required')
        reached(schema, pointer + '/schema', value, found)
    tests.append({'name': domain + '：' + name + '：实际消费观察' + str(index + 1), 'passed': True,
                  'operationIds': [name], 'constraintIds': sorted(found & set(operation['constraints'])),
                  'requests': [{'method': method.upper(), 'path': '/api/v1' + item['url'],
                                'hasProtection': 'x-csrf-token' in headers,
                                'hasIdempotencyKey': 'idempotency-key' in headers}],
                  'assertions': ['实际Axios请求与实际传入客户端的响应经标准校验，仅记录触发分支，不输出正文、秘密或令牌。']})

report = {'timestamp': datetime.now(timezone.utc).isoformat(),
          'contractSha256': hashlib.sha256((root / 'docs/agents/api/openapi.yaml').read_bytes()).hexdigest(),
          'implementationSha256': verification.snapshot()[0],
          'scope': list(dict.fromkeys(test['operationIds'][0] for test in tests)), 'tests': tests}
output = root / f'front/artifacts/contract-consumer-{ticket}.json'
output.parent.mkdir(parents=True, exist_ok=True)
output.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
