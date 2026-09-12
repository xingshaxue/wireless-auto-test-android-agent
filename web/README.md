# 无线自动化测试控制台（Web 前端）

生产 server（`server/`）的配套 Web 控制台：agent 监控、设备/配置管理、命令下发、
文件传输、测试编排、实时事件流。Vue 3 + Vite + TypeScript + Element Plus。

## 开发

```bash
export PATH="$HOME/.local/node/bin:$PATH"
cd web
npm install --registry=https://registry.npmmirror.com
npm run dev        # http://127.0.0.1:5173，/api 与 /ws 代理到 127.0.0.1:8080
```

登录页输入 server 的 `api.token`（本地试运行配置为 `demo-api-token`）。

## 构建与部署

```bash
npm run build      # 产出 web/dist/（vue-tsc 严格检查 + vite build）
```

server 的 FastAPI 在启动时检测 `web/dist/index.html`，存在即挂载到 `/`
（SPA fallback；`/api`、`/ws` 路由优先）。因此生产部署 = 先 `npm run build`，
再启动 server，浏览器访问 `http://<server-host>:8080` 即可，单端口交付。
dist 不存在时 server 退化为纯 API 模式（日志有提示）。

## 页面

| 页面 | 功能 |
|------|------|
| Dashboard | agent 在线卡片（槽位/设备数/CPU/内存/心跳）+ 设备状态表（5s 轮询） |
| Agents | agent 详情、19 类命令动态表单下发、命令台账 |
| 设备管理 | DUT CRUD（§16.4 JSON）、轮询间隔/规则/常驻在线调整、全局参数、最大连接数 |
| 文件传输 | 文件上传/删除、发起传输、任务进度（2s 轮询）与取消、日志上传触发 |
| 测试编排 | 场景运行（path/内联 JSON）、运行历史、步骤级报告（P50/P95）、停止 |
| 事件流 | WebSocket 实时事件（类型/agent 过滤、暂停、清空、断线重连） |

## 说明

- 认证：单一 API token（登录后存 localStorage），无多用户体系，限实验室/内网使用
- API 契约以 `server/src/wireless_server/api/routes.py` 为准（`src/api/types.ts` 逐字段对齐）
