import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { spawn, execFileSync } from 'node:child_process';
import { readFile, writeFile, mkdir } from 'node:fs/promises';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createHash, randomUUID } from 'node:crypto';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const normalize = (text) => text.replace(/[\s|\-]/gu, '');

export function mapEvidence(evidence, documents) {
  const seen = new Set();
  const ranked = [];
  const mappings = [];
  for (const item of evidence) {
    const document = documents.find((entry) => entry.fileId === item.fileId && entry.contentVersionId === item.contentVersionId);
    const units = document?.units.filter((unit) => normalize(item.text).includes(normalize(unit.text))) || [];
    const ids = units.length ? units.map((unit) => unit.id) : [`unknown:${item.id}`];
    mappings.push({ evidence: item, documentId: document?.id ?? null, matchedUnitIds: units.map((unit) => unit.id) });
    for (const id of ids) if (!seen.has(id)) { seen.add(id); ranked.push(id); }
  }
  return { ranked, mappings };
}

async function selfCheck() {
  const documents = [{ id: 'a', fileId: 'f', contentVersionId: 'v', units: [{ id: 'u', text: '实际正文' }] }];
  const evidence = [
    { id: 'unrelated', fileId: 'other', contentVersionId: 'v', text: '实际正文' },
    { id: 'stale', fileId: 'f', contentVersionId: 'old', text: '实际正文' },
    { id: 'hit', fileId: 'f', contentVersionId: 'v', text: '实际\n正文' },
    { id: 'duplicate', fileId: 'f', contentVersionId: 'v', text: '实际正文' },
  ];
  assert.deepEqual(mapEvidence(evidence, documents).ranked, ['unknown:unrelated', 'unknown:stale', 'u']);
  assert.equal(mapEvidence(evidence, documents).mappings.length, 4);
  const calls = [], server = createMockModel(calls);
  await new Promise((done) => server.listen(0, '127.0.0.1', done));
  const base = `http://127.0.0.1:${server.address().port}`;
  const send = async (method, path, body) => {
    const response = await fetch(base + path, { method, headers: { 'Content-Type': 'application/json' }, ...(body === undefined ? {} : { body: JSON.stringify(body) }) });
    return { status: response.status, body: await response.json() };
  };
  try {
    const collection = '/collections/kp_' + 'a'.repeat(32);
    assert.equal((await send('GET', collection)).status, 404);
    await send('PUT', collection, { vectors: { size: 3, distance: 'Cosine' } });
    assert.equal((await send('GET', collection)).body.result.config.params.vectors.size, 3);
    const points = [{ id: 'allowed', payload: { index_id: 'current' }, vector: [1, 0, 0] }, { id: 'old', payload: { index_id: 'stale' }, vector: [1, 0, 0] }];
    await send('PUT', collection + '/points', { points });
    const query = await send('POST', collection + '/points/query', { filter: { must: [{ key: 'index_id', match: { any: ['current'] } }] }, limit: 10 });
    assert.deepEqual(query.body.result.points.map((item) => item.id), ['allowed']);
    assert.equal((await send('POST', '/v1/embeddings', { model: '固定嵌入', input: ['甲', '乙'] })).body.data.length, 2);
    const messages = [{ role: 'system', content: '任务是根据本次原文作答' }, { role: 'user', content: JSON.stringify({ evidence: [{ id: 'actual', text: '实际候选原文' }], reference: '不可使用的参考答案' }) }];
    const answer = JSON.parse((await send('POST', '/v1/chat/completions', { model: '固定生成', messages })).body.choices[0].message.content);
    assert.ok(answer.text.includes('实际候选原文'));
    assert.ok(!answer.text.includes('不可使用的参考答案'));
    assert.deepEqual(answer.citationEvidenceIds, ['actual']);
    for (const instruction of ['任务是问题预处理', '任务是严格审查候选回答']) {
      messages[0].content = instruction;
      assert.equal((await send('POST', '/v1/chat/completions', { model: '固定生成', messages })).status, 200);
    }
    assert.equal((await send('GET', '/unknown')).status, 500);
    assert.ok(calls.some((item) => item.path === '/v1/embeddings'));
  } finally {
    await new Promise((done) => server.close(done));
  }
}

