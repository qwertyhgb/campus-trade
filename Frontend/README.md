# Campus Trade Frontend

本目录是校园交易与活动预约平台的本地学习前端，基于 Vue 3、TypeScript、Vite、Vue Router、Pinia、Axios 和 Element Plus。

## 启动前准备

1. 启动 Docker 中的 MySQL、Redis、RabbitMQ。
2. 启动 Spring Boot 后端，并确认 `http://localhost:8080/doc.html` 可访问。
3. 在本目录执行 `npm run dev`。

前端会将 `/api/**` 和 `/upload/**` 自动代理至 `http://localhost:8080`；代理地址可在 `.env.development` 修改。

## 已建立的骨架

- `src/api/`：Axios 实例、统一响应拆包、登录/商品/活动接口示例。
- `src/stores/`：登录状态与本地 Token 持久化。
- `src/router/`：公开路由、登录保护、角色路由占位。
- `src/components/` 与 `src/layouts/`：站点导航、页脚、页面容器、空状态。
- `src/views/`：首页、登录注册、商品/活动列表与详情，以及后续模块占位页。

完整开发顺序请阅读仓库根目录的 `docs/frontend_plan.md`。
