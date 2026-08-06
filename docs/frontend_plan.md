# 校园交易与活动预约平台：前端开发计划

> 项目定位：本项目仅用于本地学习与演示，不考虑部署、上线、SEO、浏览器兼容性和高可用。
>
> 本计划以当前 Spring Boot 后端为准。开始每个模块前，先在 `http://localhost:8080/doc.html` 核对该模块的最终请求字段与错误码；本文用于安排开发顺序和约束前端实现，而不替代 Knife4j 接口文档。

## 1. 最终目标与完成标准

完成一个可以在本机完整演示的单页应用（SPA）：

1. 未登录用户可以浏览商品、商品留言、活动列表、活动详情和热门活动。
2. 普通用户可以注册、登录、维护资料、发布/管理商品、收藏、留言、下单、管理订单、预约活动、加入候补、查看通知。
3. 组织者可以创建活动、编辑活动、提交审核，并查看活动报名名单。
4. 审核员可以审核待审核活动；管理员可以审核商品、下架活动、管理分类、封禁用户和查看平台订单/操作日志。
5. 所有页面都有加载、空数据、失败和无权限状态；所有写操作有二次确认或防重复提交。
6. 前端能通过 Vite 代理与本地后端联调，不需要为了开发而修改后端 CORS。

### 非目标（本阶段明确不做）

- 不做部署、域名、HTTPS、Nginx、Docker 镜像、CI/CD。
- 不做服务端渲染、SEO、国际化、主题切换、复杂动效、WebSocket 实时推送。
- 不做真实支付、物流、聊天、地图、邮件/短信客户端。
- 不追求一开始覆盖所有后台能力；先完成前台核心闭环，再补齐角色后台。

## 2. 推荐技术栈与约定

| 类别 | 选择 | 使用原则 |
| --- | --- | --- |
| 框架 | Vue 3 + Composition API | 所有新组件使用 `<script setup lang="ts">`。 |
| 构建工具 | Vite | 只用于本地开发；通过开发代理访问后端。 |
| 语言 | TypeScript | 请求参数、响应数据、状态映射必须有类型。 |
| 路由 | Vue Router | 路由守卫只改善体验，后端权限校验才是最终边界。 |
| 状态管理 | Pinia | 仅保存登录态、用户信息、未读数、少量跨页 UI 状态。列表数据优先保存在页面内。 |
| HTTP | Axios | 统一封装 Token、响应拆包、401/403/429/业务错误处理。 |
| UI 组件库 | Element Plus | 只使用表单、表格、对话框、分页、消息提示等基础组件，不深度魔改主题。 |
| 时间处理 | dayjs | 统一格式化后端的 `LocalDateTime` 字符串。 |
| 图标 | Element Plus Icons 或一个固定图标库 | 全项目只保留一套图标来源。 |

不在文档中锁定具体依赖版本：创建项目时采用脚手架生成的稳定版本，并将最终版本锁定在 `package.json`。Vue 官方脚手架支持创建 Vite 驱动的 TypeScript 项目；Vite 原生支持开发代理；Axios 支持请求与响应拦截器。

## 3. 本地开发前置条件

### 3.1 后端与中间件

启动顺序如下：

1. 在 Docker 中启动 MySQL、Redis、RabbitMQ。
2. 确认 MySQL 已创建 `campus_trade` 数据库并执行 `src/main/resources/db/` 下的建表脚本。
3. 核对 `application-dev.yaml` 的 MySQL、Redis、RabbitMQ 地址与 Docker 端口一致。
4. 启动后端，访问 `http://localhost:8080/doc.html`；能打开文档才开始真实联调。
5. 前端访问商品或活动公开接口，确认能拿到统一 JSON 响应。

> RabbitMQ 未运行时，完整 Spring 上下文测试或后端启动可能失败；它是通知和订单超时功能的本地依赖，不应由前端绕过。

### 3.2 前端项目初始化

建议在仓库根目录新建独立目录 `frontend/`，不将 Vue 文件放进 Spring Boot 的 `resources/static`。

```text
campus-trade/
├── src/                 # 现有 Spring Boot 后端
├── docs/
└── frontend/            # 新建：Vue 前端项目
```

建议初始化时勾选 TypeScript、Vue Router、Pinia、ESLint 和 Prettier。安装完依赖后，先只跑起默认首页，确认 `npm run dev` 能正常访问，再开始复制业务代码。

