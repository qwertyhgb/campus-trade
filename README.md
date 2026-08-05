# 校园活动预约与候补通知平台 (Campus Trade)

基于 Java 21 + Spring Boot 4.1 的校园活动预约与候补通知平台后端，在原有「校园二手交易平台」基础上演进而来，保留了商品交易、订单、收藏、留言等模块，并新增了活动创建/审核/预约/候补/通知的完整业务闭环。

核心特色：**Spring Security 无状态 Token 认证 + 多角色 RBAC 权限模型**、**预约并发防超卖四道防线**、**候补队列自动补位**、**RabbitMQ 事件驱动异步通知（重试 + 死信 + 消费幂等）**、**Redis 滑动过期登录态与商品缓存**。

## 技术栈

| 技术 | 版本 | 用途 |
|------|------|------|
| Java | 21 | 编程语言（最新 LTS） |
| Spring Boot | 4.1.0 | Web 框架 |
| Spring Security | — | Token 认证 + 方法级授权（@EnableMethodSecurity） |
| MyBatis-Plus | 3.5.16 | ORM 框架（含分页插件） |
| MySQL | 8.x | 主数据库（含 CHECK 约束、部分唯一索引技巧） |
| Redis | 7.x | 登录态存储 + 商品详情缓存 |
| RabbitMQ | — | 事件驱动异步通知 + 订单超时延迟队列 |
| Knife4j | 4.5.0 | 接口文档（OpenAPI 3） |
| Lombok | — | 减少样板代码 |
| Jakarta Validation | — | 参数校验 |
| JUnit 5 + Mockito + AssertJ | — | 单元测试 |
| Maven | — | 构建工具 |

## 核心功能

### 一、活动预约业务（新）

- **活动模块**：创建/编辑/删除（组织者）、提交审核、管理员审核（通过/驳回附原因）、下架、公开列表（关键词/分类/状态/时间范围筛选）、公开详情、我的活动
- **活动状态机**：草稿 → 待审核 → 报名中 → 报名结束 → 进行中 → 已结束，可被驳回或下架；所有状态变更走白名单校验 `canTransition()`
- **预约模块**：预约、取消、我的预约（含历史）、组织者查看预约名单；并发防超卖（见下方亮点）
- **候补队列**：满员后加入候补（悲观锁保证排队顺序）、取消候补、我的候补、实时排队位置查询
- **自动补位**：已预约用户取消后，候补队首自动转为正式预约（事务提交后触发，防死锁）
- **站内通知**：预约成功/取消、加入候补、候补补位、审核结果、活动即将开始，共 7 类通知 + 模拟邮件/短信渠道

### 二、二手交易业务（原有）

- **用户模块**：注册、登录（Redis Token + 滑动过期）、登出、修改资料、修改密码、管理员新增用户
- **商品模块**：发布（审核制）、编辑、删除（逻辑删除）、详情（Redis 缓存）、列表（分页/搜索/筛选/排序）、上下架、我的商品
- **商品审核**：发布后进入待审核，管理员审核通过才上架，驳回附原因
- **订单模块**：下单、确认、取消、详情、我买的/我卖的；事务保证一致性 + 条件更新防并发超卖 + 商品快照
- **订单超时**：下单 30 分钟未确认自动取消（RabbitMQ 延迟队列 + 定时任务双保险）
- **分类模块**：增删改查（管理员权限）
- **收藏模块**：收藏/取消（唯一索引防重复、幂等）、状态查询、我的收藏
- **留言模块**：两级评论结构（顶级留言 + 平铺回复，强制两级防无限嵌套）
- **管理员后台**：商品审核、全状态商品列表、全平台订单列表、用户封禁/解封

### 三、平台能力

- **图片上传**：本地磁盘存储，支持 jpg/png/gif/webp，UUID 命名防冲突
- **权限控制**：Spring Security URL 规则 + @PreAuthorize 方法级授权 + Service 层归属校验（双重防越权）
- **消息队列**：6 类业务事件 + 订单超时事件，发布确认 + 手动 ack + 持久化 + TTL 重试 + 死信队列
- **消费幂等**：message_consume_record 表 event_id 唯一索引，at-least-once 投递下不产生重复通知
- **定时任务**：活动状态自动推进、活动开始前 30 分钟提醒、订单超时取消（均为 @Scheduled，每分钟）

## 项目架构

