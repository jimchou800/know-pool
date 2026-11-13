"""将同一实现的真实成功路由与逐路由安全拒绝关联，不重放业务写入。"""

import http.client
import json
import re
import sys
import xml.etree.ElementTree as ET
from urllib.parse import parse_qs, urlsplit

import verify


SOURCES = (
    'provider-evidence.json', 'provider-settings-evidence.json',
    'provider-knowledge-bases-evidence.json', 'provider-indexing-evidence.json',
    'provider-indexing-events-evidence.json',
)
READ_METHODS = {'GET', 'HEAD', 'OPTIONS'}
REQUIRED_TESTS = {
    'cn.knowpool.LocalApplicationTest': (
        'sessionAndPersistentStatusWorkOverHttp',
        'invalidSessionsAndUnknownParametersReturnContractErrors',
        'rejectUntrustedOriginsAndHostsAndAllowConfiguredOrigins',
        'rejectUnprotectedWritesAndHideUnknownRoutes',
    ),
    'cn.knowpool.system.api.LocalSessionFilterTest': (
        'renewExpiredSessionsAndBoundSessionCapacity',
        'rejectMissingOrUntrustedHostsBeforeBusinessHandling',
    ),
    'cn.knowpool.TaskEventsHttpTest': (
        'terminalSnapshotCanBeResumed',
        'boundedSubscriptionsReleaseOnDisconnectAndEmitHeartbeat',
    ),
}


def route_pattern(template):
    return '^' + ''.join('[^/]+' if part.startswith('{') else re.escape(part)
                         for part in re.split(r'(\{[^}]+\})', template)) + '$'


def request_path(raw):
    parsed = urlsplit(raw)
    if parsed.scheme or parsed.netloc or parsed.fragment or not parsed.path.startswith('/'):
        raise ValueError('成功请求必须是本机接口的相对路径。')
    if any(re.search('token|credential|secret|session|csrf', key, re.I)
           for key in parse_qs(parsed.query, keep_blank_values=True)):
        raise ValueError('成功请求不得在查询参数中传递敏感信息。')
    return raw if parsed.path.startswith('/api/v1/') else '/api/v1' + raw


def collect_routes(document, reports):
    """只从真实成功观察出发，同时核对契约身份与实际注册路由。"""
    operations = verify.inventory(document)
    discovered = []
    for source, report in reports:
        for route in report['routes']:
            if route not in discovered:
                discovered.append(route)
    proven = {}
    for source, report in reports:
        for test in report['tests']:
            if test.get('passed') is not True or not 200 <= test.get('response', {}).get('status', 0) < 300:
                continue
            request = test.get('request', {})
            if not request.get('path') or not request.get('method'):
                continue
            path = request_path(request['path'])
            pathname = urlsplit(path).path
            method = request['method']
            matches = [name for name, operation in operations.items()
                       if operation['method'] == method
                       and re.fullmatch(route_pattern('/api/v1' + operation['path']), pathname)]
            if len(matches) != 1 or matches[0] not in test['operationIds']:
                raise ValueError('成功观察的具体路径与声明操作不一致。')
            name = matches[0]
            if name not in verify.SCOPE:
                continue
            if not any(route['method'] == method and re.fullmatch(route_pattern(route['path']), pathname)
                       for route in discovered):
                raise ValueError('成功观察缺少实际注册路由。')
            proven.setdefault(name, {
                'method': method, 'path': path,
                'success': {'file': source, 'test': test['name'], 'status': test['response']['status']},
            })
    if set(proven) != set(verify.SCOPE):
        raise ValueError('实际成功访问未覆盖当前范围：' + ', '.join(sorted(set(verify.SCOPE) - set(proven))))
    return proven, discovered


def prerequisites(root, reports):
    """共同前提必须来自本轮成功行为和无失败、跳过或重跑的指定测试。"""
    required = {'security.' + str(index) for index in range(1, 9)}
    found = {}
    for source, report in reports:
        for test in report['tests']:
            for identifier in set(test['constraintIds']) & required:
                if test.get('passed') is True:
                    found.setdefault(identifier, {'file': source, 'test': test['name']})
    if set(found) != required:
        raise ValueError('缺少共同安全前提的真实行为证据。')
    junit = []
    for classname, names in REQUIRED_TESTS.items():
        path = root / 'server/target/surefire-reports' / ('TEST-' + classname + '.xml')
        suite = ET.fromstring(path.read_bytes())
        if suite.get('name') != classname or any(int(suite.get(key, '0')) for key in ('failures', 'errors', 'skipped', 'flakes')):
            raise ValueError('共同安全前提的测试套件存在失败、跳过或重跑。')
        for name in names:
            cases = [case for case in suite.findall('testcase')
                     if case.get('name') == name and case.get('classname') == classname]
            if len(cases) != 1 or any(child.tag in {'failure', 'error', 'skipped', 'flakyFailure', 'flakyError', 'rerunFailure', 'rerunError'}
                                      for child in cases[0]):
                raise ValueError('共同安全前提缺少指定成功测试：' + classname + '.' + name)
            junit.append({'file': path.relative_to(root).as_posix(), 'test': name,
                          'sha256': verify.digest(path.read_bytes())})
    return {'behaviors': found, 'junit': junit}