### 3.3 Vite 开发代理

前端只访问相对路径 `/api` 和 `/upload`：

- `/api/**` 转发到 `http://localhost:8080/**`，并去掉 `/api` 前缀。
- `/upload/**` 转发到 `http://localhost:8080/upload/**`，使商品/活动图片能直接展示。
- 这样浏览器实际请求的是 Vite 本地服务，不需要后端 CORS 配置。

`vite.config.ts` 的目标形态如下（端口以实际后端配置为准）：

```ts
server: {
  proxy: {
    '/api': {
      target: 'http://localhost:8080',
      changeOrigin: true,
      rewrite: (path) => path.replace(/^\/api/, ''),
    },
    '/upload': {
      target: 'http://localhost:8080',
      changeOrigin: true,
    },
  },
}
```

将 `/api` 写进 `.env.development`：

```env
VITE_API_BASE_URL=/api
```

前端不得把 `http://localhost:8080` 散落硬编码在页面组件中；所有请求统一从环境变量和 `request.ts` 进入。

## 4. 后端接口契约与前端约束

### 4.1 统一响应与分页

后端所有接口使用 `Result<T>` 包装，前端请求层统一将成功响应拆成 `data`，页面层不要反复写 `response.data.data`。

```ts
export interface ApiResult<T> {
  code: number
  message?: string
  data: T
}

export interface PageResult<T> {
  records: T[]
  total: number
  pages: number
  current: number
  size: number
}
```

分页接口约定：

- 请求统一使用 `pageNo`、`pageSize`；页码从 `1` 开始。
- 页面将后端 `records` 传给列表，将 `total` 传给分页组件。
- 搜索、筛选和切换 Tab 后，必须把 `pageNo` 重置为 `1`。
- `pageSize` 初期统一固定为 `10`，不要先做用户自定义每页条数。

### 4.2 登录态与错误处理

登录接口 `POST /user/login` 返回 `LoginVO`：`token` 和 `userVO`。前端保存 Token 后，所有受保护请求自动携带：

```http
Authorization: Bearer {token}
```

请求层必须实现以下规则：

| 场景 | 统一处理 |
| --- | --- |
| 业务成功 | 返回 `data`，页面自行更新数据或重新加载列表。 |
| HTTP 401 | 清除本地登录态，保留当前目标地址，跳转登录页，并提示“登录已失效”。 |
| HTTP 403 | 停留在当前页或跳到无权限页，提示“没有操作权限”。 |
| HTTP 429 | 提示“操作过于频繁，请稍后再试”，不自动重试。 |
| 400 / 业务错误码 | 弹出后端返回的错误消息；不吞掉错误，页面 `finally` 必须恢复按钮状态。 |
| 网络错误 | 提示“无法连接后端，请检查后端和 Docker 服务是否已启动”。 |

学习项目可将 Token 存在 `localStorage`，便于刷新页面后继续联调；不要在多个 key 中重复保存 Token。推荐 key：`campus-trade-token` 与 `campus-trade-user`。

### 4.3 多角色接口契约：先确认后开发后台菜单

当前 `LoginVO` 中的 `userVO` 包含旧的整数 `role` 字段，但后端授权已使用 `USER`、`ORGANIZER`、`AUDITOR`、`ADMIN` 多角色模型。**不要凭一个整数角色字段猜测菜单权限。**

在实现组织者、审核员、管理员菜单前，先完成下面其中一种方案：

1. 推荐：后端在登录响应与 `/user/me` 响应中增加 `roleCodes: string[]`，例如 `['USER', 'ORGANIZER']`。
2. 临时学习方案：只实现普通用户前台；角色后台通过固定测试账号和临时前端配置进入，且在代码注释中标明这是过渡方案。

无论前端菜单是否隐藏，不能把隐藏菜单当成安全控制；后端 `@PreAuthorize` 与 Service 校验仍是实际权限边界。

### 4.4 上传与图片地址

- 图片先请求 `POST /upload/image`，再把后端返回的图片路径写进商品/活动表单。
- 上传时使用 `FormData`，不要手动设置 `Content-Type`，由浏览器附带 multipart boundary。
- 图片地址若为 `/upload/xxx.jpg`，可直接作为 `src` 使用，Vite 代理会转发；若接口返回完整 URL，则直接使用完整 URL。
- 每个图片区域都要有上传中、上传失败、预览和移除状态。

