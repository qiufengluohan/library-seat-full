# library-admin — Vue3 Web 管理端

本目录是**源码**。文档已全部集中到仓库的 `docs/` 下，这里只留一个入口。

| 文档 | 内容 |
| --- | --- |
| [../docs/library-admin/01-管理端说明.md](../docs/library-admin/01-管理端说明.md) | 技术栈、6 个页面、状态颜色、数据契约、运行方式 |
| [../docs/library-admin/04-管理端开发注意事项.md](../docs/library-admin/04-管理端开发注意事项.md) | ★ **改代码前必读**：请求层两条判断线、409 静默、401 必须带出 message、WebSocket 约定、Vite 代理的坑 |

全仓库总览与快速开始：[../README.md](../README.md)

---

## 三行速查

```bash
npm install     # 必须！node_modules 被 .gitignore 忽略，新副本里没有
npm run dev     # http://localhost:3000，/api 与 /ws 自动代理到 8080
npm run build   # 产出 dist/
```

前置条件：MySQL 已建库建表、`library-seat` 已启动。
登录：管理员 `admin` / `Admin@123`（开发演示用，公网部署前必须改）。

> ⚠️ 本端**只和 Spring Boot 通信**，不直连 OneNET / MQTT，
> `package.json` 里不该出现任何物联网平台 SDK 或设备密钥。
