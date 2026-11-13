"""以真实请求响应验证正式结构，输出不含请求正文的提供方证据。"""

import hashlib
import importlib.util
import json
from pathlib import Path
import sys
from urllib.parse import parse_qs, urlsplit

root = Path(sys.argv[1])
for candidate in (root / '.runtime/contract-tools', root.parent.parent / '.runtime/contract-tools'):
    if candidate.exists():
        sys.path.insert(0, str(candidate))
from jsonschema import Draft202012Validator, FormatChecker

spec = importlib.util.spec_from_file_location('contract_verify', root / 'scripts/contract/verify.py')
verification = importlib.util.module_from_spec(spec)
spec.loader.exec_module(verification)
document = json.loads((root / 'docs/agents/api/openapi.yaml').read_bytes())
operations = verification.inventory(document)
observations = json.load(sys.stdin)
profile = sys.argv[2] if len(sys.argv) > 2 else 'settings'
tests = []
INDEXING_CHECKS = {
    'currentIndexVersions': ({'textIndexBecomesEffective'}, {
        'getTask', 'listKnowledgeBaseFiles', 'testKnowledgeBaseSearch', 'getSearchRequest', 'getSearchEvidence'},
        '#/x-business-constraints/indexSwitch', '真实生效版本、替换和失败保留旧索引经公开接口断言。'),
    'managementPublication': ({'textIndexBecomesEffective'}, {'testKnowledgeBaseSearch', 'getKnowledgeBase'},
        '#/x-business-constraints/publication', '未发布知识库可管理检索，索引成功不自动发布。'),
    'queryDoesNotRepeatCalls': ({'managementSearchFailureIsStableAndExpiresOnRestart', 'managementQueueAndRestartAreSafe'}, {'getTask', 'testKnowledgeBaseSearch'},
        '#/x-business-constraints/cost', '同键和原任务查询不重新外发，重启不自动恢复收费检索。'),
    'indexRetriesAreBounded': ({'textIndexBecomesEffective'}, {'getTask', 'testKnowledgeBaseSearch'},
        None, '后台索引可重试阶段最多三次含首次；此断言不证明恢复不会重复收费。'),
    'persistedEmbeddingNoRepeat': ({'persistedEmbeddingResumesWithoutModelCall'}, {'getTask'},
        '#/x-business-constraints/cost', '向量写入门控后关闭应用并重启，持久嵌入安全恢复成功，模型调用总数始终为一次。'),
    'eventReconnect': ({'terminalSnapshotCanBeResumed'}, {'watchTask', 'getTask'},
        '#/x-business-constraints/sse', '断线查询原任务，重订阅不改变任务或重新外发。'),
    'eventCursor': ({'terminalSnapshotCanBeResumed'}, {'watchTask'},
        '#/paths/~1tasks~1{taskId}~1events/get/x-sse/lastEventIdHeader', '合法旧及未来游标只返回当前快照，非法或异任务游标拒绝。'),
    'eventReconnectProtocol': ({'terminalSnapshotCanBeResumed', 'boundedSubscriptionsReleaseOnDisconnectAndEmitHeartbeat'}, {'watchTask'},
        '#/paths/~1tasks~1{taskId}~1events/get/x-sse/reconnect', '重连不重跑、不改变序号，终态快照可重发后关闭，心跳使用注释帧。'),
    'eventCookieSecurity': ({'terminalSnapshotCanBeResumed'}, {'watchTask'},
        'security.8', '真实Cookie事件会话、来源及失效会话拒绝，令牌不进URL，断线不重新受理。'),
    'eventCapacity': ({'boundedSubscriptionsReleaseOnDisconnectAndEmitHeartbeat'}, {'watchTask'},
        None, '真实订阅容量满时返回429，断线释放槽位，后续订阅可恢复。'),
}
BEHAVIORS = {
    'initialConfigurationSurvivesRestart': ('持久初值：两模型独立身份、空值、时间及重启保留，向量未设置状态。', {'getGenerationModel','getEmbeddingModel','getVectorConfig'}),
    'credentialAndVersionsAreIndependent': ('配置独立：三态、同源继承、跨源400原子拒绝、相同保存不造版本、历史清除不改当前版本、分页。', {'getEmbeddingModel','updateEmbeddingModel','getEmbeddingModelVersion','updateEmbeddingVersionCredential','listEmbeddingModelVersions'}),
    'embeddingProbeUsesSavedSnapshotAndIdempotency': ('固定嵌入负载、认证隔离、有限向量结构、同幂等键只外发一次及持久任务结果。', {'testEmbeddingModel','getTask','getConnectionTestResult'}),
    'probesRespectTransportBoundaries': ('受控外发：固定生成和向量协议，认证不跨主机，307目标零请求，断连、超时、超限及拒绝总尝试一次且成功探测不可用。', {'testGenerationModel','testVectorConfig','getTask','getConnectionTestResult'}),
    'queuedSnapshotsAndRestartRemainSafe': ('有界队列429、满队列同键原任务、重启排队及运行任务中断且不再外发、费用警示与结果409。', {'testEmbeddingModel','getTask','getConnectionTestResult'}),
    'preservesSnapshotAndValidatesTls': ('完整旧路径、模型、预算、凭据及配置版本在途固定；下一任务使用新配置；不可信TLS证书拒绝。', {'testGenerationModel','testVectorConfig','getTask','getConnectionTestResult'}),
    'localFailureAndResultIntegrityAreExplicit': ('本机完成持久化故障任务失败、结果409；成功缺结果500、错误类型404、读取不重测。', {'getTask','getConnectionTestResult','testVectorConfig'}),
    'rejectsInvalidConfigurationWithoutChanges': ('逐字段非法输入400且保存前后配置一致，精确保留掩码及跨源错误字段，生成预算版本与清除完整性。', {'updateGenerationModel','updateEmbeddingModel'}),
}