```
campus-trade/
├── common/                  # 公共层
│   ├── Result.java          # 统一响应包装
│   ├── ResultCode.java      # 错误码枚举（1000用户/2000商品/3000分类/4000订单/5000收藏/6000留言/7000审核/8000活动/9000预约/9100候补/9200通知）
│   ├── constant/            # 状态常量（ActivityStatus/ReservationStatus/WaitlistStatus/OrderStatus/ProductStatus/RedisConstants）
│   └── exception/           # 业务异常 + 全局异常处理器
├── config/                  # 配置层
│   ├── SecurityConfig       # Spring Security 过滤器链（URL 授权规则 + 401/403 JSON）
│   ├── TokenAuthenticationFilter  # Token 认证过滤器（Redis 查用户 → SecurityContext）
│   ├── SecurityAccessHandler      # 401/403 统一 JSON 响应
│   ├── RabbitMQConfig       # 交换机/队列/绑定声明（通知 + 订单超时两套资源）
│   ├── RabbitMessageConverterConfig  # Jackson JSON 消息转换器
│   ├── MybatisPlusConfig    # 分页插件
│   ├── OpenApiConfig        # Knife4j 文档
│   ├── ActivityStatusTask   # 活动状态自动推进（每分钟）
│   ├── ActivityUpcomingNotificationTask  # 活动开始前提醒（每分钟）
│   └── OrderTimeoutTask     # 订单超时扫描兜底（每分钟，与延迟队列双保险）
├── controller/              # 控制层（14 个 Controller，约 68 个接口）
│   ├── UserController       # 注册/登录/登出/资料/密码/用户管理
│   ├── ProductController    # 商品发布/编辑/删除/详情/列表/状态/我的商品
│   ├── OrderController      # 下单/确认/取消/详情/我买的/我卖的
│   ├── CategoryController   # 分类增删改查
│   ├── FavoriteController   # 收藏/取消/状态/我的收藏
│   ├── CommentController    # 留言发表/删除/列表/回复/我的留言
│   ├── AdminController      # 商品审核/商品与订单列表/封禁解封
│   ├── UploadController     # 图片上传
│   ├── ActivityController   # 活动创建/编辑/删除/审核/下架/列表/详情/我的活动
│   ├── ActivityCategoryController  # 活动分类管理
│   ├── ReservationController       # 预约/取消/我的预约/活动名单
│   ├── WaitlistController          # 候补加入/取消/我的候补/排队位置
│   └── NotificationController      # 我的通知/未读数/已读/全部已读
├── event/                   # RabbitMQ 事件对象（7 个，含 BaseNotificationEvent 父类）
├── messaging/               # 消息发布/消费（NotificationEventPublisher + 2 个消费者）
├── dto/                     # 请求参数对象（18 个 DTO）
├── entity/                  # 数据库实体（15 个 Entity）
├── mapper/                  # 数据访问层（15 个 Mapper）
├── service/                 # 业务逻辑层（接口 + 实现）
├── utils/                   # UserHolder（ThreadLocal 用户上下文）
└── vo/                      # 响应视图对象（14 个 VO）
```

## 权限体系（Spring Security + RBAC）

### 角色模型

| 角色 | 说明 | 主要权限 |
|------|------|---------|
| USER | 普通用户 | 浏览/搜索/发布商品、购买、收藏、留言、预约活动、加入候补 |
| ORGANIZER | 活动组织者 | 创建/编辑/删除/提交自己组织的活动、查看预约名单 |
| AUDITOR | 活动审核员 | 审核待审核的活动 |
| ADMIN | 系统管理员 | 活动下架、商品审核、用户封禁、分类管理、活动分类管理等全部权限 |

采用标准的 `role` + `user_role`（多对多）RBAC 模型，替代旧的 `user.role` 单字段（`role_v2.sql` 提供数据迁移，多角色用户可同时拥有多个角色）。

### 认证与授权流程

```
请求 → TokenAuthenticationFilter（认证）
  → 取 Authorization: Bearer {token}
  → 查 Redis Hash（login:user:{token}）→ 恢复用户信息
  → 封禁即时拦截（login:disabled:{userId} 标记 + status 快照兜底）
  → 刷新 TTL（滑动过期，30 分钟无操作自动过期）
  → 维护"用户 → Token 集合"反向索引（封禁时强制下线）
  → 角色编码 → ROLE_ 前缀权限列表 → 放入 SecurityContext
  → 授权规则（URL 层）→ @PreAuthorize（方法层）→ Service 归属校验（数据层）
```

三层权限校验互不替代：URL 规则拦截路径、`@PreAuthorize` 控制角色、Service 层校验数据归属（如组织者只能看自己活动的预约名单），防止越权访问。

## 数据库设计

