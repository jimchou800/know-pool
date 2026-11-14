"""评估固定中文运行结果，不联网、不调用模型、不推测缺失指标。"""

import argparse
import hashlib
import json
import math
from pathlib import Path


ROOT = Path(__file__).resolve().parent
LABELS = {
    'recall': '证据召回率', 'reciprocalRank': '首个相关证据倒数排名',
    'coverage': '子问题证据覆盖率', 'accuracy': '事实准确率',
    'faithfulness': '原文忠实率', 'citationLocation': '引用可定位率',
    'citationSupport': '引用支持率', 'scopeLeakCount': '范围外证据数',
    'outcomeMatch': '回答类型匹配', 'latencyMs': '延迟毫秒',
    'modelCalls': '模型调用次数', 'inputTokens': '输入词元',
    'outputTokens': '输出词元', 'cost': '费用',
}


def number(value):
    if type(value) not in (int, float):
        return False
    try:
        return math.isfinite(value) and value >= 0
    except OverflowError:
        return False


def strings(value):
    return isinstance(value, list) and all(isinstance(item, str) and item.strip() for item in value)


def validate_suite(suite):
    if not isinstance(suite, dict) or not isinstance(suite.get('documents'), list) or not isinstance(suite.get('cases'), list):
        raise ValueError('评测集必须为包含资料和问题数组的对象')
    units = {}
    for document in suite['documents']:
        if not isinstance(document, dict) or not isinstance(document.get('units'), list):
            raise ValueError('评测资料必须为包含正文单元数组的对象')
        for unit in document['units']:
            if (not isinstance(unit, dict) or not isinstance(unit.get('id'), str) or not unit['id'].strip() or
                    not isinstance(unit.get('knowledgeBase'), str) or not unit['knowledgeBase'].strip()):
                raise ValueError('正文单元须记录非空标识和知识库')
            if unit['id'] in units:
                raise ValueError('评测正文单元标识重复')
            units[unit['id']] = unit
    cases = {}
    for case in suite['cases']:
        if (not isinstance(case, dict) or not isinstance(case.get('id'), str) or not case['id'].strip() or
                not isinstance(case.get('category'), str) or not case['category'].strip() or
                not strings(case.get('scope')) or not case['scope'] or
                not strings(case.get('relevantUnitIds')) or
                not set(case['relevantUnitIds']).issubset(units) or
                not isinstance(case.get('evidenceGroups'), list) or
                case.get('expectedOutcome') not in ('answered', 'no_answer', 'contradiction', 'partial', 'failed')):
            raise ValueError('评测问题须具有有效标识、分类、范围、相关正文、子问题分组及回答类型')
        if case['id'] in cases:
            raise ValueError('评测问题标识重复')
        for group in case['evidenceGroups']:
            if not strings(group) or not group or not set(group).issubset(case['relevantUnitIds']):
                raise ValueError('子问题证据分组必须引用本题相关正文')
        cases[case['id']] = case
    if not cases or not units:
        raise ValueError('评测集不得为空')
    return cases, units


def grade(review, key):
    entry = review.get(key)
    if entry is None:
        return None
    if not isinstance(entry, dict) or set(entry) != {'passed', 'total', 'evidence'}:
        raise ValueError('质量复核须给出通过数、总数及证据引用')
    passed, total = entry['passed'], entry['total']
    if (type(passed) is not int or type(total) is not int or
            total <= 0 or not 0 <= passed <= total or
            not isinstance(entry['evidence'], str) or not entry['evidence'].strip()):
        raise ValueError('质量复核计数无效，空分母不能计为满分')
    return passed / total