### 4.5 活动创建的幂等 Token

`POST /activity/create` 需要请求头 `Idempotency-Token`。前端流程必须是：

```text
点击“创建活动”
  → 禁用提交按钮
  → POST /idempotency/token/activity:create 获取一次性 Token
  → 在 Idempotency-Token 请求头中调用 POST /activity/create
  → 成功：跳转“我的活动”或活动编辑页
  → 失败：恢复按钮；若 Token 失效，下一次点击重新领取 Token
```

不要提前在页面打开时领取 Token，也不要把它长期保存在 Pinia 或 localStorage。

## 5. 推荐目录结构

```text
frontend/src/
├── api/                    # 每个业务模块一个 API 文件，只做请求与类型转换
│   ├── request.ts
│   ├── user.ts
│   ├── product.ts
│   ├── order.ts
│   ├── activity.ts
│   ├── reservation.ts
│   ├── waitlist.ts
│   ├── notification.ts
│   ├── category.ts
│   ├── admin.ts
│   └── upload.ts
├── assets/                 # 本地静态图片、全局样式
├── components/             # 跨页面复用组件
│   ├── AppHeader.vue
│   ├── AppFooter.vue
│   ├── PageContainer.vue
│   ├── EmptyState.vue
│   ├── StatusTag.vue
│   ├── ImageUploader.vue
│   ├── PaginationBar.vue
│   └── ConfirmActionButton.vue
├── composables/            # 可复用组合式逻辑
│   ├── usePageQuery.ts
│   ├── useAsyncAction.ts
│   ├── useStatusText.ts
│   └── useImageUrl.ts
├── constants/              # 状态、路由名、存储 key
│   ├── status.ts
│   └── storage.ts
├── layouts/
│   ├── PublicLayout.vue
│   ├── UserLayout.vue
│   └── AdminLayout.vue
├── router/
│   ├── index.ts
│   └── guards.ts
├── stores/
│   ├── auth.ts
│   ├── app.ts
│   └── notification.ts
├── types/                  # 与 VO / DTO 对应的 TypeScript 类型
│   ├── api.ts
│   ├── user.ts
│   ├── product.ts
│   ├── order.ts
│   └── activity.ts
├── utils/
│   ├── format.ts
│   └── validators.ts
├── views/                  # 页面组件，按业务分目录
│   ├── auth/
│   ├── product/
│   ├── order/
│   ├── activity/
│   ├── user/
│   ├── organizer/
│   ├── auditor/
│   ├── admin/
│   └── error/
├── App.vue
└── main.ts
```

约束：页面组件不直接调用 Axios；页面只调用 `api/*.ts` 暴露的函数。状态数字不散落写在模板中，必须通过 `constants/status.ts` 的字典渲染。

## 6. 页面、路由与权限划分

### 6.1 公开页面

| 路由 | 页面 | 主要能力 | 关联接口 |
| --- | --- | --- | --- |
| `/` | 首页 | 商品推荐、热门活动、快捷入口 | `GET /product/list`、`GET /activity/hot` |
| `/login` | 登录 | 登录、保存 Token、回跳 | `POST /user/login` |
| `/register` | 注册 | 表单校验、注册后跳登录 | `POST /user/register` |
| `/products` | 商品广场 | 关键词、分类、分页、进入详情 | `GET /product/list`、`GET /category/list` |
| `/products/:id` | 商品详情 | 商品信息、收藏、留言、下单入口 | `GET /product/{id}`、评论/收藏/订单接口 |
| `/activities` | 活动广场 | 关键词、分类、状态、时间范围、分页 | `GET /activity/list`、`GET /activity-category/list` |
| `/activities/:id` | 活动详情 | 活动信息、预约/候补入口、组织者信息 | `GET /activity/{id}`、预约/候补接口 |
| `/403`、`/:pathMatch(.*)*` | 错误页 | 无权限、页面不存在、返回首页 | 无 |

### 6.2 登录用户页面

