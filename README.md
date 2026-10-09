# 加密录音：荣耀 400 Pro 验证版 v0.2.0

目标设备：荣耀 400 Pro / MagicOS 10.0 / Android 16。将系统的电源键双按快捷启动设置为“加密录音”。应用完成首次配置及授权后，每次进入自动开始录音；重复进入不会创建第二份录音。

此项目包含 Android 源码、单用户 Linux 密文存储服务和电脑离线恢复工具。默认部署方式为 Tailscale Serve 的私有 HTTPS 入口，手机和 Linux 服务器加入同一 tailnet。云端不接收录音密钥、不解密、不转写、不播放音频。服务器仍能看到文件大小、上传时间、会话标识以及代理可见的网络信息。

## 工作方式

- `AudioRecord` 连续采集 → `MediaCodec` 编码 AAC-LC / 16 kHz / 单声道 / 64 kbps。
- 约每 30 秒对完整 AAC 帧分段，以 AES-256-GCM 加密后原子写入应用私有目录；不生成明文录音临时文件。AAC 帧边界会产生很小的时长误差，当前参数下一片约为 30.016 秒；正常停止时立即保存不足 30 秒的尾段。
- 连续录音时由独立上传线程发送已经完成的密文片段，上传检查约每 3 秒运行一次。第一片在录音约 30 秒后才会保存和上传；上传延迟还取决于网络、积压和服务器响应。
- 服务器校验 SHA-256、落盘并提交索引后返回确认；同名同内容可重试，同名不同内容返回冲突。
- 断网时继续本地录音。停止后由 WorkManager 安排补传，并每 15 分钟安排恢复检查。这是调度间隔，Android 电池管理可能延后执行。
- 本地保留已上传副本，用户可手动清理已确认上传的文件；总容量默认 256 MiB，空间不足会停止录音并显示原因。未上传密文不会自动删除。
- 正常停止时追加加密的结束标记；异常结束的会话可通过恢复工具显式选择恢复部分录音。
- 64 kbps 音频约 28.8 MB/小时，不含 AAC 帧头、加密及 HTTP 开销。

## 必须理解的行为

1. 首次安装无法跳过系统麦克风授权。首次配置需要勾选“启用打开应用自动录音”，保存服务器设置并成功导出恢复密钥。
2. 应用在可见界面中启动 `microphone` 前台服务，随后支持后台/熄屏继续录音。录音时保留系统麦克风使用提示和前台服务通知；可在通知栏停止。
3. 应用请求在锁屏上显示启动界面，并在界面恢复可见后启动录音。电源键双按的映射由 MagicOS 设置完成，应用不拦截电源键。是否能在你的锁屏设置下直接开始，必须真机验证。
4. 不承诺绕过 MagicOS 的进程回收。强行停止应用、重启手机、撤销权限、关闭麦克风访问或其他应用抢占麦克风，可能停止录音。重启后不会自动开始新录音，须再次双按/打开。
5. 普通麦克风录音不等于通话录音。来电/接听、蓝牙和其他音频应用的影响需要真机测试。
6. 若进程意外终止，已完成并落盘的片段保留；当前尚未完成的片段和编码器缓存可能丢失，通常为最近不足约 30 秒的音频。正常停止会保存尾段。
7. 当前为个人单设备验证版本，不包含多用户管理、手机端回放、自动密钥轮换、云端删除接口或自动清理云端数据。

## 密钥与恢复

手机生成随机 32 字节录音密钥，并用 Android Keystore 内的不可导出密钥包装后保存。上传令牌同样包装保存。系统备份关闭，密文文件放在 `noBackupFilesDir`。

**恢复密钥导出文件是明文 Base64 密钥。持有它的人可以解密全部录音，请离线保管，不要放到录音云服务器。** 备份文件和云端密文分开保管。首次初始化也可输入已有恢复密钥；完成初始化后不支持直接替换录音密钥。

手机丢失、卸载应用或清除应用数据后，恢复密钥用于在电脑解密云端文件。没有备份密钥，就不能恢复录音。导出成功仅代表文件写入成功，请自行确认文件能读取且有额外备份。

## Android 构建

已构建的测试包位于 `app/build/outputs/apk/debug/app-debug.apk`，可直接安装到手机进行联调。只有修改源码及重新构建时，才需要下面的开发环境。应用最低支持 Android 12。

安装 Android Studio，通过 SDK Manager 安装 Android SDK Platform 36、Build Tools 35.0.0，并使用 JDK 17 或 21。项目使用 AGP 8.13.2、Gradle 8.13、WorkManager 2.11.2。