export function createMockModel(modelCalls) {
  const points = new Map(), collections = new Map();
  return createServer(async (request, response) => {
    try {
      const chunks = [];
      for await (const chunk of request) chunks.push(chunk);
      const body = chunks.length ? JSON.parse(Buffer.concat(chunks).toString()) : null;
      const path = new URL(request.url, 'http://127.0.0.1').pathname;
      let result;
      if (path === '/v1/embeddings') {
        const input = Array.isArray(body.input) ? body.input : [body.input];
        result = { object: 'list', model: body.model, data: input.map((_, index) => ({ object: 'embedding', index, embedding: [1, 0, 0] })), usage: { prompt_tokens: 4, total_tokens: 4 } };
      } else if (path === '/v1/chat/completions') {
        const instruction = body.messages.find((item) => item.role === 'system').content;
        const context = JSON.parse(body.messages.find((item) => item.role === 'user').content);
        let answer;
        if (instruction.includes('任务是问题预处理')) answer = { additionalQueries: [] };
        else if (instruction.includes('任务是严格审查候选回答')) answer = { supported: true };
        else {
          assert.ok(instruction.includes('任务是根据本次原文作答'));
          const evidence = context.evidence;
          answer = { outcome: 'answered', text: evidence.map((item) => `原文记载：${item.text}【${item.id}】`).join('\n'), citationEvidenceIds: evidence.map((item) => item.id), uncoveredQuestions: [], contradictions: [] };
        }
        result = { id: 'fixed-evaluation', object: 'chat.completion', model: body.model, choices: [{ index: 0, message: { role: 'assistant', content: JSON.stringify(answer) }, finish_reason: 'stop' }], usage: { prompt_tokens: 16, completion_tokens: 16, total_tokens: 32 } };
      } else {
        const match = /^\/collections\/(kp_[a-f0-9]{32})(?:\/points(\/query)?)?$/.exec(path);
        assert.ok(match, '向量替身收到未知路径');
        const name = match[1], saved = points.get(name) || new Map();
        if (path.endsWith('/query')) {
          const allowed = body.filter.must.find((item) => item.key === 'index_id').match.any;
          result = { status: 'ok', result: { points: [...saved.values()].filter((item) => allowed.includes(item.payload.index_id)).map((item) => ({ ...item, score: 0.9 })).slice(0, body.limit) } };
        } else if (path.endsWith('/points')) {
          if (request.method === 'PUT') {
            for (const item of body.points) saved.set(item.id, item);
            points.set(name, saved);
            result = { status: 'ok', result: { status: 'completed' } };
          } else result = { status: 'ok', result: body.ids.map((id) => saved.get(id)).filter(Boolean) };
        } else if (request.method === 'PUT') {
          collections.set(name, body.vectors);
          result = { status: 'ok', result: true };
        } else if (request.method === 'DELETE') {
          collections.delete(name);
          result = { status: 'ok', result: true };
        } else {
          response.statusCode = collections.has(name) ? 200 : 404;
          result = { status: collections.has(name) ? 'ok' : 'error', result: collections.has(name) ? { config: { params: { vectors: collections.get(name) } } } : null };
        }
      }
      modelCalls.push({ method: request.method, path, request: body, response: result });
      response.setHeader('Content-Type', 'application/json');
      response.end(JSON.stringify(result));
    } catch (error) {
      response.statusCode = 500;
      response.end(JSON.stringify({ error: String(error) }));
    }
  });
}