共 13 张表：`user`、`role`、`user_role`、`product`、`category`、`favorite`、`comment`、`order`、`activity`、`activity_category`、`reservation`（含候补表）、`notification`、`message_consume_record`

| 表名 | 说明 | 核心字段 |
|------|------|---------|
| user | 用户表 | username, password(BCrypt), nickname, phone, avatar, status(0禁用/1正常), role(兼容旧字段) |
| role | 角色表 | role_code(USER/ORGANIZER/AUDITOR/ADMIN), role_name |
| user_role | 用户-角色关联 | user_id, role_id（联合唯一索引，物理删除） |
| product | 商品表 | title, description, price, original_price, image, category_id, seller_id, condition_level, status(0下架/1��售/2锁定/3已售/4待审核), view_count, review_remark |
| category | 商品分类表 | name, icon, sort, status |
| favorite | 收藏表 | user_id, product_id（联合唯一索引防重复，物理删除） |
| comment | 留言表 | product_id, user_id, content, parent_id(顶级为NULL), reply_to_user_id（逻辑删除） |
| `order` | 订单表 | order_no, product_id, product_title/price/image(快照), buyer_id, seller_id, status(0待确认/1已确认/2已取消) |
| activity | 活动表 | title, description, location, cover_image, category_id, start/end_time, enroll_start/end_time, max_count, current_count, status(0草稿~7已下架), organizer_id, reviewer_id, reject_reason（逻辑删除） |
| activity_category | 活动分类表 | name, icon, sort, status |
| reservation | 预约表 | user_id, activity_id, status(0已预约/1已取消/2已失效), active_mark(1有效/NULL无效) |
| waiting_list | 候补表 | user_id, activity_id, position(排队位置), status(0候补中/1已补位/2已取消/3已失效), active_mark |
| notification | 通知表 | user_id, type(1~7), title, content, related_id, is_read（无 deleted） |
| message_consume_record | 消息消费记录 | event_id(唯一索引), queue_name, consume_status, error_msg |

### 关键设计

- **部分唯一索引技巧**：MySQL 不支持部分索引，但唯一索引允许多个 NULL。`reservation` 表用 `uk_user_activity_active (user_id, activity_id, active_mark)` 实现"同一用户对同一活动只有一条有效预约"——有效记录 `active_mark=1` 受唯一约束，取消后的历史记录 `active_mark=NULL` 不受约束。预约/候补表**不设 deleted 字段**，用 `status + active_mark` 表达有效性，保留完整历史供统计审计。
- **联合索引设计**：针对高频查询设计复合索引（如 `idx_activity_status (activity_id, status, create_time)`、`idx_user_create (user_id, create_time)`），消除 filesort；每个 SQL 脚本内均附最左前缀命中说明。
- **CHECK 约束**：`reservation`/`waiting_list` 表用 CHECK 保证 `status` 与 `active_mark` 的一致性（MySQL 8.0.16+）。

## 核心业务流程

### 预约并发防超卖（四道防线）

```
用户预约活动
  → ① 业务校验：活动存在 / 状态=报名中 / 在报名时间内 / 不能自约 / 分页参数合法
  → ② 代码层查重：查"是否已有有效预约"（拦住大多数串行重复请求）
  → ③ 条件更新抢名额（并发核心）:
       UPDATE activity SET current_count = current_count + 1
       WHERE id = ? AND current_count < max_count AND status = 3
     —— MySQL 行锁 + WHERE 条件保证并发下只有 1 人成功（防超卖）
  → ④ 唯一索引兜底：两个请求同时通过查重又都抢到名额时，
     第二个 INSERT 触发 uk_user_activity_active 冲突 → 事务整体回滚，名额自动还原
  → 事务提交后发送"预约成功"通知事件（afterCommit，回滚则不发）
```

### 候补补位流程

```
已预约用户取消预约（事务 A）
  → 条件更新预约记录：status=0→1, active_mark=1→NULL（释放唯一约束）
  → 条件更新活动：current_count - 1（gt 0 兜底防负数）
  → 注册 afterCommit 回调（避免死锁：外层事务 A 还持有活动行锁，不能直接开补位事务）
  → 事务 A 提交、行锁释放后，调用 promoteNext()（REQUIRES_NEW 独立事务）
  → 悲观锁锁定活动行 → 查出候补队首（position 最小）→ 补位为正式预约，名额 +1
  → 发送"候补补位成功"通知
```

### RabbitMQ 消息架构

