"""以独立测试目录启动已打包应用，验证真实同源界面并关闭自建进程。"""

import os
import shutil
import socket
import subprocess
import sys
import time
import uuid
from pathlib import Path
from urllib.error import URLError
from urllib.request import urlopen


ROOT = Path(__file__).resolve().parents[2]


def main():
    jars = list((ROOT / 'server/target').glob('server-*.jar'))
    if len(jars) != 1:
        raise FileNotFoundError('须先完成后端打包，且只有一个本次应用发行包。')
    suffix = '.exe' if os.name == 'nt' else ''
    java = Path(os.environ.get('JAVA_HOME', '')) / 'bin' / ('java' + suffix)
    java_command = str(java) if java.is_file() else shutil.which('java')
    node = ROOT / 'front/target/toolchain/node' / ('node' + suffix)
    node_command = str(node) if node.is_file() else shutil.which('node')
    if not java_command or not node_command:
        raise FileNotFoundError('真实联调需要 Java 21 与已固定的 Node.js 工具链。')
    with socket.socket() as listener:
        listener.bind(('127.0.0.1', 0))
        port = listener.getsockname()[1]
    directory = ROOT / '.runtime/live-checks' / uuid.uuid4().hex
    directory.mkdir(parents=True)
    base_url = f'http://127.0.0.1:{port}'
    command = [java_command, '-jar', str(jars[0]), f'--server.port={port}',
               '--server.address=127.0.0.1', f'--know-pool.data-directory={directory / "data"}',
               f'--logging.file.name={directory / "application.log"}']
    with (directory / 'startup.log').open('wb') as log:
        process = subprocess.Popen(command, cwd=ROOT, stdout=log, stderr=subprocess.STDOUT)
        try:
            deadline = time.monotonic() + 60
            while time.monotonic() < deadline:
                if process.poll() is not None:
                    raise RuntimeError('测试应用启动失败，详见 ' + str(directory.relative_to(ROOT)))
                try:
                    with urlopen(base_url, timeout=1) as response:
                        if response.status == 200:
                            break
                except (URLError, TimeoutError):
                    pass
                time.sleep(0.2)
            else:
                raise TimeoutError('测试应用启动超时，详见 ' + str(directory.relative_to(ROOT)))
            result = subprocess.run([node_command, 'scripts/check-live.mjs', base_url],
                                    cwd=ROOT / 'front', timeout=120, check=False)
            return result.returncode
        finally:
            if process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=10)


if __name__ == '__main__':
    try:
        sys.exit(main())
    except (OSError, RuntimeError, subprocess.SubprocessError) as error:
        print('真实联调未通过：' + str(error))
        sys.exit(1)
