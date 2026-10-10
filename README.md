# 加密录音：荣耀 400 Pro 验证版 v0.8.0-beta.3

目标设备：荣耀 400 Pro / MagicOS 10.0 / Android 16。将系统的电源键双按快捷启动设置为“加密录音”。首次点击开始并授予麦克风权限后即可本地录音，无需服务器配置。随后每次进入默认自动开始录音，可在设置中关闭；重复进入不会创建第二份录音。

此项目包含 Android 源码、单用户 Linux 密文存储服务和电脑离线恢复工具。默认部署方式为 Tailscale Serve 的私有 HTTPS 入口，手机和 Linux 服务器加入同一 tailnet。云端不接收录音密钥、不解密、不转写、不播放音频。服务器仍能看到文件大小、上传时间、会话标识以及代理可见的网络信息。

## 工作方式

- `AudioRecord` 连续采集 → `MediaCodec` 编码 AAC-LC / 16 kHz / 单声道 / 64 kbps。
- 约每 30 秒对完整 AAC 帧分段，以 AES-256-GCM 加密后原子写入应用私有目录；不生成明文录音临时文件。AAC 帧边界会产生很小的时长误差，当前参数下一片约为 30.016 秒；正常停止时立即保存不足 30 秒的尾段。
- 连续录音时由独立上传线程发送已经完成的密文片段，上传检查约每 3 秒运行一次。第一片在录音约 30 秒后才会保存和上传；上传延迟还取决于网络、积压和服务器响应。
- 服务器校验 SHA-256、落盘并提交索引后返回确认；同名同内容可重试，同名不同内容返回冲突。
- 断网时继续本地录音。停止后由 WorkManager 安排补传，并每 15 分钟安排恢复检查。这是调度间隔，Android 电池管理可能延后执行。
- 本地默认保留已上传副本；可在设置中启用 30/90/180 天自动清理本机已确认上传且录音完整的音频，文字成果保留。云端默认永久保留；服务器可通过 `RECORDER_RETENTION_DAYS` 单独配置音频和文字密文保留天数，默认 `0`（不自动清理）。未上传密文不会自动删除。
- 正常停止时追加加密的结束标记；异常结束的会话可通过恢复工具显式选择恢复部分录音。
- 64 kbps 音频约 28.8 MB/小时，不含 AAC 帧头、加密及 HTTP 开销。

## 必须理解的行为

1. 首次安装无法跳过系统麦克风授权。首页默认“仅本地”，首次开始时自动生成密钥。备份密钥不阻止本地录音，但必须尽早备份；未备份且卸载/清除数据会失去解密能力。云端同步仍需服务器配置与成功导出密钥。
2. 应用在可见界面中启动 `microphone` 前台服务，随后支持后台/熄屏继续录音。录音时保留系统麦克风使用提示和前台服务通知；可在通知栏停止。
3. 应用在清单与运行时请求锁屏显示和唤醒屏幕，等待录音界面恢复并获得窗口焦点后启动服务，不主动要求解锁。电源键双按映射由 MagicOS 设置完成，应用不拦截电源键。若系统要求先解锁才能交付启动请求，应用无法单独改变这项系统行为；必须真机验证。重启后第一次解锁前，加密配置尚不可用。
4. 不承诺绕过 MagicOS 的进程回收。强行停止应用、重启手机、撤销权限、关闭麦克风访问或其他应用抢占麦克风，可能停止录音。重启后不会自动开始新录音，须再次双按/打开。
5. 普通麦克风录音不等于通话录音。来电/接听、蓝牙和其他音频应用的影响需要真机测试。
6. 若进程意外终止，已完成并落盘的片段保留；当前尚未完成的片段和编码器缓存可能丢失，通常为最近不足约 30 秒的音频。正常停止会保存尾段。
7. 当前为个人单用户验证版本，不包含成员/角色权限管理、自动密钥轮换或云端删除 API。服务器管理员可通过 `RECORDER_RETENTION_DAYS` 配置按上传时间自动删除旧密文；应用中设置的本机音频保留期不会更改服务器数据。

## 手机端使用

首页仅保留录音计时、开始/停止、保存状态和最近录音；其他选项统一收进右上角“设置”。首页、设置页和弹窗跟随系统浅色/深色模式。录音期间不可修改保存方式和密钥。锁屏界面可以开始/停止录音，访问历史录音、密钥和设置需要解锁。

