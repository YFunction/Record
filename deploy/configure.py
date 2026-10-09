"""Complete the existing systemd installation with private Serve and a phone import file."""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import time
import urllib.error
import urllib.request


def run(*args):
    return subprocess.check_output(args, text=True, timeout=20)


def settings():
    path = Path('/etc/recorder.env')
    values = dict(line.split('=', 1) for line in path.read_text().splitlines() if '=' in line) if path.exists() else {}
    if values.get('RECORDER_BIND', '127.0.0.1') != '127.0.0.1':
        raise ValueError('RECORDER_BIND 必须为 127.0.0.1，以保持私有访问。')
    port = int(values.get('RECORDER_PORT', '8080'))
    if not 1 <= port <= 65535:
        raise ValueError('存储端口无效。')
    return values, port


def check_serve(config, host, port, target):
    key = f'{host}:{port}'
    if config.get('AllowFunnel', {}).get(key):
        raise ValueError('此端口已启用公网 Funnel；请选择另一个 --https-port。')
    web = config.get('Web', {}).get(key, {}).get('Handlers', {})
    existing = web.get('/')
    if existing is not None and existing.get('Proxy') != target:
        raise ValueError('此 HTTPS 根路径已有其他服务；请使用 --https-port 8443 等空闲端口。')
    if str(port) in config.get('TCP', {}) and existing is None:
        raise ValueError('此端口已有其他 TCP 或 HTTPS 服务；请选择空闲端口。')


def api_check(url, token):
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    req = urllib.request.Request(url + '/v1/chunks', headers={'Authorization': 'Bearer ' + token})
    with opener.open(req, timeout=10) as response:
        if response.status != 200 or not isinstance(json.load(response).get('chunks'), list):
            raise ValueError('存储 API 健康检查失败。')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('step', choices=['preflight', 'finish'])
    parser.add_argument('--https-port', type=int, default=443)
    parser.add_argument('--no-serve', action='store_true')
    args = parser.parse_args()
    if not 1 <= args.https_port <= 65535:
        raise ValueError('HTTPS 端口无效。')
    values, backend_port = settings()
    target = f'http://127.0.0.1:{backend_port}'
    host = None
    if not args.no_serve:
        status = json.loads(run('tailscale', 'status', '--json'))
        if status.get('BackendState') != 'Running':
            raise ValueError('请先安装 Tailscale 并运行 sudo tailscale up 登录。')
        host = status['Self']['DNSName'].rstrip('.')
        if not host.endswith('.ts.net'):
            raise ValueError('缺少 Tailscale HTTPS 域名，请检查 MagicDNS。')
        if not status.get('CertDomains'):
            raise ValueError('请先启用 HTTPS 证书（不勾选 Funnel）：https://login.tailscale.com/f/serve?node=' + status['Self']['ID'])
        config = json.loads(run('tailscale', 'serve', 'status', '--json') or '{}')
        check_serve(config, host, args.https_port, target)
    if args.step == 'preflight':
        # Refuse to overwrite a listener belonging to another service.
        try:
            opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
            with opener.open(target + '/v1/chunks', timeout=2) as response:
                header = response.headers.get('Server', '')
        except urllib.error.HTTPError as error:
            header = error.headers.get('Server', '')
        except urllib.error.URLError:
            header = None
        if header is not None and not header.startswith('CipherStore/'):
            raise ValueError('本机存储端口已被其他服务占用。')
        print('部署前检查通过；已有令牌、录音数据和其他 Serve 服务将保留。')
        return
    token = values.get('RECORDER_TOKEN', '')
    if not re.fullmatch(r'[A-Za-z0-9_-]{32,256}', token):
        raise ValueError('服务器令牌无效，请检查 /etc/recorder.env。')
    for attempt in range(10):
        try:
            api_check(target, token)
            break
        except OSError:
            if attempt == 9: raise
            time.sleep(0.2)
    if args.no_serve:
        print('存储服务已启动；已跳过 Serve 配置。')
        return
    subprocess.run(['tailscale', 'serve', '--bg', f'--https={args.https_port}', target], check=True, timeout=30)
    endpoint = f'https://{host}' + (f':{args.https_port}' if args.https_port != 443 else '')
    deadline = time.monotonic() + 60
    while True:
        try:
            api_check(endpoint, token)
            break
        except (OSError, ValueError):
            if time.monotonic() >= deadline:
                raise ValueError('Serve 已配置但 HTTPS 健康检查未通过；请检查 tailscaled 日志后重新运行安装。')
            time.sleep(2)
    uid = int(os.environ.get('SUDO_UID', '0'))
    import pwd
    account = pwd.getpwuid(uid)
    parent = Path(account.pw_dir).resolve()
    destination = parent / 'record-server-config.json'
    fd, temp = tempfile.mkstemp(prefix='.record-config-', dir=parent)
    try:
        os.fchmod(fd, 0o600); os.fchown(fd, uid, account.pw_gid)
        with os.fdopen(fd, 'w') as out:
            json.dump({'server': endpoint, 'token': token}, out, indent=2); out.write('\n'); out.flush(); os.fsync(out.fileno())
        os.replace(temp, destination)
    finally:
        if os.path.exists(temp): os.unlink(temp)
    print('部署完成，HTTPS 鉴权检查通过。')
    print('手机连接地址：' + endpoint)
    print('手机导入文件：' + str(destination) + '（权限 600，含访问令牌，请私下保管）')
    print('云端密文：/var/lib/recorder/chunks/；索引：/var/lib/recorder/index.sqlite3')


if __name__ == '__main__':
    try:
        main()
    except (ValueError, OSError, subprocess.SubprocessError) as error:
        raise SystemExit(str(error))
