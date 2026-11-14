"""以独立测试目录启动已打包应用，验证真实同源界面并关闭自建进程。"""

import argparse
import hashlib
import json
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

import provider_security


ROOT = Path(__file__).resolve().parents[2]


def release_inputs(directory):
    """启动前核对已解压发行包清单，拒绝缺件、篡改、链接与越界路径。"""
    directory = Path(directory)
    resolved_directory = directory.resolve(strict=True)
    manifest = json.loads((directory / 'manifest.json').read_text(encoding='utf-8-sig'))
    if not isinstance(manifest, list) or not manifest:
        raise ValueError('发行文件清单必须为非空数组。')
    declared = set()
    for item in manifest:
        if not isinstance(item, dict) or set(item) != {'path', 'sha256'}:
            raise ValueError('发行文件清单字段不完整。')
        name, expected = item['path'], item['sha256']
        if (not isinstance(name, str) or not name or '\\' in name or ':' in name
                or name.startswith('/') or any(part in ('', '.', '..') for part in name.split('/'))
                or name in declared or not isinstance(expected, str) or len(expected) != 64):
            raise ValueError('发行文件清单路径或摘要非法。')
        path = directory / name
        if (not path.is_file() or path.resolve().is_relative_to(resolved_directory) is False
                or any(parent.is_symlink() for parent in (path, *path.parents) if parent != directory)):
            raise ValueError('发行文件缺失或含链接。')
        if hashlib.sha256(path.read_bytes()).hexdigest() != expected:
            raise ValueError('发行文件摘要不匹配：' + name)
        declared.add(name)
    actual = {path.relative_to(directory).as_posix() for path in directory.rglob('*') if path.is_file()}
    if actual != declared | {'manifest.json'}:
        raise ValueError('发行目录包含清单外文件。')
    if not {'application.jar', 'runtime/bin/java.exe', 'start.ps1', 'start.cmd'}.issubset(declared):
        raise ValueError('发行包缺少应用、运行时或启动入口。')
    return directory / 'application.jar', directory / 'runtime/bin/java.exe'


def main(release_directory=None, browser='msedge'):
    if browser not in ('msedge', 'chrome'):
        raise ValueError('浏览器必须为 Chrome 或 Edge。')
    if release_directory:
        jar, packaged_java = release_inputs(release_directory)
    else:
        jars = list((ROOT / 'server/target').glob('server-*.jar'))
        if len(jars) != 1:
            raise FileNotFoundError('须先完成后端打包，且只有一个本次应用发行包。')
        jar, packaged_java = jars[0], None
    suffix = '.exe' if os.name == 'nt' else ''
    java = Path(os.environ.get('JAVA_HOME', '')) / 'bin' / ('java' + suffix)
    java_command = str(packaged_java) if packaged_java else str(java) if java.is_file() else shutil.which('java')
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
    # 固定测试账号，避免个人运行账号进入界面截图。
    command = [java_command, '-Duser.name=验收样例账号', '-jar', str(jar), f'--server.port={port}',
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
            result = subprocess.run([node_command, 'scripts/check-live.mjs', base_url, browser],
                                    cwd=ROOT / 'front', timeout=600, check=False)
            if result.returncode == 0:
                provider_security.generate(base_url)
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
        parser = argparse.ArgumentParser(description='使用独立临时资料验证源码构建或正式发行包')
        parser.add_argument('--release-directory', type=Path)
        parser.add_argument('--browser', choices=('msedge', 'chrome'), default='msedge')
        args = parser.parse_args()
        sys.exit(main(args.release_directory, args.browser))
    except (OSError, RuntimeError, ValueError, subprocess.SubprocessError) as error:
        print('真实联调未通过：' + str(error))
        sys.exit(1)
