# 可选的自建 Python 服务

0.3 已增加独立的机主私有托管控制台，手机首次配对见 ../docs/INSTALL.md。下面保留自建 Python 服务路线；本次没有将这个 Python 程序部署到公网，也没有用它替代托管队列。

# HTTPS 桥接服务（未部署）

这是可运行的单设备测试桥接服务，采用 Python 3.10+ 标准库、SQLite 和 HTTPS。没有 Shell、ADB、任意文件、代码执行或模型调用接口。不需要付费模型服务即可生成固定测试计划。

**如果你只有手机、还没有服务器，先用应用内演示或手动动作数组，不需要完成本页。** 本次交付没有替你部署常驻服务器、注册域名、申请证书或登录 Codex。

## 协议

| 入口 | 认证 | 行为 |
| --- | --- | --- |
| POST /v1/operator/tasks | 管理令牌 | 校验并排队固定动作任务 |
| GET /v1/devices/设备ID/next | 设备令牌 | 领取一条计划；空队列返回 204；领取即标记，不重复投递 |
| POST /v1/devices/设备ID/result | 设备令牌 | 上传 taskId/status/completedSteps 三字段结果 |
| GET /v1/operator/results/任务ID | 管理令牌 | 读取执行回执 |

所有令牌通过 `Authorization: Bearer ...`，不放 URL。管理令牌与设备令牌必须不同。服务只绑定一个明确设备 ID。所有未列路径均不提供功能；没有通用代理或命令端点。

## 服务配置

在你控制的服务器上准备 Python 3.10+、系统信任的 HTTPS 域名证书及对应私钥。本服务直接提供 TLS；手机默认使用 Android 系统证书信任和域名校验。不要关闭证书验证，不要把测试自签证书错误当作正常可用。

在可信终端设置环境变量（下列随机令牌由本地生成，不是 OpenAI 账号令牌）：

```bash
# 从手机“任务桥接”页复制设备 ID；不是隐私账号信息或令牌。
export BRIDGE_DEVICE_ID='填写手机显示的设备ID'
export BRIDGE_DEVICE_TOKEN="$(python3 -c 'import secrets; print(secrets.token_urlsafe(32))')"
export BRIDGE_OPERATOR_TOKEN="$(python3 -c 'import secrets; print(secrets.token_urlsafe(32))')"
export BRIDGE_CREDENTIAL_EXPIRES="$(python3 -c 'import time; print(int(time.time())+3600)')"

# 在 bridge 目录运行。默认只监听 127.0.0.1；外部可达须显式指定地址。
python3 server.py --bind 0.0.0.0 --port 8443 \
  --cert /secure/path/fullchain.pem --key /secure/path/privkey.pem \
  --db /secure/path/bridge.sqlite3
```

只公开必要的 HTTPS 服务端口，绝不公开 ADB 或 Shell。实际公网托管需另做访问限制、日志脱敏和流量防护；这个轻量测试服务限制 8 并发与 8 秒读取超时，不是生产托管平台。私钥和数据库权限由部署者管理，数据库会设置为 0600。不要把环境变量输出到聊天、公开仓库或 Actions 日志。

将 HTTPS 地址（例如你自己的 `https://bridge.example.com:8443`）和设备令牌在手机中手动保存。手机用 Android Keystore AES-GCM 加密设备令牌。服务器端两种凭据启动时均必须有效，最长 24 小时，默认示例 1 小时。过期后需双方手动轮换令牌并重启服务；没有自动刷新或撤销 API。立即撤销方式是停止服务/轮换令牌，手机本地删除配置不能替代服务端撤销。

本项目未实现公钥配对、设备硬件证明或端到端任务签名；目前信任 TLS 及配对的服务端，服务端也有能力提交允许的测试任务。所有执行仍需本机确认。

## 发送一个测试计划

在可信操作端使用同一管理令牌：

```bash
export BRIDGE_ORIGIN='https://你的域名:8443'
# BRIDGE_OPERATOR_TOKEN 已在可信终端设置，不要写死到脚本。
python3 submit_task.py --device "$BRIDGE_DEVICE_ID" \
  --actions INSPECT CLICK INPUT SWIPE --submit
```

手机点“从桥接服务拉取一条计划”，预览后进入测试页逐步确认。完成或取消后返回桥接页，先预览回执，再确认上传。回执只包含任务 ID、状态码、完成步数，不包含节点 ID、界面内容、输入、聊天记录或配对令牌。

如果不加 `--submit`，脚本只打印用于手动导入的完整 JSON。完整信封需在 5 分钟内导入、进入测试页并完成各步。设备时间需要合理准确。未来签发超过 60 秒、有效期超过 300 秒或已到期均拒绝。

手机手动建议数组与 HTTPS 信封是两个入口：网络拉取只接受完整信封，不能发送动作数组来绕过设备绑定、防重放或有效期。

## 失败与幂等

服务端使用 SQLite 约束 taskId 和 nonce 唯一，手机在导入前同步持久化已见记录；取消计划不会释放未过期记录。服务端最多存 10000 条任务，满后拒绝，不自动删除未消费记录。需要维护时先让所有旧计划过期，再备份或轮换数据库。

领取采用 at-most-once：服务端标记后传输中断可能导致任务丢失。此时不要重复投递原 ID，先确认手机状态，再创建新的计划并人工确认。提交相同终态回执可重复；不同终态被拒绝，避免把部分完成谎报为完全完成。设备重装会清除本地防重放和设备 ID，需要重新绑定，不能声称跨卸载永久防重放。

## 官方 Codex SDK（可选，未做账号实测）

`codex_plan.mjs` 使用 `@openai/codex-sdk` 的官方服务端 SDK，只输出 1–10 个白名单动作名称。它不会直接向手机提交任务或替用户确认。先通过官方支持流程完成你自己的账号认证，再按当前官方权限及额度使用；不要提供 session、Cookie 或私有账号接口。

```bash
npm install @openai/codex-sdk
# 在不含私有资料的独立工作目录运行；不要向代理进程继承桥接令牌。
(unset BRIDGE_DEVICE_TOKEN BRIDGE_OPERATOR_TOKEN; node codex_plan.mjs '先识别，再点击两次，最后输入固定测试文字')
```

检查建议后，可直接手动粘贴到手机；或者将动作名称作为 `submit_task.py --actions ...` 的参数提交。SDK 会启动本地 Codex 代理；只读沙箱并不等于禁用所有工具。不要在含服务密钥、私人文件或配对令牌的工作目录/环境中运行。SDK 需要运行在受支持的服务器/开发环境，不直接嵌入 APK。这是独立本地 SDK 线程，不是自动连接到当前 Codex Cloud 聊天。示例未调用模型、未验证账号/地区/额度，也不保证免费或无额度消耗。

## 测试

在项目根目录：

```bash
python3 -m unittest discover -s bridge -v
```

测试自动生成临时自签证书，并只在测试客户端中明确将它设为受信任 CA。验证证书错误失败、令牌角色隔离、严格协议、重复投递拦截和 HTTPS 排队→领取→回执。没有修改系统信任或使用 trust-all。APK 到真实外部服务器、Android Keystore 及 K40 页面交互需另行真机验收。