def evaluate(suite, run):
    cases, units = validate_suite(suite)
    if not isinstance(run, dict):
        raise ValueError('运行记录必须为对象')
    if run.get('mode') not in ('mock', 'real'):
        raise ValueError('必须明确模拟或真实模式')
    for field in ('runId', 'implementation', 'evidence'):
        if not isinstance(run.get(field), str) or not run[field].strip():
            raise ValueError('缺少运行标识、实现快照或原始证据引用')
    if run['mode'] == 'real' and (not isinstance(run.get('authorization'), str) or not run['authorization'].strip()):
        raise ValueError('真实结果须记录单独授权引用，工具本身不授权模型调用')
    results = run.get('results')
    if not isinstance(results, list):
        raise ValueError('运行结果必须为数组')
    observed = {}
    for result in results:
        if not isinstance(result, dict):
            raise ValueError('单题运行结果必须为对象')
        identifier = result.get('caseId')
        if not isinstance(identifier, str) or identifier not in cases or identifier in observed:
            raise ValueError('结果包含未知或重复问题标识')
        observed[identifier] = result
    rows = []
    for identifier, case in cases.items():
        result = observed.get(identifier)
        metrics = dict.fromkeys(LABELS)
        if result is not None:
            retrieved = result.get('retrievedUnitIds')
            if retrieved is not None:
                if (not isinstance(retrieved, list) or
                        any(not isinstance(item, str) for item in retrieved) or
                        len(set(retrieved)) != len(retrieved)):
                    raise ValueError('召回证据须按排名给出不重复标识')
                expected = set(case['relevantUnitIds'])
                hits = set(retrieved) & expected
                if expected:
                    metrics['recall'] = len(hits) / len(expected)
                    metrics['reciprocalRank'] = next(
                        (1 / rank for rank, item in enumerate(retrieved, 1) if item in expected), 0)
                groups = case['evidenceGroups']
                if groups:
                    metrics['coverage'] = sum(bool(set(group) & hits) for group in groups) / len(groups)
                metrics['scopeLeakCount'] = sum(
                    item not in units or units[item]['knowledgeBase'] not in case['scope']
                    for item in retrieved)
            review = result.get('review', {})
            if not isinstance(review, dict):
                raise ValueError('质量复核必须为对象')
            if review and (not isinstance(result.get('reviewer'), str) or not result['reviewer'].strip()):
                raise ValueError('质量复核须记录独立复核者')
            for key in ('accuracy', 'faithfulness', 'citationLocation', 'citationSupport'):
                metrics[key] = grade(review, key)
            if 'outcome' in result:
                if not isinstance(result['outcome'], str) or result['outcome'] not in ('answered', 'no_answer', 'contradiction', 'partial', 'failed'):
                    raise ValueError('未知回答类型')
                metrics['outcomeMatch'] = result['outcome'] == case['expectedOutcome']
            for key in ('latencyMs', 'modelCalls', 'inputTokens', 'outputTokens', 'cost'):
                value = result.get(key)
                if value is not None:
                    if not number(value) or (key in ('modelCalls', 'inputTokens', 'outputTokens') and type(value) is not int):
                        raise ValueError('用量、费用和延迟必须为非负有限数，次数及词元为整数')
                    if key == 'cost' and (not isinstance(result.get('currency'), str) or not result['currency'].strip()):
                        raise ValueError('已知费用必须同时记录币种')
                    metrics[key] = value
            if result.get('cost') is not None:
                metrics['currency'] = result['currency']
        rows.append({'id': identifier, 'category': case['category'],
                     'status': '已导入运行结果' if result else '未运行', 'metrics': metrics})
    return {'mode': run['mode'], 'runId': run['runId'], 'implementation': run['implementation'],
            'evidence': run['evidence'], 'authorization': run.get('authorization'),
            'conclusion': '仅为固定模拟流程证据，真实质量未验证' if run['mode'] == 'mock'
            else '真实结果已导入，阈值与最终验收须另行复核', 'cases': rows}


def main():
    parser = argparse.ArgumentParser(description='离线汇总固定中文评测结果，不执行模型调用')
    parser.add_argument('--suite', type=Path, default=ROOT / 'suite.json')
    parser.add_argument('--input', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    source = args.input.read_bytes()
    suite = args.suite.read_bytes()
    report = evaluate(json.loads(suite), json.loads(source))
    report['inputSha256'] = hashlib.sha256(source).hexdigest()
    report['suiteSha256'] = hashlib.sha256(suite).hexdigest()
    args.output.mkdir(parents=True, exist_ok=True)
    for name in ('report.json', 'report.md'):
        if (args.output / name).exists():
            raise ValueError('报告目录已有结果，请使用新目录保留历史证据')
    (args.output / 'report.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    lines = ['# 中文固定评测报告', '', report['conclusion'], '',
             f"运行：`{report['runId']}`；实现：`{report['implementation']}`。", '',
             '空白指标表示未验证；不按空集计满分，不汇总不同币种费用。', '']
    for row in report['cases']:
        lines += [f"## {row['id']}：{row['category']}", '', row['status'], '', '| 指标 | 结果 |', '| --- | --- |']
        for key, label in LABELS.items():
            value = row['metrics'][key]
            display = '未验证' if value is None else ('是' if value else '否') if type(value) is bool else value
            lines.append(f"| {label} | {display} |")
        if row['metrics'].get('currency'):
            lines.append(f"| 币种 | {row['metrics']['currency']} |")
        lines.append('')
    (args.output / 'report.md').write_text('\n'.join(lines), encoding='utf-8')


if __name__ == '__main__':
    main()
