# 中文检索问答固定评测

第23票使用 [固定资料与问题](../scripts/evaluation/suite.json) 及 [离线汇总器](../scripts/evaluation/evaluate.py)。工具仅使用 Python 标准库，不联网、不执行模型调用，不新增业务接口。当前已编写，尚未运行；工程参数初值不是实测推荐值。

## 资料与运行

固定资料均为虚构内容。问题覆盖精确术语、同义表达、跨页、表格、多资料、无答案、矛盾资料和错误知识库范围。`documents` 中的 `units` 定义正文与人工评测单元；它们不是后端切片标识，不可直接填入真实请求冒充已检索证据。

统一验证时，先用资料准备脚本将每份正文保存为指定格式，再导入真实后端，并记录资料、内容版本、知识库及切片与固定单元的映射。`maintenance` 直接复用现有两页中文 PDF，不生成另一份近似文件；`manual` 保留 Markdown 表格；其他资料保存为纯文本。跨页检查须使用真实 PDF 解析和预览定位，不能以这份页码说明代替。正文单元允许被切成多片或同片覆盖多单元，映射须经原文核对，不能靠候选排名或参考答案猜映射。准备脚本要求新目录，输出的 `manifest.json` 仅用于导入分组，不含真实服务端标识。

按每个问题的 `scope` 设置知识库范围，通过现有真实检索、问答接口执行；保存请求、响应、引用定位观察及模型替身调用记录。导出的运行结果只列实际检索成功映射的单元，按首次出现的排名去重。其他候选用独有的未知标识保留排名，不能丢掉不相关候选而抬高排序分数。未完成问题保持缺失，失败不能删出分母。原始运行证据须另行保全并可由 `evidence` 定位；评估器只记录该引用，不替代真实性审阅。

参考答案只用于独立人工复核，不作为运行结果，也不从参考答案生成评分。准确、忠实、引用可定位和引用支持分别复核；每项填写实际检查的断言或引用总数、通过数、复核者及证据位置。复核者字符串及计数只是导入记录，评估器无法证明其独立性，不能据此宣称完成领域独立验收。无答案回答仍核查是否编造；没有引用时引用指标保持未知，不能写空分母满分。

## 输入格式

输入 JSON 使用英文标识符，示例如下。示例只说明格式，不是可交付的评测证据。

```json
{
  "mode": "mock",
  "runId": "固定模拟运行标识",
  "implementation": "实际提交或实现快照摘要",
  "evidence": "实际原始运行证据位置及摘要",
  "results": [
    {
      "caseId": "term",
      "retrievedUnitIds": ["term-rule"],
      "outcome": "answered",
      "reviewer": "实际独立复核者",
      "review": {
        "accuracy": {"passed": 1, "total": 1, "evidence": "实际断言复核位置"},
        "faithfulness": {"passed": 1, "total": 1, "evidence": "实际原文核对位置"},
        "citationLocation": {"passed": 1, "total": 1, "evidence": "实际版本与页行定位观察"},
        "citationSupport": {"passed": 1, "total": 1, "evidence": "实际引用对断言支持复核"}
      },
      "latencyMs": 120,
      "modelCalls": 3,
      "inputTokens": null,
      "outputTokens": null,
      "cost": null
    }
  ]
}
```

`mode` 只能是 `mock` 或 `real`。真实模式另须非空 `authorization`，指向用户单独授权记录；该字段只记录授权，不产生授权，也不代表工具核验了外部调用权限。不得为了获得真实模式报告自行调用收费模型。模拟结果只说明固定替身下的流程、范围与错误处理，不能用于宣布真实中文效果或费用结论。

`outcome` 为 `answered`、`no_answer`、`contradiction`、`partial` 或 `failed`。这是评测分类，不替换接口原始状态；从真实响应映射时保留原始证据。非空数组 `retrievedUnitIds` 的顺序来自真实排序；空数组表示已执行但零候选，缺字段表示未观察。召回和覆盖在存在相关单元时按实际命中计算，零相关样例保持未知并单独检查回答类型及范围污染。

所有指标可缺失或为 `null`，表示未验证。次数与词元必须为非负整数；耗时和费用必须为非负有限数。已知费用同时填写 `currency`，例如 `CNY`，按供应商实际计费或明确价格版本计算；模拟费用未知时保持 `null`，不能伪填零。工具逐问题保留币种，不合计不同币种。

## 复跑和结果

全部票据实现后再执行，当前不运行以下命令：

```powershell
python -m unittest discover -s scripts/evaluation -p test_evaluate.py
python scripts/evaluation/materialize.py --output .runtime/evaluation/materials-01
python scripts/evaluation/evaluate.py --input .runtime/evaluation/run.json --output .runtime/evaluation/report-01
```

输出目录必须没有同名报告，避免覆盖已有证据。`report.json` 记录输入及固定评测集的 SHA-256、运行和实现引用、逐问题指标；`report.md` 输出中文报告。报告不复制问题、正文、完整回答或凭据，输入和原始证据也不得包含真实密钥或个人资料。

指标分别保留证据召回率、首个相关证据倒数排名、子问题证据覆盖率、事实准确率、原文忠实率、引用可定位率、引用支持率、范围外证据数、回答类型匹配、延迟、调用次数、输入输出词元与费用。遗漏问题仍列为未运行。没有测量基线及批准阈值时不自动输出质量验收通过，不引入混合检索或重排；最终仍须真实后端流程、不可变原始证据及独立验收。
