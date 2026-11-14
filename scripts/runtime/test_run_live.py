"""验证联调前置缺失时不会启动错误发行包。"""

import tempfile
import hashlib
import json
import unittest
from pathlib import Path
from unittest.mock import patch

import run_live


class LivePreflightTests(unittest.TestCase):
    def test_release_integrity_rejects_changes_extras_and_traversal(self):
        """发行启动先验证清单，不接受篡改、额外文件或越界条目。"""
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            files = ('application.jar', 'runtime/bin/java.exe', 'start.ps1', 'start.cmd')
            manifest = []
            for name in files:
                path = root / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(b'fixed')
                manifest.append({'path': name, 'sha256': hashlib.sha256(b'fixed').hexdigest()})
            index = root / 'manifest.json'
            index.write_text(json.dumps(manifest), encoding='utf-8-sig')
            self.assertEqual(run_live.release_inputs(root),
                             (root / 'application.jar', root / 'runtime/bin/java.exe'))
            (root / 'application.jar').write_bytes(b'changed')
            with self.assertRaisesRegex(ValueError, '摘要'):
                run_live.release_inputs(root)
            (root / 'application.jar').write_bytes(b'fixed')
            extra = root / 'personal.sqlite'
            extra.touch()
            with self.assertRaisesRegex(ValueError, '清单外'):
                run_live.release_inputs(root)
            extra.unlink()
            manifest[0]['path'] = '../application.jar'
            index.write_text(json.dumps(manifest), encoding='utf-8')
            with self.assertRaisesRegex(ValueError, '路径'):
                run_live.release_inputs(root)

    def test_missing_or_multiple_packages_prevent_launch(self):
        """缺少发行包或存在多个包时拒绝启动。"""
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            target = root / 'server/target'
            target.mkdir(parents=True)
            with patch.object(run_live, 'ROOT', root), patch.object(run_live.subprocess, 'Popen') as launch:
                with self.assertRaises(FileNotFoundError):
                    run_live.main()
                (target / 'server-旧.jar').touch()
                (target / 'server-新.jar').touch()
                with self.assertRaises(FileNotFoundError):
                    run_live.main()
                launch.assert_not_called()


if __name__ == '__main__':
    unittest.main()