def validator(schema):
    return Draft202012Validator({**schema, 'components': document['components']}, format_checker=FormatChecker())

def validate_media(value):
    if value.split(';',1)[0].strip().lower() != 'application/json':
        raise AssertionError('真实响应媒体不符合契约。')

def behavior_assertions(item, name):
    behavior = BEHAVIORS.get(item.get('behavior'))
    return [behavior[0]] if behavior and name in behavior[1] else []


def indexing_assertions(item, name):
    """只接收已通过测试显式声明的对应行为，不由结构清单推断。"""
    found, assertions = set(), []
    for check in item.get('behaviorChecks', []):
        if check not in INDEXING_CHECKS:
            raise AssertionError('未声明的索引行为检查：' + str(check))
        methods, identifiers, pointer, explanation = INDEXING_CHECKS[check]
        if item.get('behavior') in methods and name in identifiers:
            if pointer:
                found.add(pointer)
            assertions.append(explanation)
    return found, assertions


def event_constraints(item, prefix):
    """核对真实事件帧及数据，只映射本次实际观察到的事件结构。"""
    if item['contentType'].split(';', 1)[0].strip().lower() != 'text/event-stream':
        raise AssertionError('真实事件媒体不符合契约。')
    if not isinstance(item['response'], str):
        raise AssertionError('真实事件响应必须保留原始帧。')
    found = {prefix + '/responses/200/content/text~1event-stream/schema/type'}
    task_id = item['path'].split('?', 1)[0].split('/')[-2]
    cursor_schema = document['paths']['/tasks/{taskId}/events']['get']['parameters'][1]['schema']
    count = 0
    normalized = item['response'].replace('\r\n', '\n').replace('\r', '\n')
    if not normalized.endswith('\n\n'):
        raise AssertionError('真实事件末帧不完整。')
    for frame in normalized.split('\n\n'):
        fields = {}
        for line in frame.split('\n'):
            if not line or line.startswith(':'):
                continue
            key, separator, value = line.partition(':')
            if not separator or key not in ('event', 'id', 'data') or key in fields:
                raise AssertionError('真实事件包含非法或重复字段。')
            fields[key] = value.removeprefix(' ')
        if not fields:
            continue
        if set(fields) != {'event', 'id', 'data'} or fields['event'] not in ('phase', 'terminal'):
            raise AssertionError('真实事件缺少身份、类型或数据。')
        value = json.loads(fields['data'])
        validator({'$ref': '#/components/schemas/TaskEvent'}).validate(value)
        validator(cursor_schema).validate(fields['id'])
        if value['taskId'] != task_id or fields['id'] != task_id + ':' + str(value['sequence']):
            raise AssertionError('真实事件身份或序号不一致。')
        terminal = value['state'] in document['paths']['/tasks/{taskId}/events']['get']['x-sse']['terminalStates']
        if (fields['event'] == 'terminal') != terminal:
            raise AssertionError('真实事件类型与任务终态不一致。')
        if count and previous_terminal:
            raise AssertionError('真实终态之后不得继续发送事件。')
        previous_terminal = terminal
        keywords({'$ref': '#/components/schemas/TaskEvent'}, '#/event', value, found)
        found.update({prefix + '/x-sse/events/' + fields['event'], prefix + '/x-sse/id',
                      prefix + '/x-sse/terminalStates'})
        count += 1
    if not count:
        raise AssertionError('真实事件响应没有阶段或终态帧。')
    return found