| 路由 | 页面 | 主要能力 | 关联接口 |
| --- | --- | --- | --- |
| `/profile` | 个人中心 | 查看/修改资料、修改密码、退出登录 | `/user/me`、`/user/profile`、`/user/password`、`/user/logout` |
| `/favorites` | 我的收藏 | 列表、取消收藏、跳转商品 | `GET /favorite/my`、`DELETE /favorite/{productId}` |
| `/products/publish` | 发布商品 | 图片上传、分类选择、发布 | `/upload/image`、`/category/list`、`/product/publish` |
| `/products/mine` | 我的商品 | 状态筛选、编辑、下架、重新提交审核 | `GET /product/my`、`POST /product/{id}/status` |
| `/products/mine/:id/edit` | 编辑商品 | 加载私有详情、保存修改 | `GET /product/my/{id}`、`PUT /product/{id}` |
| `/orders/buy` | 我买到的 | 列表、查看详情、取消订单 | `GET /order/buy`、`GET /order/{id}`、`PUT /order/{id}/cancel` |
| `/orders/sell` | 我卖出的 | 列表、查看详情、确认或取消 | `GET /order/sell`、`PUT /order/{id}/confirm` |
| `/reservations` | 我的预约 | 已预约/历史记录、取消预约 | `GET /reservation/my`、`DELETE /reservation/{activityId}` |
| `/waitlist` | 我的候补 | 队列位置、取消候补 | `GET /waitlist/my`、`GET /waitlist/{activityId}/position` |
| `/notifications` | 我的通知 | 未读样式、标记已读、全部已读 | `/notification/my`、`/notification/unread-count`、读状态接口 |

### 6.3 角色后台页面

只有在第 4.3 节的角色信息契约明确后，才按角色动态显示以下菜单。

| 角色 | 路由 | 页面/操作 | 关联接口 |
| --- | --- | --- | --- |
| ORGANIZER | `/organizer/activities` | 我的活动、创建、编辑、提交审核 | `/activity/my`、`/activity/create`、`/activity/update`、`/activity/{id}/submit-review` |
| ORGANIZER | `/organizer/activities/:id/reservations` | 报名名单 | `GET /reservation/activity/{activityId}` |
| AUDITOR | `/auditor/activities` | 查询待审核活动、通过/驳回 | `GET /activity/list?status=1`、`POST /activity/review` |
| ADMIN | `/admin/products` | 商品审核、全量商品查看 | `/admin/product/list`、`/admin/product/{id}`、`/admin/product/{id}/review` |
| ADMIN | `/admin/orders` | 全平台订单查看 | `GET /admin/order/list` |
| ADMIN | `/admin/users` | 查询用户、封禁/解封 | `/user/list`、`/admin/user/{id}/ban`、`/admin/user/{id}/unban` |
| ADMIN | `/admin/categories` | 商品分类管理 | `/category/add`、`/category/update`、`/category/{id}` |
| ADMIN | `/admin/activity-categories` | 活动分类管理 | `/activity-category/add`、`/activity-category/update`、`/activity-category/{id}` |
| ADMIN | `/admin/activities` | 活动下架 | `GET /activity/list`、`POST /activity/{id}/off-shelf` |
| ADMIN | `/admin/operation-logs` | 操作日志查看 | `GET /admin/operation-log/list` |

## 7. 状态字典与操作按钮

前端在 `constants/status.ts` 中统一维护下面映射，并提供 `getStatusMeta(type, value)` 返回文本、Tag 类型、颜色和允许操作。

| 业务 | 状态值 | 前端文本 | 常见操作 |
| --- | --- | --- | --- |
| 商品 | 0 | 已下架 | 重新提交审核（符合后端状态机时） |
| 商品 | 1 | 在售 | 编辑、下架、被他人下单 |
| 商品 | 2 | 已锁定 | 显示交易进行中，不允许再次下单 |
| 商品 | 3 | 已售出 | 仅查看订单/历史 |
| 商品 | 4 | 待审核 | 查看、等待审核；显示审核备注（如有） |
| 订单 | 0 | 待确认 | 买卖双方可取消；卖家可确认 |
| 订单 | 1 | 已完成 | 仅查看 |
| 订单 | 2 | 已取消 | 仅查看 |
| 活动 | 0 | 草稿 | 编辑、删除、提交审核 |
| 活动 | 1 | 待审核 | 等待审核 |
| 活动 | 2 | 审核拒绝 | 显示驳回原因，可编辑后重新提交 |
| 活动 | 3 | 报名中 | 可预约；满员时可加入候补 |
| 活动 | 4 | 报名结束 | 不再展示预约/候补按钮 |
| 活动 | 5 | 进行中 | 展示进行中状态 |
| 活动 | 6 | 已结束 | 仅可查看历史 |
| 活动 | 7 | 已下架 | 不展示预约/候补按钮 |
| 预约 | 0 | 已预约 | 可取消（以后端返回为准） |
| 预约 | 1 | 已取消 | 历史记录 |
| 预约 | 2 | 已失效 | 历史记录 |
| 候补 | 0 | 候补中 | 显示队列位置、可取消 |
| 候补 | 1 | 已补位 | 提示已转为正式预约 |
| 候补 | 2 | 已取消 | 历史记录 |
| 候补 | 3 | 已失效 | 历史记录 |

