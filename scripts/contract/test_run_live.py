"""验证联调前置缺失时不会启动错误发行包。"""

import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import run_live


class LivePreflightTests(unittest.TestCase):
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
