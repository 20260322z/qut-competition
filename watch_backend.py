"""在本机运行，实时查看服务器后端日志。

    python watch_backend.py
    python watch_backend.py --napcat
    python watch_backend.py --both
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import threading
from pathlib import Path

try:
    import paramiko
except ImportError:
    sys.exit('缺少 paramiko，请先执行: python -m pip install paramiko')

ROOT = Path(__file__).resolve().parent
DEFAULTS = {
    'DEPLOY_HOST': '38.207.179.218',
    'DEPLOY_PORT': '57777',
    'DEPLOY_USER': 'root',
    'DEPLOY_PASSWORD': '',
}


def load_env():
    values = dict(DEFAULTS)
    env_path = ROOT / '.env'
    if env_path.exists():
        for raw in env_path.read_text(encoding='utf-8').splitlines():
            line = raw.strip()
            if not line or line.startswith('#') or '=' not in line:
                continue
            key, value = line.split('=', 1)
            values[key.strip()] = value.strip().strip("'").strip('"')
    for key in DEFAULTS:
        if os.getenv(key):
            values[key] = os.environ[key]
    return values


def connect(cfg):
    password = cfg.get('DEPLOY_PASSWORD') or ''
    if not password:
        sys.exit('未找到 DEPLOY_PASSWORD。请在项目目录的 .env 里填写，或设置环境变量。')
    client = paramiko.SSHClient()
    client.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    client.connect(
        cfg['DEPLOY_HOST'],
        port=int(cfg['DEPLOY_PORT']),
        username=cfg['DEPLOY_USER'],
        password=password,
        timeout=20,
        allow_agent=False,
        look_for_keys=False,
    )
    return client


def run(client, command, timeout=30):
    _, stdout, stderr = client.exec_command(command, timeout=timeout)
    return (stdout.read() + stderr.read()).decode('utf-8', 'replace')


def print_status(client):
    health = run(client, 'curl -sS --max-time 8 http://127.0.0.1:18086/health')
    sources = run(client, 'curl -sS --max-time 8 http://127.0.0.1:18086/api/v1/sources')
    print('======== 服务状态 ========')
    try:
        data = json.loads(health)
        print(f"API {data.get('status')}  version={data.get('version')}  "
              f"通知={data.get('notice_count')}  QQ通知={data.get('qq_notice_count')}")
    except json.JSONDecodeError:
        print(health.strip() or '健康检查失败')
    try:
        items = json.loads(sources).get('items', [])
        for item in items:
            name = item.get('name') or item.get('id')
            error = item.get('last_error') or '正常'
            extra = ''
            if item.get('id') == 'qq':
                extra = f"  待审核={item.get('pending_review')}"
            print(f"{name}: {error}{extra}")
    except json.JSONDecodeError:
        print(sources.strip())
    print('Ctrl+C 停止跟随日志\n')


def follow(client, command, prefix=''):
    _, stdout, stderr = client.exec_command(command, get_pty=True, timeout=None)
    stream = stdout
    try:
        while True:
            line = stream.readline()
            if not line:
                break
            text = line if isinstance(line, str) else line.decode('utf-8', 'replace')
            print(f'{prefix}{text}', end='', flush=True)
    except KeyboardInterrupt:
        return


def main():
    parser = argparse.ArgumentParser(description='实时查看青理竞赛通后端日志')
    parser.add_argument('--napcat', action='store_true', help='只看 NapCat 机器人日志')
    parser.add_argument('--both', action='store_true', help='同时看后端和 NapCat')
    args = parser.parse_args()
    if hasattr(sys.stdout, 'reconfigure'):
        sys.stdout.reconfigure(encoding='utf-8', errors='replace')

    cfg = load_env()
    client = connect(cfg)
    print(f'已连接 {cfg["DEPLOY_USER"]}@{cfg["DEPLOY_HOST"]}:{cfg["DEPLOY_PORT"]}')
    print_status(client)

    api_cmd = 'docker logs -f --tail 80 qut-competition-api'
    napcat_cmd = 'docker logs -f --tail 40 napcat'
    try:
        if args.both:
            extra = connect(cfg)
            threading.Thread(target=follow, args=(extra, napcat_cmd, '[napcat] '), daemon=True).start()
            follow(client, api_cmd, '[api] ')
        elif args.napcat:
            follow(client, napcat_cmd)
        else:
            follow(client, api_cmd)
    finally:
        client.close()


if __name__ == '__main__':
    main()