async function main() {
  const args = process.argv.slice(2);
  if (args[0] === '--self-check') { await selfCheck(); return; }
  const option = (name) => args[args.indexOf(name) + 1];
  if (!args.includes('--output')) throw new Error('必须指定新的输出目录。');
  assert.equal(execFileSync('git', ['branch', '--show-current'], { cwd: root, encoding: 'utf8' }).trim(), 'integration', '评测必须在根集成分支执行');
  const output = resolve(option('--output'));
  await mkdir(dirname(output), { recursive: true });
  await mkdir(output, { recursive: false });
  const save = (name, value) => writeFile(resolve(output, name), JSON.stringify(value, null, 2) + '\n');
  const suite = JSON.parse(await readFile(resolve(root, 'scripts/evaluation/suite.json'), 'utf8'));
  execFileSync('python', ['scripts/evaluation/materialize.py', '--output', resolve(output, 'materials')], { cwd: root });
  const manifest = JSON.parse(await readFile(resolve(output, 'materials/manifest.json'), 'utf8'));
  const implementation = execFileSync('python', ['scripts/contract/verify.py', '--snapshot'], { cwd: root, encoding: 'utf8' }).trim();
  const modelCalls = [], httpCalls = [];
  // 复用现有联调替身的固定向量、集合过滤和原文摘录语义，不模拟真实检索质量。
  const model = createMockModel(modelCalls);
  await new Promise((done) => model.listen(0, '127.0.0.1', done));
  const endpoint = `http://127.0.0.1:${model.address().port}`;
  const portServer = createServer();
  await new Promise((done) => portServer.listen(0, '127.0.0.1', done));
  const port = portServer.address().port;
  await new Promise((done) => portServer.close(done));
  const java = args.includes('--java') ? resolve(option('--java')) : 'java';
  const app = spawn(java, ['-Duser.name=验收样例账号', '-jar', resolve(root, 'server/target/server-0.1.0-SNAPSHOT.jar'), `--server.port=${port}`, '--server.address=127.0.0.1', `--know-pool.data-directory=${resolve(output, 'data')}`], { cwd: root, windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'] });
  const closed = new Promise((done) => app.once('close', done));
  let launchError;
  app.on('error', (error) => { launchError = error; });
  const logs = [];
  app.stdout.on('data', (value) => logs.push(value));
  app.stderr.on('data', (value) => logs.push(value));
  const base = `http://127.0.0.1:${port}/api/v1`;
  let csrf, cookie;
  const pause = () => new Promise((done) => setTimeout(done, 150));
  const api = async (method, path, body) => {
    const headers = { ...(cookie ? { Cookie: cookie } : {}), ...(csrf ? { 'X-CSRF-Token': csrf } : {}), ...(method === 'POST' ? { 'Idempotency-Key': randomUUID() } : {}) };
    const multipart = body instanceof FormData;
    if (body !== undefined && !multipart) headers['Content-Type'] = 'application/json';
    const start = performance.now();
    const response = await fetch(base + path, { method, headers, ...(body === undefined ? {} : { body: multipart ? body : JSON.stringify(body) }), signal: AbortSignal.timeout(60000) });
    const result = await response.json();
    if (path === '/session') { csrf = result.csrfToken; cookie = response.headers.get('set-cookie').split(';')[0]; }
    // 会话和凭据仅驻内存，原始记录不保存保护头与敏感字段。
    const redact = (value) => JSON.parse(JSON.stringify(value, (key, item) => ['csrfToken', 'sessionId'].includes(key) ? '已脱敏' : key === 'credential' ? { action: item.action, value: '固定替身凭据已脱敏' } : item));
    httpCalls.push({ method, path, request: multipart ? { multipart: '固定资料文件，摘要见导入映射' } : body === undefined ? null : redact(body), status: response.status, response: redact(result), latencyMs: performance.now() - start });
    if (!response.ok) throw new Error(`${method} ${path}：${response.status} ${JSON.stringify(result)}`);
    return result;
  };
  const wait = async (path, nested = true) => {
    const deadline = Date.now() + 120000;
    while (Date.now() < deadline) {
      const result = await api('GET', path);
      const task = nested ? result.task : result;
      if (!['queued', 'running'].includes(task.state)) return result;
      await pause();
    }
    throw new Error('固定模拟任务等待超时');
  };
  const documents = [], results = [], observations = [];
  let resourceMeasurement = null;
  const started = performance.now();
  try {
    const deadline = Date.now() + 60000;
    while (true) {
      try { await api('GET', '/session'); break; } catch (error) {
        if (launchError) throw launchError;
        if (app.exitCode !== null || Date.now() > deadline) throw error;
        await pause();
      }
    }
    const limits = await api('GET', '/settings/runtime');
    limits.modelRateLimits = ['text-embedding-v4', 'fixed-question-model'].map((model) => ({ endpoint: endpoint + '/v1', model, maxConcurrent: 4, requestsPerMinute: 10000, tokensPerMinute: 10000000 }));
    await api('PUT', '/settings/runtime', limits);
    await api('PUT', '/settings/vector-store', { endpoint, credential: { action: 'clear' } });
    const embedding = await api('PUT', '/settings/models/embedding', { endpoint: endpoint + '/v1', model: 'text-embedding-v4', contextWindowTokens: null, maxOutputTokens: null, credential: { action: 'replace', value: 'fixed-evaluation' } });
    await api('PUT', '/settings/models/generation', { endpoint: endpoint + '/v1', model: 'fixed-question-model', contextWindowTokens: 131072, maxOutputTokens: 4096, credential: { action: 'replace', value: 'fixed-evaluation' } });
    const knowledgeBases = {};
    for (const group of [...new Set(manifest.map((item) => item.knowledgeBase))]) {
      const dataset = await api('POST', '/datasets', { name: '固定评测资料-' + group });
      for (const entry of manifest.filter((item) => item.knowledgeBase === group)) {
        const bytes = await readFile(resolve(output, 'materials', entry.path));
        const form = new FormData();
        form.append('files', new Blob([bytes], { type: entry.path.endsWith('.pdf') ? 'application/pdf' : entry.path.endsWith('.md') ? 'text/markdown' : 'text/plain' }), entry.path);
        const uploaded = await api('POST', `/datasets/${dataset.id}/files:upload`, form);
        const file = uploaded.items[0].file;
        assert.ok(file, '固定资料导入失败');
        documents.push({ ...suite.documents.find((item) => item.id === entry.documentId), fileId: file.id, contentVersionId: file.latestVersion.id, sha256: createHash('sha256').update(bytes).digest('hex'), datasetId: dataset.id });
      }
      const created = await api('POST', '/knowledge-bases', { name: '固定评测知识库-' + group, datasetIds: [dataset.id], processing: { chunking: { strategy: 'structure', overlapCodePoints: 200, removePageHeadersFooters: false, customMetadata: {}, embeddedMetadataFields: [] }, embedding: { modelConfigVersionId: embedding.versionId, dimensions: 3, distance: 'cosine', similarityThreshold: 0.5, candidatesPerQuery: 100, modelSnapshot: { endpoint: endpoint + '/v1', model: 'text-embedding-v4' } } } });
      const task = await wait('/tasks/' + created.task.id, false);
      assert.equal(task.state, 'succeeded');
      const id = task.resultResourceId;
      knowledgeBases[group] = id;
      const readyDeadline = Date.now() + 120000;
      while (true) {
        const files = (await api('GET', `/knowledge-bases/${id}/files?limit=100`)).items;
        assert.ok(!files.some((item) => item.updateState === 'failed'), '固定资料索引失败');
        if (files.length && files.every((item) => item.effectiveContentVersionId)) break;
        if (Date.now() > readyDeadline) throw new Error('固定资料索引等待超时');
        await pause();
      }
      await api('POST', `/knowledge-bases/${id}/publish`);
    }
    await save('documents.json', { knowledgeBases, documents });
    for (const item of suite.cases) {
      const start = performance.now(), firstCall = modelCalls.length;
      const scope = item.scope.map((name) => knowledgeBases[name]);
      try {
        const instance = await api('POST', '/search-instances');
        const accepted = await api('POST', `/search-instances/${instance.id}/requests`, { query: item.question, knowledgeBaseIds: scope });
        const search = await wait('/search-requests/' + accepted.id);
        assert.equal(search.task.state, 'succeeded', '固定题检索任务失败');
        const first = await api('POST', '/conversations/first-question', { question: item.question, knowledgeBaseIds: scope });
        const question = await wait('/question-tasks/' + first.questionTask.task.id);
        assert.equal(question.task.state, 'succeeded', '固定题问答任务失败');
        const mapped = mapEvidence(search.result?.evidence || [], documents);
        const references = [];
        for (const evidence of question.answer?.evidence || []) references.push(await api('GET', `/conversations/${first.conversation.id}/evidence/${evidence.id}`));
        observations.push({ caseId: item.id, search, question, ...mapped, references });
        const outcome = question.task.state !== 'succeeded' ? 'failed' : question.answer.outcome === 'no_evidence' ? 'no_answer' : question.answer.contradictions.length ? 'contradiction' : question.answer.outcome === 'needs_clarification' ? 'partial' : question.answer.outcome;
        results.push({ caseId: item.id, retrievedUnitIds: mapped.ranked, outcome, latencyMs: performance.now() - start, modelCalls: modelCalls.slice(firstCall).filter((call) => call.path.startsWith('/v1/')).length });
      } catch (error) {
        observations.push({ caseId: item.id, error: String(error) });
        results.push({ caseId: item.id, outcome: 'failed', latencyMs: performance.now() - start });
      }
    }
    if (process.platform === 'win32') {
      resourceMeasurement = JSON.parse(execFileSync('powershell.exe', ['-NoProfile', '-Command', `$sample=Get-Process -Id ${app.pid}; [ordered]@{peakWorkingSetBytes=$sample.PeakWorkingSet64; cpuMilliseconds=$sample.TotalProcessorTime.TotalMilliseconds} | ConvertTo-Json -Compress`], { encoding: 'utf8', windowsHide: true }));
    }
  } finally {
    app.kill();
    await closed;
    await new Promise((done) => model.close(done));
    await save('raw-http.json', httpCalls);
    await save('raw-model.json', modelCalls);
    await save('observations.json', observations);
    await save('resources.json', { mode: 'mock', applicationPid: app.pid, elapsedMs: performance.now() - started, measurement: resourceMeasurement, note: '固定小资料、单次串行模拟；进程峰值包含启动和导入，不是长期容量、并发基线或推荐限额。词元为替身固定值，不是实际分词测量。' });
    const hashes = {};
    for (const name of ['raw-http.json', 'raw-model.json', 'observations.json', 'resources.json']) hashes[name] = createHash('sha256').update(await readFile(resolve(output, name))).digest('hex');
    await save('run.json', { mode: 'mock', runId: 'fixed-mock-' + new Date().toISOString(), implementation, evidence: JSON.stringify(hashes), results });
    await writeFile(resolve(output, 'application.log'), Buffer.concat(logs));
  }
  assert.equal(execFileSync('python', ['scripts/contract/verify.py', '--snapshot'], { cwd: root, encoding: 'utf8' }).trim(), implementation, '运行期间实现输入发生变化，不能复用评测证据');
  execFileSync('python', ['scripts/evaluation/evaluate.py', '--input', resolve(output, 'run.json'), '--output', resolve(output, 'report')], { cwd: root, stdio: 'inherit' });
  assert.equal(observations.filter((item) => item.error).length, 0, '固定评测包含接口执行失败，原始证据已保存');
  console.log('固定中文模拟接口运行完成；真实质量、费用及推荐资源限额保持未验证。');
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) main().catch((error) => { console.error(error); process.exitCode = 1; });