规则：前端只根据状态控制展示与交互提示，**不得**尝试在前端复刻后端全部状态机判断；请求失败时必须以服务端返回结果为准并刷新数据。

## 8. 分阶段开发任务

### 阶段 0：项目骨架与联调基础（先完成）

目标：得到一个可以访问后端、能保存登录态、可复用的空壳应用。

任务清单：

- [ ] 初始化 `frontend/` Vue + TypeScript 项目，配置 ESLint/Prettier。
- [ ] 安装 Vue Router、Pinia、Axios、Element Plus、dayjs。
- [ ] 配置 `.env.development` 和 Vite 的 `/api`、`/upload` 代理。
- [ ] 建立 `api/request.ts`：`baseURL`、超时、请求拦截器、响应拦截器、错误统一提示。
- [ ] 建立 `ApiResult<T>`、`PageResult<T>`、用户/商品/活动等基础类型。
- [ ] 建立 `auth` Store：Token、用户信息、登录、登出、恢复本地状态。
- [ ] 建立路由与全局守卫：登录必需页、`redirect` 回跳、403/404 页。
- [ ] 完成顶部导航、基础布局、全局加载条、空状态组件和状态标签组件。
- [ ] 使用 `/product/list` 做第一个真实请求，验证代理、类型、分页和错误提示。

验收标准：

- 前端刷新后登录态可恢复；Token 无效时会被清除并跳转登录页。
- 浏览器 Network 中 API 请求为 `/api/...`，没有硬编码后端地址。
- 后端关闭时页面得到可理解的网络错误提示，而非未处理异常。

### 阶段 1：认证与公开浏览（先做出可展示页面）

目标：未登录状态也能完成“看见项目价值”的演示。

任务清单：

- [ ] 登录页：用户名、密码、表单校验、登录中状态、回跳。
- [ ] 注册页：校验规则以 DTO/Knife4j 为准，注册成功后跳转登录。
- [ ] 首页：热门活动、最新商品、未登录/已登录导航差异。
- [ ] 商品广场：关键词、分类、分页、加载态、空态。
- [ ] 商品详情：价格、成色、卖家、图片、状态、发布时间。
- [ ] 留言区：公开查看一级留言与回复；未登录点击“留言/回复”跳转登录。
- [ ] 活动广场：关键词、分类、状态、时间范围、分页。
- [ ] 活动详情：时间、地点、报名人数、候补人数、组织者、活动状态。
- [ ] 热门活动：调用 `/activity/hot?limit=...`，失败时静默隐藏该区块，不影响主页面。

验收标准：

- 无登录态可完整浏览公开内容。
- URL 参数、筛选状态和分页能正确驱动列表重新加载。
- 商品/活动详情 ID 不存在或接口失败时展示错误状态，而不是白屏。

### 阶段 2：商品交易闭环（普通用户核心）

目标：完成“发布 → 审核等待 → 浏览 → 下单 → 确认/取消”的演示闭环。

任务清单：

- [ ] 图片上传组件：格式/数量/大小的前端预校验、上传进度、预览、删除。
- [ ] 发布商品页：标题、描述、价格、原价、分类、成色、图片；发布后进入“我的商品”。
- [ ] 我的商品页：分页、状态 Tag、私有详情、编辑、下架、重新提交审核。
- [ ] 商品编辑页：只允许从 `/product/my/{id}` 加载私有详情，不用公开详情代替。
- [ ] 收藏按钮：先查询 `GET /favorite/{productId}/status`，再调用收藏/取消；操作成功后更新当前状态。
- [ ] 我的收藏：支持空态、取消收藏、商品已下架/已售出的状态展示。
- [ ] 商品留言：发表、删除自己的留言、回复；提交按钮防重复点击。
- [ ] 下单确认框：展示商品摘要，明确“下单后商品会锁定”；调用 `POST /order/place`。
- [ ] 买到的订单、卖出的订单：分页、订单详情、状态 Tag、取消/卖家确认按钮。