- **仅本地**：不开启上传，不需要 Tailscale 或服务器令牌；音频仍按约 30 秒切片并加密。关闭云端同步会取消后台补传；已在发送的请求可能完成。切换为云端后会补传全部未确认的本地片段，包括此前仅本地录制的片段。
- **本地 + 云端**：填写 HTTPS 根地址和令牌，或在云端设置中选择“导入服务器连接配置”。配置文件为 JSON，字段为 `server` 和 `token`；文件包含访问凭据，请通过你控制的方式传到手机，不要提交 Git 或公开分享。配置保存后导出恢复密钥，才会开始上传。
- **最近录音**：点击录音选择播放、导出 AAC 或删除本地副本。列表时间是首片本地保存时间，完整时长在解密播放时显示。播放只在内存中保留一个已认证的明文片段，关闭页面即停止；没有明文临时录音文件。
- **实时文字**：先在“设置 → 文字与 AI”安装离线模型。录音时默认从同一麦克风 PCM 旁路识别，首页约每 5 秒更新文字和时间点，可滚动查看最近 80 段；更早的文字在停止后从加密文字页查看。可在设置关闭。识别在手机本地进行，录音优先，模型忙或处理跟不上时预览可能漏段。锁屏或离开应用时隐藏实时文字。停止后已识别文字加密保存到现有录音文字页；若识别失败，音频密文仍保留，可重新完整转写。
- **本地搜索**：在首页“最近录音”旁点击“搜索”，输入关键词并选择全部或某个分类。支持搜索手机已保存的转写、发言人称呼、AI 总结和大纲；多个词用空格分开，需出现在同一段转写或同一项成果中。点结果可打开录音详情并定位到匹配的转写片段。按需在手机内存解密，不建立明文索引，也不向云端发送搜索词；离开页面即隐藏结果。最多显示 80 条、每场最多 4 条。云端尚未恢复到手机的文字不会出现在结果中。
- **转写、回听与导出**：录音结束后可对本应用录制的音频在手机进行更完整的离线转写与发言人区分；点击文字时间点跳到录音对应位置，播放时按进度高亮并跟随当前片段。实时文字只是预览，暂不区分发言人；当前不支持导入外部音频文件。支持 Markdown、Word（DOCX）、PDF、SRT 和 WebVTT 文件导出，并可通过 Android 分享面板发送给 Notion、飞书等接收应用。字幕沿用转写片段的开始/结束时间，不推测逐字时间，较长片段和识别误差需人工修改。导出和分享均为明文，需用户确认；Notion/飞书通过系统分享接收文字，不是账号 API 集成。
- **本机音频保留期**：设置 → 密钥与存储可选择手动清理或 30/90/180 天。只会删除服务器确认保存且录音结束标记完整的手机音频，保留手机加密文字成果；本机策略不覆盖服务器的独立保留设置。到期后手机不能直接从服务器恢复音频。
- **服务器保留期**：在 `/etc/recorder.env` 设置 `RECORDER_RETENTION_DAYS=0`（永久保留，默认）或配置正整数天数。更新 systemd 服务后，服务器每小时清理已超过该上传保留期的音频与文字密文对象；该设置不需要录音密钥。过期数据会从服务器删除，电脑端无法再下载恢复。
- **分类与整理**：设置 → 分类与整理可管理“隐私录音、会议记录、课堂记录、访谈调研、未分类”和自定义分类，设置快速录音默认分类、纪要模板及分类级 DeepSeek 权限。升级前的录音显示为“未分类”，不会改写原录音；最近录音可筛选、重新归类。分类名称、模板、AI 权限及录音归属均加密后保存在手机并同步。
- **AI 隐私控制**：所有分类默认禁止外部 AI。用户可逐类开启；生成总结仍需在单条录音页面确认。禁用后不会再为该分类发起新请求，已经生成的总结仍可查看。模板包括会议、课堂、访谈、隐私备忘和通用格式。
- **导出 AAC**：导出文件是明文，使用系统文件选择器自行选择保存位置。连续但缺少结束标记的意外中断录音可导出已保存部分；中间缺段或认证失败会拒绝导出。导出失败可能留下部分目标文件，请自行删除并重试。
- **删除/清理**：删除本地录音不会删除云端副本；清理已上传片段可能让本地会话不完整，需要从云端下载完整密文后在电脑恢复。所有删除均需在手机确认。

