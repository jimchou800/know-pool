"""将固定正文保存为可导入资料，跨页PDF复用已有测试文件。"""

import argparse
import json
import shutil
from pathlib import Path


ROOT = Path(__file__).resolve().parent
REPOSITORY = ROOT.parents[1]


def materialize(suite, output):
    output.mkdir(parents=True, exist_ok=False)
    manifest = []
    for document in suite['documents']:
        extension = {'markdown': '.md', 'text': '.txt', 'text_pdf': '.pdf'}[document['format']]
        path = output / (document['id'] + extension)
        if document['format'] == 'text_pdf':
            shutil.copyfile(REPOSITORY / document['source'], path)
        else:
            path.write_text('\n\n'.join(unit['text'] for unit in document['units']) + '\n', encoding='utf-8')
        manifest.append({'documentId': document['id'], 'path': path.name,
                         'knowledgeBase': document['units'][0]['knowledgeBase'],
                         'unitIds': [unit['id'] for unit in document['units']]})
    (output / 'manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description='准备固定中文导入资料，不连接应用或模型')
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    materialize(json.loads((ROOT / 'suite.json').read_text(encoding='utf-8')), args.output)