关键交互规则：

- 不允许购买自己的商品：前端可提前禁用并提示，但必须允许后端再次校验。
- 商品状态不是“在售”时，不显示下单按钮。
- 每次确认、取消、下架、删除都使用二次确认对话框；请求中禁用操作按钮。
- 写操作成功后优先重新拉取详情/列表，避免只靠本地猜测状态。

验收标准：

- 用两个本地测试账号可以演示买家下单、卖家确认、双方查看订单。
- 取消订单后列表和商品详情均更新为后端真实状态。
- 在商品详情页连续点击下单不会制造重复交互提示或页面状态错乱。

### 阶段 3：活动、预约与候补闭环

目标：完成项目最有特色的“活动报名满员后候补、取消后补位、通知”的前端体验。

任务清单：

- [ ] 活动详情根据状态显示报名按钮、候补按钮或不可操作提示。
- [ ] 点击“预约”：未登录则跳登录；登录后调用 `POST /reservation/{activityId}`。
- [ ] 预约失败且后端提示满员时，展示“加入候补”入口，不在前端自行推断名额。
- [ ] 点击“加入候补”：调用 `POST /waitlist/{activityId}`，成功后查询并展示队列位置。
- [ ] 我的预约页：展示活动时间、地点、预约状态、取消入口。
- [ ] 我的候补页：展示活动信息、候补状态、队列位置、取消候补入口。
- [ ] 通知中心：未读数量、通知列表、单条已读、全部已读；点击通知依据 `relatedId` 跳转活动或相关页面。
- [ ] 顶部通知角标：登录后首次加载未读数；进入通知中心或标记已读后刷新数字。
- [ ] 为预约、候补、取消、补位等结果设计明确的成功/失败文案，不只显示“操作成功”。

关键交互规则：

- 报名人数、候补人数和排队位置均由后端决定；前端不做“抢名额”或本地扣减逻辑。
- 候补成功后不要每秒轮询位置；用户刷新候补页、重新进入页面或完成操作时再查询即可。
- 活动详情从公开接口获取时，不假定当前用户的预约状态已包含其中；需要时以“我的预约/候补”数据或后端新增状态接口为准。

验收标准：

- 用两个或多个账号可演示预约成功、名额满后候补、取消预约、候补转正和通知已读。
- 网络抖动或重复点击时，页面最终状态会回到后端真实数据。

### 阶段 4：个人中心与组织者后台

目标：补齐用户管理自己的资源和组织者管理活动的路径。

任务清单：

- [ ] 个人资料页：读取 `/user/me`，修改昵称、电话、头像等允许字段。
- [ ] 修改密码对话框：新旧密码字段、校验、成功后按后端实际行为决定是否强制重新登录。
- [ ] 退出登录：调用 `/user/logout` 后清除本地状态、跳转首页。
- [ ] 我的活动页：按活动状态分组或筛选，突出草稿、待审核、被驳回原因。
- [ ] 创建活动页：活动封面上传、活动分类、时间区间、人数、地点、描述。
- [ ] 创建活动时严格实现第 4.5 节的幂等 Token 流程。
- [ ] 编辑活动页：仅在草稿/审核拒绝状态展示编辑与提交审核；操作失败后刷新详情。
- [ ] 报名名单页：组织者查看活动预约名单，处理“无权限/活动不存在/空名单”。

验收标准：

- 组织者能创建草稿、编辑、提交审核，并从“我的活动”看到状态变化。
- 普通用户不能通过手动修改路由正常使用组织者页面；即使绕过前端，后端也返回 403。

### 阶段 5：审核员与管理员后台

目标：按最小可用原则完成后台，不追求完整运营系统视觉效果。

任务清单：

- [ ] 审核员活动审核页：待审核列表、查看详情、通过/驳回对话框；驳回时强制填写原因。
- [ ] 管理员商品审核页：全量列表、筛选、详情、通过/驳回及备注。
- [ ] 管理员订单页：全平台订单列表与状态展示。
- [ ] 管理员用户页：查询、封禁、解封；对高风险操作进行二次确认。
- [ ] 商品分类、活动分类管理：列表、新增、编辑、删除；删除前提示可能影响关联数据，并以服务端结果为准。
- [ ] 管理员活动页：活动浏览、强制下架。
- [ ] 操作日志页：只做分页/时间/操作类型展示，不做复杂检索，除非后端已经提供相应参数。
- [ ] 后台布局：左侧菜单、顶部用户区、移动端无需优先适配。