def error_field_constraints(schema, pointer, field, value, found):
    if '$ref' in schema:
        pointer = schema['$ref']
        schema = verification.resolve(document, pointer)
    child = schema.get('properties', {}).get(field)
    if child is not None and validator(child).is_valid(value):
        keywords(child, pointer + '/properties/' + verification.escape(field), value, found)
    for index, part in enumerate(schema.get('allOf', [])):
        error_field_constraints(part, pointer + '/allOf/' + str(index), field, value, found)

def parameter_constraints(schema, pointer, actual, required, status):
    found = set()
    if actual is None:
        if required and status < 400:
            raise AssertionError('真实成功请求缺少必填参数。')
        found.add(pointer + '/required')
    elif validator(schema).is_valid(actual):
        found.add(pointer + '/required')
        keywords(schema, pointer + '/schema', actual, found)
    elif status < 400:
        raise AssertionError('真实成功请求参数不符合契约。')
    return found


def keywords(schema, pointer, value, found, visited=None):
    visited = set() if visited is None else visited
    if not isinstance(schema, dict):
        return
    if '$ref' in schema:
        ref = schema['$ref']
        if ref not in visited:
            visited.add(ref)
            keywords(verification.resolve(document, ref), ref, value, found, visited)
    for key, child in schema.items():
        here = pointer + '/' + verification.escape(key)
        if key in verification.KEYWORDS and key not in ('then','else'):
            found.add(here)
        if key == 'properties' and isinstance(value, dict):
            for name, part in child.items():
                if name in value:
                    if name in verification.KEYWORDS:
                        found.add(here + '/' + verification.escape(name))
                    keywords(part, here + '/' + verification.escape(name), value[name], found, visited)
        elif key == 'propertyNames' and isinstance(value, dict):
            for name in value:
                validator(child).validate(name)
                keywords(child, here, name, found, set(visited))
        elif key == 'additionalProperties' and isinstance(child, dict) and isinstance(value, dict):
            for name, item in value.items():
                if name not in schema.get('properties', {}):
                    validator(child).validate(item)
                    keywords(child, here, item, found, set(visited))
        elif key == 'items' and isinstance(value, list):
            for item in value:
                keywords(child, here, item, found, set(visited))
        elif key in ('oneOf', 'anyOf', 'allOf'):
            for index, part in enumerate(child):
                if key == 'allOf' or validator(part).is_valid(value):
                    keywords(part, here + '/' + str(index), value, found, set(visited))
        elif key == 'if':
            keywords(child, here, value, found, set(visited))
            branch = 'then' if validator(child).is_valid(value) else 'else'
            if branch in schema:
                found.add(pointer + '/' + branch)
                keywords(schema[branch], pointer + '/' + branch, value, found, set(visited))
        elif key == 'not':
            if not validator(child).is_valid(value):
                keywords(child, here, value, found, set(visited))