在 Android Studio 打开此目录，等待同步完成，构建并运行 `app`。如使用命令行：

```powershell
.\gradlew.bat assembleDebug lintDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

调试 APK 只在设置校验层允许 `http://127.0.0.1:端口` 本地测试地址。发布版本只接受 HTTPS；部署到云服务器时使用 HTTPS 地址。发布 APK 请使用自己长期保管的签名密钥，以便后续更新不丢失应用数据。

## WSL 本地测试

服务端只需要 Python 3.10+，无第三方依赖。在 WSL：

```bash
cd /mnt/d/Record/server
umask 077
export RECORDER_TOKEN="$(python3 -c 'import secrets; print(secrets.token_urlsafe(32))')"
export RECORDER_DATA="$PWD/data"
python3 server.py
```

请通过你自己的终端安全查看 `RECORDER_TOKEN`，填入应用“上传令牌”。不要把令牌或恢复密钥贴到公共日志/聊天里。令牌只用于访问存储 API，不是录音解密密钥。

在 Windows PowerShell，手机 USB 调试授权后运行：

```powershell
adb devices
adb reverse tcp:8080 tcp:8080
```

Windows 通常能通过 `localhost:8080` 访问 WSL 服务；若本机网络配置关闭了 WSL localhost 转发，需要先恢复该转发或将测试服务运行在 Windows。`adb reverse` 需要 Windows 的 8080 端口可达；重新连接 USB 后可能需要再次执行。

安装调试 APK，在应用填 `http://127.0.0.1:8080` 和测试令牌，勾选自动录音，保存并导出恢复密钥，然后授予麦克风/通知权限并开始测试。

## Linux 云端部署：Tailscale Serve HTTPS

`deploy/recorder.service` 提供 systemd 示例。服务器需要 Python 3.10+，以及已登录同一 tailnet 的 Tailscale。通过 Tailscale Serve 将本机 Python 服务发布到 tailnet 内，手机使用稳定的完整 `*.ts.net` HTTPS 地址访问。应用配置不需要使用服务器的公网 IP。

### 安装存储服务

将 `server/` 放到 `/opt/recorder/server/`：

1. 创建无交互登录的 `recorder` 服务用户，将 `/var/lib/recorder` 的所有者设为该用户，权限设为 700。
2. 复制 `deploy/recorder.env.example` 到 `/etc/recorder.env`，替换随机令牌，权限设为 600；保持绑定 `127.0.0.1`，默认云端额度 10 GiB。
3. 将 `deploy/recorder.service` 安装到 `/etc/systemd/system/`，执行 `systemctl daemon-reload` 和 `systemctl enable --now recorder`。
4. 在 Tailscale 管理控制台的 DNS 页面启用 MagicDNS 和 HTTPS Certificates。首次启用证书时会提示节点完整域名将出现在证书透明度日志中；节点名应使用普通名称，例如 `recorder`。
5. 检查该节点现有 Serve 配置后，启用代理并获取实际 HTTPS 地址：

```bash
sudo tailscale serve status
sudo tailscale serve --bg --https=443 http://127.0.0.1:8080
sudo tailscale serve status
```

如果现有 Serve 已使用该端口/根路径承载其他服务，请选择空闲端口，例如 `--https=8443`，应用地址相应包含 `:8443`。使用命令实际显示的地址，例如 `https://recorder.your-tailnet.ts.net`；这个示例不是你的真实地址。

6. 将手机的 Tailscale 连接到同一 tailnet，确保访问规则允许手机访问服务器 HTTPS 端口。先用手机浏览器打开该地址的 `/v1/chunks`；未附上传令牌时收到 `{"error":"unauthorized"}` 是预期响应，表明网络和 HTTPS 已连通。证书错误、DNS 错误或超时需要先解决。
7. 在录音应用填写 HTTPS 根地址和云端上传令牌。地址末尾不要添加 `/v1/chunks`。修改设置前先停止录音；新地址也会用于补传已有本地片段。
8. 定期监控磁盘和备份 `/var/lib/recorder`。最简单的一致备份方法是短暂停止服务后复制整个目录，再启动；不应只复制正在写入的 SQLite 主文件而忽略 WAL 和录音文件。

Serve 入口受 tailnet 的访问控制限制。保持 `RECORDER_BIND=127.0.0.1`，通过 Serve 的私有 HTTPS 入口访问，不配置公网端口映射。本项目部署方式不使用 Funnel。HTTPS证书和代理由 Tailscale Serve 管理；存储 API 仍要求独立上传令牌。

