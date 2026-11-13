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
    'indexRetryCost': ({'indexRetryEligibilityAndHistoryStayAtomic'}, {'retryKnowledgeBaseFile','retryFailedKnowledgeBaseFiles'},
        '#/x-business-constraints/cost', '显式重试可能重新嵌入，原失败保持且新任务关联原失败；同键原结果和活动任务均不重复外发，成功及排除项不再嵌入，非重试凭据错误只发一次。'),
    'configurationRebuildVersions': ({'configurationRebuildPublicationAndHistoryStayIndependent'}, {'updateProcessingConfig','getTask','listKnowledgeBaseFiles','listIndexSwitches'},
        '#/x-business-constraints/indexSwitch', '整库目标和生效分离，构建期间继续旧版本，成功统一切换，失败旧索引可检索；历史配置归属不可串库，密钥替换身份不变不重建，端点变更同模型名仍用新身份重新嵌入。'),
    'configurationRebuildPublication': ({'configurationRebuildPublicationAndHistoryStayIndependent'}, {'updateProcessingConfig','getKnowledgeBase','listKnowledgeBaseFiles'},
        '#/x-business-constraints/publication', '已发布配置重建保持已发布，期间主动下线在成功切换后仍下线；失败不丢旧索引或发布决定，空库重建不凭空发布。'),
    'conversationVersions': ({'conversationHistoryScopeAndReferenceReadsStayBound','conversationCancellationDeletionAndLateResultsStayIsolated'}, {'submitQuestion','listConversationTurns','getQuestionTask','getConversationEvidence'},
        '#/x-business-constraints/indexSwitch', '追问重新检索当前生效版本，完整历史只含问题和最终回答，不带旧证据；逐轮范围不被默认范围修改，引用快照历史名称正文不变，当前名称和旧原文可用性读取时统一重查。'),
    'conversationPublication': ({'conversationHistoryScopeAndReferenceReadsStayBound','conversationCancellationDeletionAndLateResultsStayIsolated'}, {'submitQuestion'},
        '#/x-business-constraints/publication', '每轮显式提交范围，已发布知识库才能参与当轮检索，默认范围可空但不改写旧轮次。'),
    'conversationCost': ({'conversationHistoryScopeAndReferenceReadsStayBound','conversationCancellationDeletionAndLateResultsStayIsolated'}, {'submitQuestion','deleteConversation'},
        '#/x-business-constraints/cost', '追问同键复用原任务，同会话活动冲突不额外排队；取消后可再提交但外部调用仍全局限流，删除本地原子清理不调用模型，迟到和重启不复活。'),
    'questionVersions': ({'groundedQuestionBudgetsValidationAndSnapshotsStayBound','groundedQuestionFixedVersionsCancellationAndEventsStaySafe'}, {'submitFirstQuestion','getQuestionTask','getConversationEvidence'},
        '#/x-business-constraints/indexSwitch', '问答预处理前固定生效版本，切换后仍引用捕获切片；范围位置引用标识及支持性通过才持久保存完整实际切片，取消校验不泄露草稿。'),
    'questionPublication': ({'groundedQuestionFixedVersionsCancellationAndEventsStaySafe'}, {'submitFirstQuestion'},
        '#/x-business-constraints/publication', '已开始问答按固定范围版本继续，期间主动下线不篡改既有请求，后续新请求必须重新检查。'),
    'questionCost': ({'groundedQuestionBudgetsValidationAndSnapshotsStayBound','groundedQuestionFixedVersionsCancellationAndEventsStaySafe'}, {'submitFirstQuestion','getQuestionTask','cancelQuestionTask'},
        '#/x-business-constraints/cost', '最多一次预处理、一次生成及一次校验，无依据不生成，同键返回原任务；重启不重发，取消不递归修订，缺用量与截断输出拒绝。'),
    'questionEvents': ({'groundedQuestionSharedQueueAndEventCapacityAreBounded','groundedQuestionBudgetsValidationAndSnapshotsStayBound','groundedQuestionFixedVersionsCancellationAndEventsStaySafe'}, {'watchQuestionTask','getQuestionTask'},
        '#/x-business-constraints/sse', '问答专用事件只有阶段和终态，不含回答或草稿，完成后查询原持久任务，重连不外发。'),
    'questionEventCursor': ({'groundedQuestionFixedVersionsCancellationAndEventsStaySafe'}, {'watchQuestionTask'},
        '#/paths/~1question-tasks~1{taskId}~1events/get/x-sse/lastEventIdHeader', '真实旧和未来合法游标终态可重发，零序号或其他任务身份拒绝。'),
    'questionEventReconnect': ({'groundedQuestionSharedQueueAndEventCapacityAreBounded','groundedQuestionFixedVersionsCancellationAndEventsStaySafe'}, {'watchQuestionTask'},
        '#/paths/~1question-tasks~1{taskId}~1events/get/x-sse/reconnect', '同一持久任务重连不重发模型请求，终态重复通知不泄露正文。'),
    'instanceLifecycleCost': ({'searchInstanceMetadataAndRestartKeepOnlyConditions','searchInstanceCancellationDeletionAndLateResultsStayIsolated'}, {'listSearchInstances','createSearchInstance','getSearchInstance','renameSearchInstance','updateSearchInstanceScope','deleteSearchInstance','cancelSearchRequest','submitSearch'},
        '#/x-business-constraints/cost', '范围保存、切换读取和重启不触发嵌入，重启只恢复条件，原请求和证据失效；取消和删除不重发，资料及其他实例不受影响。'),
    'instanceLifecycleVersions': ({'searchInstanceMetadataAndRestartKeepOnlyConditions','searchInstanceCancellationDeletionAndLateResultsStayIsolated'}, {'deleteSearchInstance','cancelSearchRequest','submitSearch','getSearchRequest'},
        '#/x-business-constraints/indexSwitch', '提交元数据失败不替换当前请求，删除事务失败不取消旧任务；成功删除使临时结果失效，取消及删除后的迟到响应不得复活实例。'),
    'multiSearchVersions': ({'multiSearchScopesFusionAndCostsAreExplicit','multiSearchLatestRequestAndQueueStayAtomic'}, {'submitSearch','getSearchRequest','getSearchEvidence'},
        '#/x-business-constraints/indexSwitch', '多库固定生效版本证据按来源位置去重融合，不比较跨模型原始分数；同实例新提交取消旧请求，旧响应不能覆盖新失败或复活结果。'),
    'multiSearchPublication': ({'multiSearchScopesFusionAndCostsAreExplicit','multiSearchLatestRequestAndQueueStayAtomic'}, {'submitSearch'},
        '#/x-business-constraints/publication', '只在实际开始时领取已发布范围，未知及下线库明确遗漏，排队中下线不可参与；部分继续且警示，全不可用任务失败不冒充空结果。'),
    'multiSearchCost': ({'multiSearchScopesFusionAndCostsAreExplicit'}, {'submitSearch'},
        '#/x-business-constraints/cost', '同配置同次请求只生成一次查询嵌入，同键恢复原请求不重复收费，异负载冲突不替换当前请求。'),
    'publicationStates': ({'publicationAndChecksKeepIndependentStates'}, {'publishKnowledgeBase','offlineKnowledgeBase','checkKnowledgeBase'},
        '#/x-business-constraints/publication', '真实门槛、发布幂等、主动和自动下线、恢复不自动发布及外部故障只改可用性均由接口逐项断言。'),
    'checkDoesNotRepeatCalls': ({'publicationAndChecksKeepIndependentStates'}, {'checkKnowledgeBase'},
        '#/x-business-constraints/cost', '固定中文短文本不发送资料正文，同键及活动不同键映射原任务，完成后查询和同键重放不重复外发。'),
    'contentVersionSwitch': ({'contentSwitchHistoryAndCancellationRemainBoundToVersions','runningSearchAndRebuildCancellationRejectLateResults'}, {
        'listIndexSwitches','cancelTask','downloadVersionFile','listKnowledgeBaseFiles','getTask'},
        '#/x-business-constraints/indexSwitch','公开接口核对同源多库独立切换、原子指针关系、旧版保留、取消幂等及目标版本正文下载；未受理的非法新文件不取消在途任务。'),
    'processingConfigurationSwitch': ({'preflightAndRebuildKeepVersionsAtomic'}, {
        'updateProcessingConfig', 'getChunkPreflight', 'getProcessingConfig', 'getKnowledgeBase', 'getTask', 'getSearchRequest'},
        '#/x-business-constraints/indexSwitch', '真实公开接口断言配置幂等任务、整库切换成功、后续失败保留旧生效版本及检索证据配置身份；预检读取不触发模型。'),
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
    event_path = '/question-tasks/{taskId}/events' if item['path'].startswith('/question-tasks/') or item['path'].startswith('/api/v1/question-tasks/') else '/tasks/{taskId}/events'
    cursor_schema = document['paths'][event_path]['get']['parameters'][1]['schema']
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
        terminal = value['state'] in document['paths'][event_path]['get']['x-sse']['terminalStates']
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
                           'getChunkPreflight', 'updateProcessingConfig', 'getProcessingConfig', 'getProcessingConfigVersion', 'retryKnowledgeBaseFile','retryFailedKnowledgeBaseFiles',
                           'removeKnowledgeBaseSource', 'listKnowledgeBaseExclusions', 'excludeKnowledgeBaseFile', 'includeKnowledgeBaseFile', 'listFileKnowledgeBases', 'addKnowledgeBaseSource', 'listKnowledgeBaseSources',
                           'listIndexSwitches','cancelTask','downloadVersionFile','previewVersionFile',
                           'publishKnowledgeBase','offlineKnowledgeBase','checkKnowledgeBase','getConnectionTestResult','submitFirstQuestion','getQuestionTask','cancelQuestionTask','watchQuestionTask','getConversationEvidence','listConversations','getConversation','renameConversation','updateConversationScope','listConversationTurns','submitQuestion','deleteConversation','createSearchInstance','submitSearch','listSearchInstances','getSearchInstance','renameSearchInstance','updateSearchInstanceScope','deleteSearchInstance','cancelSearchRequest',
                           'getSearchRequest', 'getSearchEvidence', 'uploadFiles', 'listKnowledgeBases',
                           'getKnowledgeBase', 'updateKnowledgeBase', 'listKnowledgeBaseFiles'}
    BEHAVIORS = {
        'indexRetryEligibilityAndHistoryStayAtomic': ('真实非重试错误直接停止，新任务关联旧失败且保留原失败事实，同键不重提、已有活动不重复、成功排除项不重做，批量逐项结果及非法正文查询标识会话保护拒绝。', {'retryKnowledgeBaseFile','retryFailedKnowledgeBaseFiles','getTask','listKnowledgeBaseTasks','listKnowledgeBaseFiles'}),
        'configurationRebuildPublicationAndHistoryStayIndependent': ('真实整库切换区分目标生效及历史不可变配置；仅换凭据无重建，端点相同模型名仍独立身份并重新嵌入；已发布处理中保持旧索引，主动下线后完成不重发布，失败继续旧版本服务，空库保持未发布，历史版本归属未知非法和会话拒绝。', {'getProcessingConfigVersion','updateProcessingConfig','getProcessingConfig','getKnowledgeBase','listKnowledgeBaseFiles','listIndexSwitches','getTask'}),
        'conversationHistoryScopeAndReferenceReadsStayBound': ('真实追问仅带预算内近期完整轮次，不摘要不截断且历史不携带旧证据；同键不外发，重名手名不覆盖、默认范围不改旧请求、提交时间轮次分页及最近交互会话分页，旧原文清理与文件改名后各读取动态事实一致，历史跨重启保存不重发。', {'listConversations','getConversation','renameConversation','updateConversationScope','listConversationTurns','submitQuestion','getQuestionTask','getConversationEvidence','getTask'}),
        'conversationCancellationDeletionAndLateResultsStayIsolated': ('真实同会话活动资格及同键原任务、取消释放资格且旧迟到不盖新任务；删除事务失败不取消，成功确认清理同键持久复用，任务和专属引用不可读取，通用任务脱敏状态保留，源资料与别会话不受影响，重启不复活不外发。', {'getConversation','submitQuestion','deleteConversation','getQuestionTask','cancelQuestionTask','getConversationEvidence','getTask'}),
        'groundedQuestionSharedQueueAndEventCapacityAreBounded': ('真实知识服务队列容量满时首问四二九且同键不重提；问答专用事件六十四订阅及下一订阅四二九，阶段到取消终态恢复无正文不重发。', {'submitFirstQuestion','getQuestionTask','cancelQuestionTask','watchQuestionTask'}),
        'groundedQuestionBudgetsValidationAndSnapshotsStayBound': ('真实首问持久受理、手动名称、原问题和额外查询、生成校验最多各一次、无依据不生成、部分缺口矛盾澄清、无效预处理回退，坏引用及模型截断缺用量不泄草稿，引用跨重启保存且同键不重发。', {'submitFirstQuestion','getQuestionTask','cancelQuestionTask','watchQuestionTask','getConversationEvidence','getTask'}),
        'groundedQuestionFixedVersionsCancellationAndEventsStaySafe': ('预处理前捕获全部生效版本，资料切换及主动下线后仍用旧快照；校验期间没有草稿，取消后迟到不发布，专用事件终态重连游标和安全拒绝不重发模型。', {'submitFirstQuestion','getQuestionTask','cancelQuestionTask','watchQuestionTask','getConversationEvidence','getTask'}),
        'searchInstanceMetadataAndRestartKeepOnlyConditions': ('真实实例允许重名、首查自动名及不覆盖手改，稳定分页、条件保存、事务失败不改旧指针；重启恢复条件不恢复正文或自动外发，原结果证据四一零，确认删除及同键原清理任务。', {'listSearchInstances','createSearchInstance','getSearchInstance','renameSearchInstance','updateSearchInstanceScope','deleteSearchInstance','cancelSearchRequest','submitSearch','getSearchRequest','getSearchEvidence','getTask'}),
        'searchInstanceCancellationDeletionAndLateResultsStayIsolated': ('切换读取不互相取消，显式取消保留已核范围；删除事务失败保留原请求，成功后任务取消及请求失效，迟到不复活，其他实例及资料保持，管理和普通终态取消幂等。', {'listSearchInstances','getSearchInstance','renameSearchInstance','updateSearchInstanceScope','deleteSearchInstance','cancelSearchRequest','getSearchRequest','getTask'}),
        'multiSearchScopesFusionAndCostsAreExplicit': ('真实最小实例和多库去重融合、固定身份、部分范围警示、全部故障与空命中区分、同键只嵌入一次及非法安全拒绝。', {'createSearchInstance','submitSearch','getSearchRequest','getSearchEvidence','getTask'}),
        'multiSearchLatestRequestAndQueueStayAtomic': ('真实同实例新提交取消旧请求，满队列拒绝不取消当前请求，排队期间下线重新核资格，旧迟到不能恢复结果。', {'createSearchInstance','submitSearch','getSearchRequest','getTask'}),
        'publicationAndChecksKeepIndependentStates': ('真实发布门槛、幂等发布下线、排除后自动下线、恢复不自动发布；收费确认、固定输入及活动异键合并、同键不重复调用，外部失败正常探测且发布状态保持。', {'publishKnowledgeBase','offlineKnowledgeBase','checkKnowledgeBase','getKnowledgeBase','getTask','getConnectionTestResult'}),
        'runningSearchAndRebuildCancellationRejectLateResults': ('运行中检索取消后等待后继请求完成，旧结果仍为空；整库父任务取消传播子任务，旧生效指针与切换记录保持，取消接口会话与保护令牌真实拒绝。', {'cancelTask','getTask','getSearchRequest','listIndexSwitches','listKnowledgeBaseTasks','getKnowledgeBase'}),
        'contentSwitchHistoryAndCancellationRemainBoundToVersions': ('实际内容版本、多库独立生效指针、完整前后切换关系、允许任务终态幂等取消与初始化类型拒绝，以及指定旧版下载正文和安全头均由真实接口断言。', {'listIndexSwitches','cancelTask','downloadVersionFile','listKnowledgeBaseFiles','getTask'}),
        'associationUnionExclusionsAndLateTasksStayConsistent': ('真实来源重叠不重复索引、保留单来源不下线、排除优先、无来源排除可见、取消排除无来源不排队、上传新增同步及覆盖后迟到任务不得复活；排除持久化跨重启仍有效。', {'removeKnowledgeBaseSource', 'listKnowledgeBaseExclusions', 'excludeKnowledgeBaseFile', 'includeKnowledgeBaseFile', 'listFileKnowledgeBases', 'addKnowledgeBaseSource', 'listKnowledgeBaseSources', 'listKnowledgeBaseFiles', 'getKnowledgeBase', 'getTask'}),
        'preflightAndRebuildKeepVersionsAtomic': ('预检真实码点、无损正文、硬切降级及完整性经断言；同键配置更新复用父任务、整库成功切换、失败继续检索旧版本，未知及错误类型预检拒绝。', {'getChunkPreflight', 'updateProcessingConfig', 'getProcessingConfig', 'getTask', 'getKnowledgeBase', 'listKnowledgeBaseTasks', 'getSearchRequest'}),
        'markdownLocationsRemainBoundToOriginalSource': ('真实Markdown标题、源行、段落、表格、代码、实际内容版本和完整证据读取，经固定原文逐项断言。', {'getTask', 'listKnowledgeBaseFiles', 'testKnowledgeBaseSearch', 'getSearchRequest', 'getSearchEvidence'}),
        'pdfLocationsKeepCrossPageSourceAndBlankPages': ('真实中文PDF全文提取、跨页句子和空白页保留原始页号，固定副本版本与证据一致。', {'getTask', 'listKnowledgeBaseFiles', 'testKnowledgeBaseSearch', 'getSearchRequest', 'getSearchEvidence'}),
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
    is_event = is_indexing and name in ('watchTask','watchQuestionTask') and item['status'] == 200
    is_binary = name in ('previewVersionFile','downloadVersionFile') and item['status'] == 200
    if not is_event and not is_binary and item['status'] != 204:
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
    elif item['status'] == 204:
        if item['response'] is not None or 'content' in response_definition:
            raise AssertionError('真实204响应必须无正文且契约不能声明响应内容。')
    elif is_binary:
        media = item['contentType'].split(';',1)[0].strip().lower()
        response_schema = response_definition['content'][media]['schema']
        validator(response_schema).validate(item['response'])
        keywords(response_schema,response_pointer + '/content/' + verification.escape(media) + '/schema',item['response'],found)
        headers = {key.lower():value for key,value in item.get('responseHeaders',{}).items()}
        for header, definition in response_definition.get('headers',{}).items():
            value = headers.get(header.lower())
            validator(definition['schema']).validate(value)
            pointer = response_pointer + '/headers/' + verification.escape(header)
            found.add(pointer)
            if definition.get('description'): found.add(pointer + '/description')
            keywords(definition['schema'],pointer + '/schema',value,found)
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
