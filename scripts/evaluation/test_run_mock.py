"""真实评测导出必须保留未知排名，不接受错误文件或旧版本冒充命中。"""

import json
import subprocess
import tempfile
import unittest
from pathlib import Path


class MockExportTests(unittest.TestCase):
    def test_mapping_preserves_rank_and_checks_document_version(self):
        root = Path(__file__).resolve().parents[2]
        node = root / 'front/target/toolchain/node/node.exe'
        subprocess.run([str(node), 'scripts/evaluation/run_mock.mjs', '--self-check'],
                       cwd=root, check=True)

    def test_missing_java_preserves_failure_evidence_and_exits(self):
        root = Path(__file__).resolve().parents[2]
        node = root / 'front/target/toolchain/node/node.exe'
        with tempfile.TemporaryDirectory(dir=root / '.runtime') as directory:
            output = Path(directory) / 'run'
            result = subprocess.run([str(node), 'scripts/evaluation/run_mock.mjs',
                                     '--resources',
                                     '--java', str(Path(directory) / 'missing.exe'),
                                     '--output', str(output)], cwd=root,
                                    capture_output=True, timeout=30)
            self.assertNotEqual(result.returncode, 0)
            self.assertTrue((output / 'raw-http.json').exists())
            self.assertTrue((output / 'run.json').exists())
            resources = json.loads((output / 'resource-stages.json').read_text(encoding='utf-8'))
            self.assertEqual(resources['stages'], [])
            self.assertEqual(resources['mode'], 'mock')
            result = subprocess.run([str(node), 'scripts/evaluation/run_mock.mjs',
                                     '--output', str(output)], cwd=root,
                                    capture_output=True, timeout=30)
            self.assertNotEqual(result.returncode, 0)