def exchange(base_url, method, path, headers):
    base = urlsplit(base_url)
    if base.scheme != 'http' or base.hostname != '127.0.0.1' or not base.port or base.path not in ('', '/'):
        raise ValueError('安全验证只允许当前回环测试应用。')
    connection = http.client.HTTPConnection(base.hostname, base.port, timeout=5)
    try:
        connection.request(method, path, headers=headers)
        response = connection.getresponse()
        body = response.read(65537)
        if len(body) > 65536:
            raise ValueError('安全验证响应超过固定预算。')
        return response.status, {name.lower(): value for name, value in response.getheaders()}, json.loads(body)
    finally:
        connection.close()


def reject(base_url, method, path, headers, status, code, validator):
    actual, returned, body = exchange(base_url, method, path, headers)
    if actual != status or body.get('code') != code:
        raise ValueError('具体已实现路由未按预期拒绝安全请求：' + method + ' ' + path)
    validator.validate(body)
    if (returned.get('content-type', '').split(';', 1)[0] != 'application/json'
            or returned.get('cache-control') != 'no-store'
            or returned.get('x-content-type-options') != 'nosniff'
            or returned.get('x-request-id') != body['requestId']):
        raise ValueError('安全拒绝响应媒体、保护头或请求身份不一致。')
    return {'status': actual, 'code': code}


def generate(base_url, root=verify.ROOT):
    contract = root / 'docs/agents/api/openapi.yaml'
    document = json.loads(contract.read_bytes())
    contract_hash = verify.digest(contract.read_bytes())
    implementation_hash = verify.snapshot()[0]
    reports = []
    for filename in SOURCES:
        path = root / 'server/target/contract' / filename
        reports.append((path.relative_to(root).as_posix(),
                        verify.read_evidence(path, contract_hash, implementation_hash)))
    proven, routes = collect_routes(document, reports)
    common = prerequisites(root, reports)
    sys.path.insert(0, str(root / '.runtime/contract-tools'))
    from jsonschema import Draft202012Validator, FormatChecker
    validator = Draft202012Validator({'$ref': '#/components/schemas/Error', 'components': document['components']},
                                     format_checker=FormatChecker())
    status, headers, session = exchange(base_url, 'GET', '/api/v1/session', {})
    cookie = headers.get('set-cookie', '')
    if (status != 200 or not {'sessionId', 'csrfToken', 'expiresAt'} <= set(session)
            or not all(value in cookie for value in ('KNOW_POOL_SESSION=', 'HttpOnly', 'SameSite=Strict', 'Path=/'))
            or 'Secure' in cookie):
        raise ValueError('本轮公共会话初始化或保护属性未通过。')
    cookie = cookie.split(';', 1)[0]
    tests = []
    for name, route in sorted(proven.items()):
        method, path = route['method'], route['path']
        checks = {'origin': reject(base_url, method, path, {'Cookie': cookie, 'Origin': 'https://untrusted.invalid'},
                                   403, 'ORIGIN_REJECTED', validator)}
        if name == 'initializeSession':
            checks['publicSession'] = {'status': status, 'cookieAttributesVerified': True}
        else:
            checks['anonymous'] = reject(base_url, method, path, {}, 401, 'SESSION_EXPIRED', validator)
        if method not in READ_METHODS:
            checks['csrf'] = reject(base_url, method, path, {'Cookie': cookie}, 403, 'CSRF_REJECTED', validator)
        tests.append({
            'name': '真实提供方路由安全保护：' + name, 'passed': True,
            'operationIds': [name], 'constraintIds': ['#/x-business-constraints/security'],
            'request': {'method': method, 'path': path}, 'successEvidence': route['success'],
            'rejections': checks, 'prerequisites': common,
            'assertions': ['同一实现真实成功观察与实际注册路由一致；逐具体路径校验来源、适用的匿名和写保护拒绝。',
                           '共享主机、回环、会话属性、过期、脱敏及完整事件断线行为均引用本轮原始成功证据。',
                           '安全验证不重放业务正文、幂等键或收费模型请求，报告不保存 Cookie、令牌及响应正文。'],
        })
    if verify.snapshot()[0] != implementation_hash:
        raise ValueError('安全验证期间实现已变化，拒绝生成跨快照证据。')
    report = {'contractSha256': contract_hash, 'implementationSha256': implementation_hash,
              'routes': routes, 'tests': tests,
              'sourceReports': [{'file': source, 'sha256': verify.digest((root / source).read_bytes())}
                                for source, _ in reports]}
    output = root / 'server/target/contract/provider-security-evidence.json'
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
    print('真实提供方安全路由已核验：' + str(len(tests)))


if __name__ == '__main__':
    generate(sys.argv[1])