def self_check():
    found = set()
    keywords({'$ref':'#/components/schemas/CredentialChange'}, '#/test', {'action':'clear'}, found)
    assert not any(pointer.startswith('#/components/schemas/ReplaceCredential/') for pointer in found)
    condition = {'if': {'properties':{'state':{'const':'failed'}}}, 'then':{'properties':{'failure':{'type':'object'}}}, 'else':{'properties':{'failure':{'type':'null'}}}}
    keywords(condition, '#/condition', {'state':'failed','failure':{}}, found)
    assert '#/condition/then/properties/failure/type' in found
    assert '#/condition/else/properties/failure/type' not in found
    assert not validator({'type':'integer','minimum':1}).is_valid(0)
    assert not validator({'type':'string','format':'uuid'}).is_valid('invalid')
    validate_media('application/json;charset=utf-8')
    for check in (lambda: validate_media('text/plain'),
                  lambda: parameter_constraints({'type':'string','format':'uuid'}, '#/parameter', None, True, 200),
                  lambda: parameter_constraints({'type':'string','format':'uuid'}, '#/parameter', 'invalid', True, 200)):
        try: check()
        except AssertionError: pass
        else: raise AssertionError('证据反向自检未拒绝无效输入。')
    assert '#/parameter/schema/format' not in parameter_constraints({'type':'string','format':'uuid'}, '#/parameter', None, False, 200)
    assert behavior_assertions({}, 'getVectorConfig') == []
    assert behavior_assertions({'behavior':'initialConfigurationSurvivesRestart'}, 'updateVectorConfig') == []
    assert behavior_assertions({'behavior':'initialConfigurationSurvivesRestart'}, 'getVectorConfig')
    found = set()
    keywords({'type':'object','properties':{'type':{'type':'string'}}}, '#/named', {'type':'dataset'}, found)
    assert '#/named/properties/type' in found
    assert '#/named/properties/type/type' in found
    found = set()
    keywords({'type':'object','properties':{'type':{'type':'string'}}}, '#/named', {}, found)
    assert '#/named/properties/type' not in found
    found = set()
    error_field_constraints({'allOf':[{'$ref':'#/components/schemas/ModelConfigInput'}]}, '#/request', 'contextWindowTokens', None, found)
    assert '#/components/schemas/ModelConfigInput/properties/contextWindowTokens/anyOf/1/type' in found
    assert '#/components/schemas/ModelConfigInput/properties/contextWindowTokens/anyOf/0/type' not in found


self_check()

selected_operations = {'getGenerationModel','updateGenerationModel','testGenerationModel',
                       'getEmbeddingModel','updateEmbeddingModel','testEmbeddingModel',
                       'listEmbeddingModelVersions','getEmbeddingModelVersion','updateEmbeddingVersionCredential',
                       'getConnectionTestResult','getVectorConfig','updateVectorConfig','testVectorConfig','getTask'}
if profile == 'knowledge-bases':
    selected_operations = {'listKnowledgeBases','createKnowledgeBase','getKnowledgeBase','updateKnowledgeBase',
                           'getProcessingConfig','addKnowledgeBaseSource','listKnowledgeBaseSources','listKnowledgeBaseFiles','getTask'}
    BEHAVIORS = {
        'emptyKnowledgeBaseSurvivesRestart': ('空库初始化完成才公开，配置快照和未发布状态跨重启保留。',selected_operations),
        'sourcesKeepUniqueQueuedIntent': ('两类来源独立保留、文件并集与持久排队任务唯一，重启不丢失且不执行索引。',selected_operations),
        'namesIdempotencyAndSnapshotsAreStable': ('名称唯一、同键原任务与异负载冲突，任务时间和历史模型身份及配置快照稳定。',selected_operations),
        'queueCapacityAndHiddenInitialization': ('真实队列满四二九无业务副作用，半初始化列表隐藏且详情不存在，同键复用。',selected_operations),
        'compensationResumesWithoutReplacingOriginalFailure': ('本机最终提交故障保留原失败，删除返回假值不算清理成功，重启恢复补偿。',selected_operations),
        'foreignAndUnknownCollectionsAreNeverDeleted': ('已有与结果不确定集合均不接管删除，原失败与幂等任务保留。',selected_operations),
        'rejectsInvalidInputWithoutSideEffects': ('非法结构、逐字段、用户元数据、重叠与快照身份校验无副作用，合法元数据持久保存。',selected_operations),
        'manyCompensationsDoNotBlockStartup': ('超过执行容量的补偿记录重启后有界处理，本地资料管理仍可用。',selected_operations),
        'everyOperationRejectsUnsafeRequests': ('全部操作真实会话和跨站保护，查询及标识边界，本机存储异常统一错误。',selected_operations),
    }
    found = set()
    keywords({'type':'object','propertyNames':{'pattern':'^user_[a-z]+$'},'additionalProperties':{'type':'string','maxLength':20}}, '#/metadata', {'user_name':'中文'}, found)
    assert '#/metadata/propertyNames/pattern' in found
    assert '#/metadata/additionalProperties/maxLength' in found