验收标准：

- 每个角色只看到符合角色的菜单和路由。
- 403、封禁、删除失败等后端结果都有可理解提示。
- 商品审核、活动审核、封禁/解封的写操作均有确认并在成功后刷新列表。

### 阶段 6：联调收尾与学习复盘

目标：把项目从“页面能点”提升为“流程稳定、能讲清楚”。

任务清单：

- [ ] 检查所有列表页：加载、空态、错误态、分页、筛选重置。
- [ ] 检查所有表单：必填、长度、金额、日期范围、提交中、重复提交、后端错误回显。
- [ ] 检查所有写操作：确认框、按钮 loading、成功反馈、失败恢复、数据刷新。
- [ ] 检查 Token 失效、403、429、网络断开、图片加载失败、路由不存在。
- [ ] 在 `docs/` 记录实际遇到的接口差异、修复方式和学习笔记。
- [ ] 为三个核心流程录制或准备演示步骤：商品交易、活动审核、预约候补补位。
- [ ] 仅在需要时补充 Vitest：优先测状态字典、请求错误转换、路由守卫和关键组合式函数。

## 9. API 模块划分清单

创建 API 文件时按以下边界组织；方法名使用动词，路径与 HTTP 方法保持一一对应。

| API 文件 | 覆盖后端路径 |
| --- | --- |
| `api/user.ts` | `/user/register`、`/user/login`、`/user/logout`、`/user/me`、`/user/profile`、`/user/password`、`/user/list`、`/user/{id}`、`/user/add` |
| `api/product.ts` | `/product/list`、`/product/{id}`、`/product/my`、`/product/my/{id}`、`/product/publish`、`/product/{id}`（更新/删除）、`/product/{id}/status` |
| `api/order.ts` | `/order/place`、`/order/{id}`、`/order/{id}/confirm`、`/order/{id}/cancel`、`/order/buy`、`/order/sell` |
| `api/favorite.ts` | `/favorite/{productId}`、`/favorite/{productId}/status`、`/favorite/my` |
| `api/comment.ts` | `/comment/add`、`/comment/{id}`、`/comment/product/{productId}`、`/comment/{parentId}/replies`、`/comment/my` |
| `api/activity.ts` | `/activity/list`、`/activity/hot`、`/activity/{id}`、`/activity/my`、`/activity/create`、`/activity/update`、`/activity/{id}/submit-review`、`/activity/review`、`/activity/{id}/off-shelf` |
| `api/reservation.ts` | `/reservation/{activityId}`、`/reservation/my`、`/reservation/activity/{activityId}` |
| `api/waitlist.ts` | `/waitlist/{activityId}`、`/waitlist/my`、`/waitlist/{activityId}/position` |
| `api/notification.ts` | `/notification/my`、`/notification/unread-count`、`/notification/{id}/read`、`/notification/read-all` |
| `api/category.ts` | `/category/list`、`/category/{id}`、`/category/add`、`/category/update`；活动分类对应 `/activity-category/**` |
| `api/admin.ts` | `/admin/product/**`、`/admin/order/list`、`/admin/user/{id}/ban`、`/admin/user/{id}/unban`、`/admin/operation-log/list` |
| `api/upload.ts` | `/upload/image` |
| `api/idempotency.ts` | `/idempotency/token/{scene}` |

## 10. 每个页面的通用实现模板

每实现一个页面，按下面顺序完成，避免只做“静态界面”：

1. 先在 Knife4j 读接口：请求方法、参数名、是否登录、DTO 校验、返回 VO、错误码。
2. 在 `types/` 写最小必要类型，在 `api/` 写请求函数。
3. 先做真实请求、加载态、空态和错误态，再优化布局。
4. 页面使用 `ref` 保存查询参数、数据、总数、加载状态；写操作使用独立的 `submitting` 状态。
5. 对删除、取消、确认、下架、审核、封禁等不可逆或状态变化操作加确认框。
6. 成功后重新请求后端数据；不要只手改数组来“假装成功”。
7. 至少用一个正常账号和一个无权限账号手动测试。
8. 完成后在本计划的阶段清单中勾选任务，并在 Git 中提交一个小而清晰的提交。