```
6 类站内通知事件共用一个主队列：
  notification.exchange (Direct) → notification.queue
  路由键：reservation.created / reservation.canceled / waitlist.joined
         / waitlist.promoted / activity.reviewed / activity.upcoming

可靠性保障：
  · 发布确认（publisher-confirm correlated）+ 发布返回（publisher-returns）
  · 消息持久化（Exchange + Queue + Message 三持久化）
  · 手动 ack（acknowledge-mode: manual）+ prefetch 10
  · 临时失败 → 重试交换机 → TTL 重试队列（5/10/20 秒指数退避，最多 3 次）
    → 仍失败 → 死信队列（供人工排查，不无限占用主队列）
  · 消费幂等：消费者先 INSERT message_consume_record（event_id 唯一索引），
    重复消息触发唯一键冲突 → 跳过，保证 at-least-once 下不重复生成通知

订单超时（延迟队列 + 定时任务双保险）：
  下单 → 消息进入 order.timeout.delay.queue（TTL 30 分钟）
       → TTL 到期经死信转发到 order.timeout.queue → 消费者执行取消
  兜底：OrderTimeoutTask 每分钟扫描超时订单（条件更新防竞态）
```

### 活动状态机

```
草稿(0) ──提交审核──▶ 待审核(1) ──审核通过──▶ 报名中(3) ──报名截止──▶ 报名结束(4) ──活动开始──▶ 进行中(5) ──活动结束──▶ 已结束(6)
  │                    │                        │                  │
  └──────────────┐     └──审核拒绝──▶ 审核拒绝(2) └──下架──▶ 已下架(7)  └──下架──▶ 已下架(7)
                 │                          │
                 └──管理员下架───────────────┘
```

所有状态变更（用户操作、定时任务推进）必须先通过 `ActivityStatus.canTransition(from, to)` 白名单校验，防止非法跳转。

### 商品缓存策略（Cache-Aside）

```
读：查 Redis（product:detail:{id}）→ 命中返回
    → 未命中查 MySQL → 存在则写缓存（TTL = 30min + 随机0~9min 防雪崩）
    → 不存在则缓存空值 "NULL"（TTL 5min 防穿透）
    → Redis 异常时降级为直接查 MySQL（保证接口可用）
写：更新 MySQL → 删除缓存（try-catch 保护，删除失败不影响主流程）
```

## 项目亮点

1. **Spring Security 无状态 Token 认证**：Bearer Token + Redis Hash，支持滑动过期自动续期、封禁即时拦截、Token 反向索引强制下线
2. **完整 RBAC 权限模型**：`user_role` 多对多 + URL 规则 + `@PreAuthorize` + Service 归属校验三层防护
3. **预约防超卖四道防线**：业务校验 → 代码查重 → 条件更新抢名额 → 唯一索引兜底，层层设防
4. **部分唯一索引技巧**：`active_mark` NULL 技巧实现"部分唯一约束"，兼顾唯一性与历史记录保留
5. **候补自动补位**：afterCommit + REQUIRES_NEW 组合，避免"外层事务持有行锁 + 内层事务等待行锁"的死锁
6. **RabbitMQ 消息可靠性**：TTL 重试队列指数退避 + 死信队列 + 手动确认 + 消费幂等，事件驱动异步解耦
7. **订单超时双保险**：TTL 延迟队列（精确到消息级）+ 定时任务扫描兜底（条件更新防竞态）
8. **条件更新防并发**：预约、订单、补位全部使用 `UPDATE ... WHERE 状态条件` 的原子操作，乐观锁思想贯穿
9. **Redis 商品缓存**：Cache-Aside + 空值缓存防穿透 + 随机 TTL 防雪崩 + 读写双向降级保护
10. **活动状态机白名单**：所有状态变更统一校验，定时任务也不绕过
11. **N+1 优化**：列表页批量 selectByIds + 内存 Map 组装，避免循环查库
12. **统一响应 + 全局异常 + 模块化错误码**：错误码按业务分段（8000 活动 / 9000 预约 / 9100 候补 / 9200 通知）

## 项目难点

