#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ $EUID -ne 0 ]]; then
  echo '请使用 sudo bash deploy/install.sh' >&2
  exit 1
fi
https_port=443
no_serve=()
while [[ $# -gt 0 ]]; do
  case "$1" in
    --https-port) https_port="${2:?缺少 HTTPS 端口}"; shift 2 ;;
    --no-serve) no_serve=(--no-serve); shift ;;
    *) echo "未知参数：$1" >&2; exit 1 ;;
  esac
done
python3 deploy/configure.py preflight --https-port "$https_port" "${no_serve[@]}"
if ! id recorder >/dev/null 2>&1; then
  useradd --system --user-group --home-dir /var/lib/recorder --shell /usr/sbin/nologin recorder
fi
install -d -m 0755 /opt/recorder/server
install -d -o recorder -g recorder -m 0700 /var/lib/recorder
install -m 0644 server/server.py /opt/recorder/server/server.py
if ! test -f /etc/recorder.env; then
  python3 - <<'PY'
import os, secrets
fd = os.open('/etc/recorder.env', os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
with os.fdopen(fd, 'w') as f:
    f.write('RECORDER_TOKEN=' + secrets.token_urlsafe(48) + '\n')
    f.write('RECORDER_DATA=/var/lib/recorder\nRECORDER_BIND=127.0.0.1\n')
    f.write('RECORDER_PORT=8080\nRECORDER_QUOTA_BYTES=10737418240\n')
PY
fi
install -m 0644 deploy/recorder.service /etc/systemd/system/recorder.service
systemctl daemon-reload
systemctl enable recorder
systemctl restart recorder
python3 deploy/configure.py finish --https-port "$https_port" "${no_serve[@]}"