手机 Tailscale 断开、VPN 权限被关闭、节点登录到期或 tailnet 策略不允许访问时，上传会失败并保留本地密文。录音应用使用 Android 系统的 VPN 网络，不会替你启动或登录 Tailscale。请在手机的 Tailscale/VPN 与应用电池设置中检查自动连接和后台运行，并实际测试熄屏及 Wi-Fi/移动网络切换。

这个 Python 服务按单进程设计，请只运行一个实例写同一数据目录。服务器不会持有录音恢复密钥。请先在本地验证，再部署服务器。电脑通过 Serve 下载密文时也需要连接到能访问该服务的 tailnet。

## Git 版本管理

仓库：[YFunction/Record](https://github.com/YFunction/Record)。`main` 为当前验证版；`v0.1.0` 保留初始验证项目，`v0.2.0` 对应 30 秒切片和 Tailscale Serve 部署。各版变化记录在 `CHANGELOG.md`。

源码、Gradle Wrapper、存储服务、测试和部署说明纳入 Git。恢复密钥、令牌配置、录音/密文、数据库、签名材料、APK 和构建缓存由 `.gitignore` 排除。发布版本签名密钥需要自己长期保管。

## 下载与电脑解密

电脑恢复工具需要 `cryptography`。在 WSL 创建虚拟环境：

```bash
cd /mnt/d/Record
python3 -m venv .venv
source .venv/bin/activate
pip install -r tools/requirements.txt
python tools/recover.py download --server https://recorder.your-tailnet.ts.net --output downloads/encrypted
python tools/recover.py decrypt --key-file /你安全保存的位置/recorder-recovery-key.txt --input downloads/encrypted --output downloads/plain
```

下载时会交互要求服务器令牌，也可使用 `RECORDER_TOKEN` 环境变量。下载、列表接口均需令牌，下载校验 SHA-256 并支持重跑补齐。解密时不需要联系服务器；输出每场录音的 `.aac` 和包含片段信息的 `.json`。输出音频是明文，请自行保护。

正常停止的完整会话应具有连续序号和最后一个结束标记。缺段会拒绝合并；没有结束标记时默认拒绝，以免把未上传完整的录音误认为完整。对于手机意外关机或进程终止后留下的连续片段，可在解密命令末尾加 `--allow-incomplete`，明确恢复部分录音。工具不覆盖已有解密输出。

## 验证清单

- 已完成的电脑协议测试：`python -m unittest discover -s tests -v`。
- 必须在手机上验证：已解锁双按冷启动；锁屏/熄屏双按冷启动；录音后熄屏至少 30 分钟；约 30 秒首片上传；不足 30 秒停止后的尾段上传；Tailscale 断开后继续本地录音及重连补传；Wi-Fi/移动网络切换；停止后结束标记上传；下载解密并播放；重复双按；来电打断；关闭麦克风访问；低存储空间。
- 在 MagicOS 的应用电池/启动管理设置中查看允许后台运行的选项，具体名称以手机实际菜单为准；设置后仍需长时间测试。

## 密文协议 v1

文件名：`<UUID>_<8位片段序号>.enc`。AES-GCM 附加认证数据为完整文件名的 UTF-8 字节，因此重命名也会导致认证失败。

外层：`ER01`（4 字节）+ 随机 nonce（12 字节）+ AES-GCM 密文及 16 字节认证标签。

解密后：`EAA1`（4 字节）+ JSON 长度（4 字节大端）+ 元信息 JSON + AAC ADTS 字节。JSON 含版本、会话、序号、开始时间、编码参数、样本数量及 `final`。结束标记的 `final=true` 且音频为空；服务器仅能校验外层结构和密文哈希，不能验证音频内容。

API：`PUT /v1/chunks/{name}`、`GET /v1/chunks?after={cursor}`、`GET /v1/chunks/{name}`。均需要 `Authorization: Bearer <token>`。上传还需 `X-Content-SHA256` 和 Content-Length；每片段上限 2 MiB。成功确认包含 `stored`、`name`、`sha256`、`size`。

## 官方参考

- [麦克风前台服务启动限制](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)
- [Android 16 后台任务配额变化](https://developer.android.com/develop/background-work/services/fgs/changes)
- [Android Keystore](https://developer.android.com/privacy-and-security/keystore)
- [MediaCodec](https://developer.android.com/reference/android/media/MediaCodec)
- [Tailscale Serve 命令与私有 HTTPS 代理](https://tailscale.com/docs/reference/tailscale-cli/serve)
- [Tailscale MagicDNS 与 HTTPS 证书](https://tailscale.com/docs/how-to/set-up-https-certificates)