1. **并发预约防超卖**：同一活动仅剩 1 个名额、100 人同时预约，通过条件更新 + 行锁保证仅 1 人成功，唯一索引兜底并发窗口
2. **候补排队顺序**：加入候补需要"读 MAX(位置) → 算新位置 → 插入"三步，条件更新无法解决，改用 `SELECT ... FOR UPDATE` 悲观锁串行化
3. **补位死锁规避**：取消预约事务还持有活动行锁时不能直接补位，通过 afterCommit 延迟到锁释放后再用 REQUIRES_NEW 独立事务补位
4. **消息重复消费**：RabbitMQ at-least-once 投递，用 `message_consume_record` 唯一索引实现消费幂等
5. **消息丢失**：三持久化 + 发布确认 + 手动 ack，临时故障走 TTL 重试，最终失败进死信队列
6. **状态机一致性**：活动/预约/候补多状态机联动（活动结束 → 候补失效），定时任务批量清理
7. **缓存一致性**：Cache-Aside 先更新 DB 再删缓存，配合防穿透/防雪崩/降级保护
8. **越权访问**：接口层只校验角色，Service 层必须二次校验数据归属（组织者查名单、用户查通知）

## 单元测试

| 测试类 | 覆盖内容 |
|--------|---------|
| UserServiceImplTest | 注册、登录（成功/密码错/不存在/禁用）、登出、查询、管理员新增 |
| ProductServiceImplTest | 发布、编辑、删除、详情（缓存命中/穿透/回源）、状态修改、列表、我的商品 |
| OrderServiceImplTest | 下单（成功/不存在/自买/非在售/已锁定）、确认、取消、详情、买家/卖家列表 |
| CategoryServiceImplTest | 添加（成功/默认值/重名）、修改、删除、列表、详情 |
| FavoriteServiceImplTest | 添加（成功/不存在/幂等）、取消（成功/未收藏）、状态查询、列表 |

测试策略：纯 Mockito 单元测试（不依赖 Spring 容器和数据库），通过反射注入 baseMapper，Mock 外部依赖。活动/预约/候补模块的测试待补充。

## 本地启动

### 环境要求

- JDK 21+
- MySQL 8.x
- Redis 7.x
- RabbitMQ（本地推荐 Docker：`docker run -d --name campus-rabbitmq -p 5672:5672 -p 15672:15672 rabbitmq:3-management`）
- Maven 3.8+

### 启动步骤

1. 创建数据库并执行 SQL 脚本（注意执行顺序）：

```bash
CREATE DATABASE campus_trade DEFAULT CHARACTER SET utf8mb4;
# 依次执行 src/main/resources/db/ 下的脚本：
# user.sql → role_v2.sql → category.sql → product.sql → order.sql
# → favorite.sql → comment.sql → activity_category.sql → activity.sql
# → reservation.sql → notification.sql → message_consume_record.sql
# （脚本均可重复执行，已内置 IF NOT EXISTS 与幂等迁移）
```

2. 配置环境变量（避免明文密码提交到 Git）：

```bash
set DB_USERNAME=root
set DB_PASSWORD=你的密码
set RABBITMQ_USERNAME=campus_admin
set RABBITMQ_PASSWORD=你的RabbitMQ密码
```

3. 修改 `src/main/resources/application-dev.yaml`：

```yaml
spring:
  data:
    redis:
      host: localhost
      port: 6379
  rabbitmq:
    host: localhost
    port: 5672
    username: ${RABBITMQ_USERNAME:campus_admin}
    password: ${RABBITMQ_PASSWORD:ChangeMe_Rabbit_2026!}
    virtual-host: campus_trade

upload:
  path: D:/campus-trade-uploads   # 改成你的图片存储路径
```

> 注意：RabbitMQ 需先创建 `campus_trade` virtual-host 及 `campus_admin` 用户并授权，或直接修改上述配置使用默认 vhost。

4. 启动项目：

```bash
mvn spring-boot:run
```

5. 访问接口文档：

```
http://localhost:8080/doc.html
```

## 接口文档

启动项目后访问 Knife4j 文档页面：`http://localhost:8080/doc.html`（Swagger UI：`/swagger-ui.html`）

包含 14 个 Controller 共约 68 个接口的完整文档，支持在线调试。接口按业务模块分组：用户、商品、分类、订单、收藏、留言、管理后台、上传、活动、活动分类、预约、候补、通知。

## 后续优化方向

- [ ] 活动/预约/候补模块单元测试（当前仅覆盖旧交易模块）
- [ ] Testcontainers 集成测试（真实 MySQL/Redis/RabbitMQ 验证并发与幂等）
- [ ] 前端页面（Vue 3）
- [ ] Docker 容器化部署（Dockerfile + docker-compose，MySQL/Redis/RabbitMQ/应用）
- [ ] GitHub Actions CI（mvn -B clean test）
- [ ] 活动列表接入缓存（当前仅商品详情有缓存）
- [ ] 接口限流与防重复提交（Redis 幂等 Token）
- [ ] 活动热度排行（Redis ZSet）
- [ ] JMeter 压测 + 性能优化记录