if profile in ('indexing', 'indexing-events', 'indexing-gaps'):
    selected_operations = {'getTask', 'watchTask', 'listKnowledgeBaseTasks', 'testKnowledgeBaseSearch',
                           'getSearchRequest', 'getSearchEvidence', 'uploadFiles', 'listKnowledgeBases',
                           'getKnowledgeBase', 'updateKnowledgeBase', 'listKnowledgeBaseFiles'}
    BEHAVIORS = {
        'textIndexBecomesEffective': ('真实文本索引、生效版本及管理结果身份与证据读取。',
            {'getTask', 'listKnowledgeBaseTasks', 'testKnowledgeBaseSearch', 'getSearchRequest', 'getSearchEvidence'}),
        'managementSearchFailureIsStableAndExpiresOnRestart': ('无索引异步失败、同键原请求、非法输入拒绝与重启410墓碑。',
            {'getTask', 'testKnowledgeBaseSearch', 'getSearchRequest', 'getSearchEvidence'}),
        'managementQueueAndRestartAreSafe': ('真实检索容量拒绝、同键不重提及重启在途请求中断和身份失效。',
            {'getTask', 'testKnowledgeBaseSearch', 'getSearchRequest', 'getSearchEvidence'}),
        'persistedEmbeddingResumesWithoutModelCall': ('向量写入中断后从持久嵌入检查点恢复，索引成功且模型调用总数不增加。',
            {'getTask', 'listKnowledgeBaseFiles'}),
        'terminalSnapshotCanBeResumed': ('真实阶段终态事件及恢复游标，订阅不改变持久状态序号。', {'watchTask'}),
        'boundedSubscriptionsReleaseOnDisconnectAndEmitHeartbeat': ('真实订阅容量、断线释放及注释心跳不改变序号，阶段和终态随任务状态变化。', {'watchTask'}),
        'everyOperationRejectsUnsafeRequests': ('真实会话及来源拒绝、未知查询、分页与标识边界和本机异常响应。',
            {'getTask', 'watchTask', 'listKnowledgeBaseTasks', 'testKnowledgeBaseSearch',
             'getSearchRequest', 'getSearchEvidence', 'uploadFiles', 'listKnowledgeBases',
             'getKnowledgeBase', 'updateKnowledgeBase', 'listKnowledgeBaseFiles'}),
    }
    if profile == 'indexing-events':
        selected_operations = {'watchTask', 'getTask'}