设置包含录音、分类与整理、保存与同步、密钥与存储、文字与 AI、应用。普通页面允许截图，输入令牌和密钥仍使用密码输入框。升级会保留原密钥、录音和云端配置。

## 提取文字、区分发言人与 AI 总结

1. 在设置 → 文字与 AI 下载离线模型，约 **193 MiB**，安装时建议至少有 **700 MB** 可用空间。也可从 [v0.5.0 发布页](https://github.com/YFunction/Record/releases/tag/v0.5.0) 下载 `Record-speech-models-v1.zip`，传到手机后导入。下载与导入均校验包和每个文件的 SHA-256；模型安装到应用私有目录。当前 APK 仅包含 arm64-v8a，适用于荣耀 400 Pro。
2. 停止录音，点击最近录音 → 文字与 AI 总结 → 本地提取文字。已知发言人数可填 1～20；留空自动估计。使用 SenseVoiceSmall 识别中文等语言，pyannote segmentation-3.0 与 3D-Speaker ERes2Net 区分发言人。模型完整安装后可断网处理；不上传音频给识别或 AI 服务。每 5 分钟在内存解码处理一批，声纹向量仅在本场处理中保留，不保存到文件或上传。
3. 转写显示时间与“发言人 1、2…”；编号不是身份鉴定。噪声、短句、相近声音、重叠说话和跨批次匹配会产生误分。重叠语音标为待核对，可修改称呼及每段文字。同一人被分为多个编号时可将称呼设为相同名称。长连续语句约每 26 秒识别一次，边界可能影响准确度。结果需人工核对。
4. 在设置输入自己的 DeepSeek API Key，然后在文字页点击生成总结并确认。使用 [DeepSeek 官方 V4.1 Flash](https://api-docs.deepseek.com/en/)，API 名称为 `deepseek-flash`。仅向固定官方 HTTPS 地址发送转写文字、时间、发言人称呼及总结指令；不发送音频、录音恢复密钥或服务器令牌。Key 作为鉴权凭据发给 DeepSeek，使用 Android Keystore 包装后保存在手机，不传给你的存储服务器。打开文字页、转写和补传均不会自动请求 AI。费用由你的 DeepSeek 账户承担。
5. 总结包含摘要、各人要点、决定、待办、分歧及待核对事项。长文本分段总结后合并，不静默截掉后半段；超过 30 万字符会提示拆分录音。AI 仍可能遗漏或误解，请核对原文。取消处理保留旧结果，但已经发出的请求仍可能计费。重新转写成功后替换原转写及旧总结；手动修改文字或称呼会清除旧总结，需要重新生成。

**加密边界**：转写与总结使用同一已备份恢复密钥进行 AES-256-GCM 加密，保存在手机 `noBackupFilesDir/texts`；开启云端同步且配置完成后，补传密文到你的服务器。发给 DeepSeek 的文字使用 HTTPS 加密传输，但 DeepSeek 必须读取文字才能总结，这不是对 AI 服务商不可见的端到端加密。只需本地转写时无需提供 Key。录音的密文备份仍按原设置进行；AI 通道只有文字。

文字页及处理通知跟随系统浅色/深色模式。音频副本清理后保留已有文字；重新转写需要完整的本地音频。处理失败、取消、认证失败或响应截断不会覆盖已有成功记录。文字副本本地额度 64 MiB，单份解密文字最多 1 MiB；服务器文字额度默认独立为 256 MiB。当前模型尚未在你的荣耀手机上实测速度、内存占用与准确率；Windows 原生模型测试及 Android 界面测试不代替真机测试。

录音详情可导出**明文** Markdown、Word（DOCX）和 PDF；原有 TXT 导出仍可使用。通过系统分享面板可把文字发送到 Notion、飞书等应用，接收应用可能将内容上传到自己的云端。只有你确认导出或分享时才会产生这些明文。模型来源、作者及原始许可见设置 → 开源模型与许可，以及 `app/src/main/assets/speech-licenses`；许可随 APK 和模型包分发。

锁屏双按仍要求解锁时，先观察有没有出现录音首页：如果只有系统解锁页面，可能是 MagicOS 尚未向应用交付启动请求；如果首页出现但录音失败，进入设置 → 锁屏启动排查 → 导出诊断。诊断仅记录最近启动事件、窗口焦点、锁屏状态和权限，不含令牌、密钥或音频内容。`FLAG_SECURE` 只影响截图和非安全显示，与锁屏启动限制不同。

## 密钥与恢复

手机生成随机 32 字节录音密钥，并用 Android Keystore 内的不可导出密钥包装后保存。上传令牌和 DeepSeek Key 同样包装保存。该恢复密钥同时用于解密转写及总结。系统备份关闭，密文文件放在 `noBackupFilesDir`。

**恢复密钥导出文件是明文 Base64 密钥。持有它的人可以解密全部录音，请离线保管，不要放到录音云服务器。** 备份文件和云端密文分开保管。首次初始化也可输入已有恢复密钥；完成初始化后不支持直接替换录音密钥。

手机丢失、卸载应用或清除应用数据后，恢复密钥用于在电脑解密云端文件。没有备份密钥，就不能恢复录音。导出成功仅代表文件写入成功，请自行确认文件能读取且有额外备份。

## Android 构建

已构建的测试包位于 `app/build/outputs/apk/debug/app-debug.apk`，可直接安装到手机进行联调。只有修改源码及重新构建时，才需要下面的开发环境。应用最低支持 Android 12。

安装 Android Studio，通过 SDK Manager 安装 Android SDK Platform 36、Build Tools 35.0.0，并使用 JDK 17 或 21。项目使用 AGP 8.13.2、Gradle 8.13、WorkManager 2.11.2。

先使用 Python 3.10+ 获取固定版本且校验 SHA-256 的官方 sherpa-onnx Android 依赖，再在 Android Studio 打开此目录。模型权重另行下载安装，不包含在源码或 APK 中。如使用命令行：

```powershell
python tools/prepare_speech.py
.\gradlew.bat assembleDebug lintDebug testDebugUnitTest
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

## 云端文字路径与电脑恢复

默认部署音频仍位于 `/var/lib/recorder/chunks/`，索引 `/var/lib/recorder/index.sqlite3`。
新增文字与总结密文位于 **`/var/lib/recorder/texts/chunks/`**，独立索引 `/var/lib/recorder/texts/index.sqlite3`。自定义 `RECORDER_DATA` 时位于该目录下 `texts/chunks`。服务器不运行语音模型或 DeepSeek，也不持有解密密钥及 DeepSeek Key。更新服务器仍使用已有的一条命令 `sudo bash deploy/install.sh`，保留已有数据、令牌和 Tailscale Serve 入口。

文字使用不可变版本文件 `<录音UUID>_<版本UUID>.enc`。每次修正或生成总结产生新版本；云端保留历史版本。`PUT /v1/documents/<文件名>`、`GET /v1/documents`、`GET /v1/documents/<文件名>` 与音频一样使用 Bearer 令牌、SHA-256、幂等确认、分页列表和不可覆盖约束。密文格式为 `ET01 | 12字节 nonce | AES-GCM 密文及认证标签`，AAD 为 UTF-8 `text-v1:<文件名>`，明文为含 `version=1`、`session`、`updatedAt`、`segments`、`names`、`summary` 的 JSON。录音与文字 AAD 分开，防止混用。

电脑恢复需要单独备份的恢复密钥，令牌交互输入，不写入命令历史：

```bash
python -m pip install -r tools/requirements.txt
python tools/text_recover.py download --server https://你的设备.你的tailnet.ts.net --output downloads/texts
python tools/text_recover.py decrypt --key-file /安全位置/recorder-recovery-key.txt --input downloads/texts --output /安全位置/recovered-texts
```

恢复工具认证全部文件后才导出，每个版本生成一个明文 JSON。按 `session` 区分录音，同一场以 `updatedAt` 最大的版本为最近保存结果；版本 UUID 的字典顺序不代表时间。已存在的输出拒绝覆盖。导出目录含隐私明文，请自行保管；尚不支持直接在手机恢复云端文字。

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

### 一条命令安装

前提：Ubuntu 已安装 Python 3.10+、Git、Tailscale，服务器和手机登录同一 tailnet，并已在 Tailscale DNS 设置中启用 MagicDNS 与 HTTPS Certificates。首次启用证书需要在管理控制台确认，节点域名会出现在证书透明度日志中。此配置不需要启用 Funnel。

```bash
git clone https://github.com/YFunction/Record.git
cd Record
sudo bash deploy/install.sh
```

安装命令自动检查端口冲突、建立服务用户、安装/重启 systemd 服务、生成首次令牌、配置私有 Serve HTTPS、验证鉴权，并在执行 sudo 的用户家目录生成 `record-server-config.json`。文件权限为 600，内容只有 `server` 和 `token`，不包含录音恢复密钥。

将配置文件私下传到手机，在应用“设置 → 导入连接配置”选择它，再单独备份恢复密钥即可同步。不要公开分享配置文件或提交到 Git。手机需保持 Tailscale 已连接，访问规则允许连接服务器 HTTPS 端口。

重复运行同一安装命令会更新服务器代码并重启服务，保留 `/etc/recorder.env` 的令牌及已有密文。已有仓库可先运行 `git pull --ff-only` 再安装。如果 443 根路径已有其他服务，脚本会在修改前退出；可选择空闲端口：

```bash
sudo bash deploy/install.sh --https-port 8443
```

仅安装/更新存储服务而由自己配置代理时，可用 `--no-serve`；该选项跳过 HTTPS 入口配置和手机导入文件生成。脚本不会替你安装或登录 Tailscale。

### 云端保存位置

默认音频密文位于 `/var/lib/recorder/chunks/`，文字密文位于 `/var/lib/recorder/texts/chunks/`，加密分类目录是固定文件 `/var/lib/recorder/category-catalog.enc`；音频索引位于 `/var/lib/recorder/index.sqlite3`（运行时可能伴随 `-wal`、`-shm`）。服务代码在 `/opt/recorder/server/server.py`，服务配置在 `/etc/recorder.env`，默认音频额度 10 GiB。数据位置可通过 `RECORDER_DATA` 调整。分类文件只含 AES-GCM 密文，服务器看不到分类名称、模板、AI 权限或录音归属。

检查服务：`sudo systemctl status recorder`；检查入口：`sudo tailscale serve status`。浏览器打开 HTTPS 根地址加 `/v1/chunks`，没有令牌时应返回 `{"error":"unauthorized"}`，说明网络入口已可达。应用填写根地址，不加 `/v1/chunks`。

定期备份整个数据目录。最简单的一致备份方法是短暂停止服务后复制整个 `/var/lib/recorder`，再启动；不要仅复制正在写入的 SQLite 主文件而忽略 WAL 和密文。

Serve 入口受 tailnet 访问控制限制。服务保持 `RECORDER_BIND=127.0.0.1`，不需要开放公网 8080 或设置端口映射。HTTPS 证书和代理由 Tailscale Serve 管理，存储 API 仍要求独立上传令牌。

手机 Tailscale 断开、VPN 权限被关闭、节点登录到期或 tailnet 策略不允许访问时，上传会失败并保留本地密文。录音应用使用 Android 系统的 VPN 网络，不会替你启动或登录 Tailscale。请在手机的 Tailscale/VPN 与应用电池设置中检查自动连接和后台运行，并实际测试熄屏及 Wi-Fi/移动网络切换。

这个 Python 服务按单进程设计，请只运行一个实例写同一数据目录。服务器不会持有录音恢复密钥。请先在本地验证，再部署服务器。电脑通过 Serve 下载密文时也需要连接到能访问该服务的 tailnet。

## Azure 公网 HTTPS 直连（可选）

如果手机不使用 Tailscale，可在 Azure 静态公网 IPv4 上配置 DNS 名称标签，并用 Caddy 提供 HTTPS 反向代理。当前部署地址为 `record-api-yf-2026.koreacentral.cloudapp.azure.com`。

- Azure 网络安全组保留现有 SSH 入站规则，只允许公网访问 TCP 80 和 443，并显式拒绝 TCP 8080。
- 录音服务继续绑定 `127.0.0.1:8080`。Caddy 绑定 Azure 网卡的静态私有地址 `10.1.1.4`，代理到 `127.0.0.1:8080`。这里使用私有地址是为了避开 Tailscale Serve 已占用的 Tailscale 网卡 443 监听。
- Caddy 自动申请并续期公开 TLS 证书，并把 HTTP 请求重定向到 HTTPS。手机可在“设置 → 导入连接配置”导入本机配置文件，或把服务器地址设为 `https://record-api-yf-2026.koreacentral.cloudapp.azure.com`。
- 此入口可从公网访问，因此 API 仍要求独立上传令牌。录音和文字在客户端加密；服务器仍可看到连接 IP、上传时间和数据大小。Tailscale 入口仍可继续使用。

## Git 版本管理

仓库：[YFunction/Record](https://github.com/YFunction/Record)。`main` 为当前验证版；`v0.1.0` 保留初始验证项目，`v0.2.0` 对应 30 秒切片和 Tailscale Serve 部署，`v0.3.0` 增加本地录音、播放和导出，`v0.4.0` 增加独立设置、浅色/深色适配与一条命令部署，`v0.5.0` 增加离线转写和 AI 文字总结，`v0.6.0` 增加分类与资料整理，`v0.7.0-beta.2` 修复弹窗首次定位，`v0.8.0-beta.1` 增加本地实时文字预览，`v0.8.0-beta.2` 增加本地全文搜索，`v0.8.0-beta.3` 增加字幕导出和回放逐段高亮。各版变化记录在 `CHANGELOG.md`。

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
- 手机读取逻辑的 JVM 测试：`gradlew testDebugUnitTest`，覆盖跨切片顺序导出/随机读取、篡改、错密钥、缺段、结束标记和本地模式不依赖云端；另有 Robolectric Android 16 界面测试，覆盖浅色/深色首页与设置、允许截图、锁屏下等待窗口焦点且不请求解锁。模拟测试不等于真机播放、采集或 MagicOS 电源键行为验证。
- 必须在手机上验证：已解锁双按冷启动；锁屏/熄屏双按冷启动；录音后熄屏至少 30 分钟；约 30 秒首片上传；不足 30 秒停止后的尾段上传；Tailscale 断开后继续本地录音及重连补传；Wi-Fi/移动网络切换；停止后结束标记上传；下载解密并播放；重复双按；来电打断；关闭麦克风访问；低存储空间。
- 在 MagicOS 的应用电池/启动管理设置中查看允许后台运行的选项，具体名称以手机实际菜单为准；设置后仍需长时间测试。

## 密文协议 v1

文件名：`<UUID>_<8位片段序号>.enc`。AES-GCM 附加认证数据为完整文件名的 UTF-8 字节，因此重命名也会导致认证失败。

外层：`ER01`（4 字节）+ 随机 nonce（12 字节）+ AES-GCM 密文及 16 字节认证标签。

解密后：`EAA1`（4 字节）+ JSON 长度（4 字节大端）+ 元信息 JSON + AAC ADTS 字节。JSON 含版本、会话、序号、开始时间、编码参数、样本数量及 `final`。结束标记的 `final=true` 且音频为空；服务器仅能校验外层结构和密文哈希，不能验证音频内容。

API：`PUT /v1/chunks/{name}`、`GET /v1/chunks?after={cursor}`、`GET /v1/chunks/{name}`；文字使用 `/v1/documents` 接口；加密分类目录使用固定槽位 `PUT/GET /v1/category-catalog`，每次更新覆盖服务器上的单个密文文件。均需要 `Authorization: Bearer <token>`。上传还需 `X-Content-SHA256` 和 Content-Length；音频片段上限 2 MiB，分类密文上限 1 MiB。成功上传确认包含 `stored`、`name`、`sha256`、`size`。

## 官方参考

- [麦克风前台服务启动限制](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)
- [Android 16 后台任务配额变化](https://developer.android.com/develop/background-work/services/fgs/changes)
- [FLAG_SECURE 与截图限制](https://developer.android.com/security/fraud-prevention/activities)
- [Activity 锁屏显示与屏幕唤醒](https://developer.android.com/reference/android/app/Activity.html)
- [Android Keystore](https://developer.android.com/privacy-and-security/keystore)
- [MediaCodec](https://developer.android.com/reference/android/media/MediaCodec)
- [Tailscale Serve 命令与私有 HTTPS 代理](https://tailscale.com/docs/reference/tailscale-cli/serve)
- [Tailscale MagicDNS 与 HTTPS 证书](https://tailscale.com/docs/how-to/set-up-https-certificates)
