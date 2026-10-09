#!/usr/bin/env bash
set -euo pipefail
# Run from the repository root with sudo. Existing credentials and data are kept.
test -f server/server.py
test -f deploy/recorder.service
if ! id recorder >/dev/null 2>&1; then
  useradd --system --home-dir /var/lib/recorder --shell /usr/sbin/nologin recorder
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
systemctl enable --now recorder
systemctl restart recorder
systemctl is-active recorder