for index, item in enumerate(observations):
    path = item['path'].split('?', 1)[0].removeprefix('/api/v1')
    selected = None
    for name, operation in operations.items():
        if operation['method'] != item['method']:
            continue
        expected = operation['path'].split('/')
        actual = path.split('/')
        if len(expected) == len(actual) and all(a == b or a.startswith('{') for a, b in zip(expected, actual)):
            selected = name, operation
            break
    if selected is None or selected[0] not in selected_operations:
        continue
    name, operation = selected
    is_indexing = profile in ('indexing', 'indexing-events', 'indexing-gaps')
    is_event = is_indexing and name == 'watchTask' and item['status'] == 200
    if not is_event:
        validate_media(item['contentType'])
    method = item['method'].lower()
    prefix = '#/paths/' + verification.escape(operation['path']) + '/' + method
    definition = document['paths'][operation['path']][method]
    found = {prefix + '/method', prefix + '/path', prefix + '/security', prefix + '/parameters',
             prefix + '/responses/' + str(item['status'])}
    assertions = behavior_assertions(item, name)
    if is_indexing:
        behavior_found, behavior_notes = indexing_assertions(item, name)
        found.update(behavior_found)
        assertions.extend(behavior_notes)
    if definition.get('description') and assertions:
        found.add(prefix + '/description')
    response_definition = definition['responses'][str(item['status'])]
    if '$ref' in response_definition:
        ref = response_definition['$ref']
        response_definition = verification.resolve(document, ref)
        response_pointer = ref
    else:
        response_pointer = prefix + '/responses/' + str(item['status'])
    if is_event:
        found.update(event_constraints(item, prefix))
    else:
        response_schema = response_definition['content']['application/json']['schema']
        errors = list(validator(response_schema).iter_errors(item['response']))
        if errors:
            raise AssertionError('真实响应结构不符合契约：' + name + ':' + str(item['status']) + ':' + '/'.join(map(str, errors[0].schema_path)))
        keywords(response_schema, response_pointer + '/content/application~1json/schema', item['response'], found)
    if 'application/json' in definition.get('requestBody', {}).get('content', {}):
        found.add(prefix + '/requestBody')
        found.add(prefix + '/requestBody/required')
        request_schema = definition['requestBody']['content']['application/json']['schema']
        try:
            request = json.loads(item['body'])
        except (TypeError, ValueError):
            request = None
        if item['status'] < 400:
            validator(request_schema).validate(request)
        if validator(request_schema).is_valid(request):
            keywords(request_schema, prefix + '/requestBody/content/application~1json/schema', request, found)
        elif item['status'] == 400 and item.get('behavior') == 'rejectsInvalidConfigurationWithoutChanges':
            schema = request_schema
            schema_pointer = prefix + '/requestBody/content/application~1json/schema'
            if isinstance(request, dict):
                for error in item['response']['fieldErrors']:
                    field = error['field'].removeprefix('/')
                    if field in request:
                        error_field_constraints(schema, schema_pointer, field, request[field], found)
    actual_path = dict(zip([p[1:-1] for p in operation['path'].split('/') if p.startswith('{')],
                           [a for p,a in zip(operation['path'].split('/'),path.split('/')) if p.startswith('{')]))
    actual_query = parse_qs(urlsplit(item['path']).query, keep_blank_values=True)
    actual_headers = {k.lower(): v for k,v in item.get('parameters', {}).items()}
    for number, parameter in enumerate(definition.get('parameters', [])):
        pointer = prefix + '/parameters/' + str(number)
        if '$ref' in parameter:
            pointer = parameter['$ref']
            parameter = verification.resolve(document, pointer)
        location, parameter_name = parameter['in'], parameter['name']
        actual = actual_path.get(parameter_name) if location == 'path' else actual_headers.get(parameter_name.lower()) if location == 'header' else actual_query.get(parameter_name, [None])[0]
        schema = parameter.get('schema', {})
        if location == 'query' and schema.get('type') == 'integer' and actual is not None:
            try: actual = int(actual)
            except ValueError: pass
        found.update(parameter_constraints(schema,pointer,actual,parameter.get('required',False),item['status']))
    found &= set(operation['constraints']) | ({'security.8'} if is_indexing else set())
    request_label = '真实索引边界请求' if profile == 'indexing-gaps' else '真实事件请求' if profile == 'indexing-events' else '真实索引请求' if is_indexing else '真实知识库请求' if profile == 'knowledge-bases' else '真实设置请求'
    tests.append({'name': str(index + 1) + '：' + request_label + '：' + name + '：' + str(item['status']),
                  'passed': True, 'operationIds': [name], 'constraintIds': sorted(found),
                  'request': {'method': item['method'], 'path': path},
                  'response': {'status': item['status'], 'contentType': item['contentType']},
                  'assertions': ['真实HTTP媒体及响应结构经标准校验器核对；请求和参数仅映射实际满足的分支；正文和令牌不输出。'] + assertions})

report = {'contractSha256': hashlib.sha256((root / 'docs/agents/api/openapi.yaml').read_bytes()).hexdigest(),
          'implementationSha256': verification.snapshot()[0],
          'routes': observations[0]['routes'], 'tests': tests}
output = root / ('server/target/contract/provider-indexing-gaps-evidence.json' if profile == 'indexing-gaps' else 'server/target/contract/provider-indexing-events-evidence.json' if profile == 'indexing-events' else 'server/target/contract/provider-indexing-evidence.json' if profile == 'indexing' else 'server/target/contract/provider-knowledge-bases-evidence.json' if profile == 'knowledge-bases' else 'server/target/contract/provider-settings-evidence.json')
output.parent.mkdir(parents=True, exist_ok=True)
output.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
print('提供方证据完成：' + profile + '：' + str(len(tests)))