## 11. 本地联调与验收用例

### 11.1 必跑的核心流程

| 流程 | 验收步骤 |
| --- | --- |
| 注册登录 | 注册新用户 → 登录 → 刷新页面仍保持登录 → 退出 → 访问私有页跳登录。 |
| 商品交易 | 卖家发布商品 → 管理员审核 → 买家浏览/收藏/下单 → 卖家确认或任一方取消。 |
| 留言 | 未登录查看 → 登录发表 → 回复 → 删除自己的留言。 |
| 活动审核 | 组织者创建草稿 → 编辑 → 提交审核 → 审核员通过/驳回 → 查看状态和通知。 |
| 预约候补 | 报名中活动预约 → 满员后加入候补 → 已预约用户取消 → 候补用户查看补位和通知。 |
| 权限 | 普通用户访问后台路由 → 前端拦截或后端 403；管理员可完成分类/封禁操作。 |
| 异常 | 关闭后端或使用无效 Token → 页面给出清晰提示，恢复后可继续使用。 |

### 11.2 手工检查清单

- [ ] 控制台没有未处理 Promise、Vue warning、重复 key warning。
- [ ] 每个 `v-for` 有稳定的 `:key`。
- [ ] 每个异步请求都有 `try/catch/finally` 或统一封装。
- [ ] 所有金额使用固定两位小数展示，后端数值不经字符串拼接计算。
- [ ] 所有日期通过同一工具函数格式化。
- [ ] 图片加载失败时有占位图；图片未上传时不提交空的错误地址。
- [ ] 不在前端日志、截图、提交记录中泄露数据库密码、Redis 密码或 Token。
- [ ] 使用浏览器刷新、后退、直接输入详情链接进行检查。

## 12. 建议学习节奏（可按实际时间调整）

| 周期 | 重点产出 | 不要做的事 |
| --- | --- | --- |
| 第 1 周 | 阶段 0 + 阶段 1：骨架、登录、商品/活动公开浏览 | 不要先做管理员后台或复杂首页动效。 |
| 第 2 周 | 阶段 2：发布、收藏、留言、订单闭环 | 不要跳过真实接口而长期使用假数据。 |
| 第 3 周 | 阶段 3：预约、候补、通知 | 不要在前端模拟并发或候补补位规则。 |
| 第 4 周 | 阶段 4：个人中心与组织者页面 | 先确认角色返回字段，再做动态菜单。 |
| 第 5 周 | 阶段 5：审核员和管理员后台 | 不追求运营级数据大屏。 |
| 第 6 周 | 阶段 6：联调、异常处理、演示与复盘 | 不为了“完善”而开始部署或重构后端。 |

## 13. 开始开发的第一天：按此顺序执行

1. 启动 Docker 的 MySQL、Redis、RabbitMQ，以及 Spring Boot 后端；打开 Knife4j。
2. 在根目录创建 `frontend/`，完成 Vue + TypeScript 初始化。
3. 配置 `/api`、`/upload` 代理并用 `/api/product/list` 验证。
4. 完成 `request.ts`、`auth.ts`、路由守卫、登录/注册页面。
5. 完成商品列表与商品详情，作为第一个完整“公开浏览”切片。
6. 提交代码，例如：`feat(frontend): initialize vue app and product browsing`。
7. 再按第 8 节顺序逐模块推进；任何接口不确定时，以 Knife4j 和对应 Controller/DTO 为准。

---

## 附：实现时最容易踩的坑

1. `/product/my`、`/activity/my` 是私有接口，路由优先级和登录检查不要被 `/{id}` 详情页误伤。
2. 商品和活动状态会被后端流程改变；提交成功后应重新拉取数据，不要仅在前端修改一个数字。
3. 后端分页字段来自 MyBatis-Plus，先在真实响应中确认字段再绑定 Element Plus 分页。
4. 创建活动需要一次性 `Idempotency-Token`；重复提交问题不能只靠按钮禁用解决。
5. 浏览器的图片请求也会跨端口；没有 `/upload` 代理时，图片会加载失败，即使 API 代理已成功。
6. 前端隐藏按钮不是权限控制。对 401/403 的处理必须完整，所有权限最终由后端决定。
7. 角色代码数组在登录响应中尚需确认或补充；在它明确之前，不应固化多角色菜单判断。
