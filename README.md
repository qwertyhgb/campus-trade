# 校园二手交易平台 (Campus Trade)

基于 Spring Boot 4.1 + Java 21 的校园二手交易平台后端，涵盖商品发布与审核、分类管理、订单交易（含超时自动取消）、商品留言、收藏、用户管理（封禁/资料修改）、管理员后台等完整业务。采用 Redis 存储登录态与商品缓存，自定义注解 + 拦截器实现轻量级 RBAC 权限控制，订单模块使用事务和条件更新保证数据一致性与并发安全。

## 技术栈

| 技术 | 版本 | 用途 |
|------|------|------|
| Java | 21 | 编程语言（最新 LTS） |
| Spring Boot | 4.1.0 | Web 框架 |
| MyBatis-Plus | 3.5.16 | ORM 框架（含分页插件） |
| MySQL | 8.x | 主数据库 |
| Redis | 7.x | 登录态存储 + 商品缓存 |
| spring-security-crypto | — | BCrypt 密码加密 |
| Knife4j | 4.5.0 | 接口文档（OpenAPI 3） |
| Lombok | — | 减少样板代码 |
| Jakarta Validation | — | 参数校验 |
| JUnit 5 + Mockito + AssertJ | — | 单元测试 |
| Maven | — | 构建工具 |

## 核心功能

- **用户模块**：注册、登录（Redis Token + 滑动过期）、登出、修改个人信息、修改密码、管理员新增用户
- **权限控制**：自定义 @PublicApi / @RequireRole 注解 + 拦截器实现轻量级 RBAC
- **商品模块**：发布（审核制）、编辑、删除、详情（Redis 缓存）、列表（分页/搜索/筛选/排序）、上下架
- **商品审核**：发布后进入待审核状态，管理员审核通过才上架，驳回可附原因
- **分类模块**：分类增删改查（管理员权限）
- **订单模块**：下单、确认、取消，事务保证一致性，条件更新防并发，超时 30 分钟自动取消
- **留言模块**：两级评论结构（顶级留言 + 平铺回复），发表/删除/按商品查询/回复列表/我的留言
- **收藏模块**：收藏/取消收藏（唯一索引防重复，幂等操作）、收藏状态查询、我的收藏列表
- **管理员后台**：商品审核、全状态商品列表、全平台订单列表、用户封禁/解封
- **图片上传**：本地磁盘存储，支持 jpg/png/gif/webp，UUID 命名防冲突
- **Redis 缓存**：商品详情 Cache-Aside 缓存，防穿透（空值缓存）+ 防雪崩（随机 TTL）+ 读写降级保护
- **定时任务**：@Scheduled 订单超时自动取消，条件更新防竞态，逐笔容错

## 项目架构

```
campus-trade/
├── common/                  # 公共层
│   ├── Result.java          # 统一响应包装（静态工厂模式）
│   ├── ResultCode.java      # 错误码枚举（按模块分段：1000用户/2000商品/3000分类/4000订单/5000收藏/6000留言）
│   ├── annotation/          # 自定义注解（@PublicApi、@RequireRole）
│   ├── constant/            # 常量（RedisConstants、ProductStatus、OrderStatus）
│   └── exception/           # 业务异常 + 全局异常处理器（@RestControllerAdvice）
├── config/                  # 配置层
│   ├── LoginInterceptor     # 登录拦截器（Token 验证 + @PublicApi 放行 + 滑动过期）
│   ├── RoleInterceptor      # 角色拦截器（@RequireRole 权限校验）
│   ├── WebMvcConfig         # 拦截器注册 + 静态资源映射
│   ├── MybatisPlusConfig    # 分页插件配置
│   ├── SecurityConfig       # BCrypt 密码编码器
│   ├── OpenApiConfig        # Knife4j 接口文档配置
│   └── OrderTimeoutTask     # 订单超时自动取消定时任务（@Scheduled）
├── controller/              # 控制层（7 个 Controller，40+ 接口）
│   ├── UserController       # 用户注册/登录/登出/资料修改/密码修改
│   ├── ProductController    # 商品发布/编辑/删除/详情/列表/上下架/我的商品
│   ├── OrderController      # 下单/确认/取消/详情/我买的/我卖的
│   ├── CategoryController   # 分类增删改查
│   ├── FavoriteController   # 收藏/取消/状态查询/我的收藏
│   ├── CommentController    # 留言发表/删除/商品留言列表/回复列表/我的留言
│   └── AdminController      # 管理员后台（商品审核/订单管理/用户封禁）
├── dto/                     # 请求参数对象（12 个 DTO）
├── entity/                  # 数据库实体（6 个 Entity）
├── mapper/                  # 数据访问层（6 个 Mapper）
├── service/                 # 业务逻辑层（6 个接口 + 6 个实现）
├── utils/                   # 工具类（UserHolder ThreadLocal）
└── vo/                      # 响应视图对象（7 个 VO）
```

## 数据库设计

### 表结构

| 表名 | 说明 | 核心字段 |
|------|------|---------|
| user | 用户表 | id, username, password(BCrypt), nickname, phone, avatar, role(0普通/1管理员), status(0禁用/1正常) |
| product | 商品表 | id, title, description, price, original_price, image, category_id, seller_id, condition_level, status(0下架/1在售/2锁定/3已售/4待审核), view_count, review_remark |
| category | 分类表 | id, name, icon, sort, status |
| `order` | 订单表 | id, order_no, product_id, product_title/price/image(快照), buyer_id, seller_id, status(0待确认/1已确认/2已取消) |
| favorite | 收藏表 | id, user_id, product_id（联合唯一索引防重复，物理删除） |
| comment | 留言表 | id, product_id, user_id, content, parent_id(顶级留言为NULL), reply_to_user_id |

### 索引设计

| 表 | 索引 | 用途 |
|----|------|------|
| product | idx_status_createtime (status, create_time) | 商品列表查询 + 排序 |
| product | idx_categoryid_status (category_id, status) | 分类筛选 |
| product | idx_sellerid_createtime (seller_id, create_time) | 我的商品列表 |
| order | idx_buyerid_createtime (buyer_id, create_time) | 我买的订单 |
| order | idx_sellerid_createtime (seller_id, create_time) | 我卖的订单 |
| order | idx_status_createtime (status, create_time) | 超时订单扫描 |
| favorite | uk_user_product (user_id, product_id) | 防重复收藏 |
| comment | idx_productid_parentid (product_id, parent_id) | 商品留言/回复查询 |
| comment | idx_userid_createtime (user_id, create_time) | 我的留言列表 |

## 核心业务流程

### 登录认证流程

```
用户提交用户名密码
  → 后端查询用户，BCrypt 校验密码
  → 校验账号状态（封禁用户拒绝登录）
  → 生成 UUID Token
  → 用户信息存入 Redis Hash（key: login:user:{token}，TTL 30分钟）
  → 返回 Token 给前端
  → 后续请求携带 Authorization: Bearer {token}
  → LoginInterceptor 从 Redis 取用户信息 → 存入 ThreadLocal
  → 请求结束清理 ThreadLocal（防内存泄漏）
  → 每次请求刷新 TTL（滑动过期）
```

### 商品发布与审核流程

```
卖家发布商品
  → 商品状态: PENDING_REVIEW(4)
  → 管理员审核通过 → 商品状态: ON_SALE(1)，前台可见
  → 管理员审核驳回 → 商品状态: OFF_SALE(0)，附驳回原因(review_remark)
```

### 订单状态流转

```
买家下单
  → 商品: ON_SALE(1) → LOCKED(2)    订单: PENDING(0)
  → 条件更新: UPDATE product SET status=2 WHERE id=? AND status=1（防并发超卖）

卖家确认
  → 商品: LOCKED(2) → SOLD(3)       订单: PENDING(0) → CONFIRMED(1)

买家/卖家取消
  → 商品: LOCKED(2) → ON_SALE(1)    订单: PENDING(0) → CANCELED(2)

超时自动取消（定时任务，每60秒扫描）
  → 超过30分钟未确认的订单自动取消
  → 条件更新: UPDATE `order` SET status=2 WHERE id=? AND status=0（防竞态）
  → 释放商品: LOCKED(2) → ON_SALE(1)
```

### 商品缓存策略（Cache-Aside）

```
读请求:
  → 查 Redis（product:detail:{id}）
  → Redis 异常 → 降级为查 MySQL（保证接口可用）
  → 命中空值标记 "NULL" → 直接返回商品不存在（防穿透）
  → 命中正常缓存 → 反序列化返回
  → 未命中 → 查 MySQL → 商品不存在则缓存 "NULL"（TTL 5min）
  → 商品存在 → 组装 VO → 写入 Redis（TTL = 30min + 随机0~9min）→ 返回

写请求（修改/删除/上下架）:
  → 更新 MySQL → 删除 Redis 缓存（try-catch 保护，删除失败不影响主流程）
```

### 留言模块（两级结构）

```
发表顶级留言: parentId = null → 直接对商品留言
发表回复: parentId = 顶级留言ID → 平铺展示在顶级留言下方
  → 强制两级：对"回复"再回复时，自动挂到根留言下（防无限嵌套）
  → 校验：父留言必须存在 + 必须属于同一商品（防跨商品挂载）
```

## 项目亮点

1. **Redis Token 登录态**：无状态登录，支持滑动过期自动续期
2. **轻量级 RBAC**：自定义 @PublicApi + @RequireRole 注解配合拦截器，无需引入完整 Spring Security
3. **ThreadLocal 用户上下文**：请求级隔离，afterCompletion 中清理防内存泄漏
4. **订单事务控制**：@Transactional 保证订单创建与商品状态修改的原子性
5. **条件更新防并发**：UPDATE ... WHERE status=ON_SALE 防止同一商品被多人下单（乐观锁思想）
6. **订单超时自动取消**：@Scheduled 定时扫描 + 条件更新防竞态 + 逐笔 try-catch 容错
7. **商品快照**：订单记录下单时的标题、价格、图片，不受商品后续修改影响
8. **Redis 商品缓存**：Cache-Aside + 空值缓存防穿透 + 随机 TTL 防雪崩 + 读写双向降级保护
9. **商品审核制**：发布后需管理员审核通过才上架，驳回附原因
10. **两级留言结构**：顶级留言 + 平铺回复，强制两级防无限嵌套，跨商品挂载校验
11. **唯一索引防重复收藏**：数据库层面保证幂等性，物理删除避免逻辑删除+唯一索引冲突
12. **联合索引优化**：针对高频查询设计复合索引，消除 filesort
13. **统一响应 + 全局异常 + 错误码体系**：接口规范，前端对接友好
14. **单元测试覆盖**：5 个 Service 层测试类，75+ 用例，Mockito + AssertJ

## 项目难点

1. **并发下单**：两个用户同时购买同一商品，通过条件更新（乐观锁思想）解决，影响行数为 0 则说明已被抢
2. **缓存一致性**：采用先更新 DB 再删缓存的 Cache-Aside 策略，极端情况下短暂不一致可接受
3. **缓存穿透**：查询不存在的商品 ID 会反复打到 MySQL，通过缓存空值标记（短 TTL）解决
4. **缓存雪崩**：大量缓存同时过期导致 MySQL 压力骤增，通过随机 TTL 偏移分散过期时间
5. **Redis 降级**：Redis 故障时读写均降级（读走 MySQL，写跳过缓存清除），保证核心业务可用
6. **订单状态机**：严格校验状态流转合法性，待确认→已确认/已取消，不允许非法跳转
7. **超时取消竞态**：定时任务查出超时订单后、取消前卖家可能刚好确认，通过条件更新（WHERE status=PENDING）避免误伤
8. **留言层级控制**：强制两级结构，对"回复"再回复时自动挂到根留言，避免无限嵌套

## 单元测试

| 测试类 | 用例数 | 覆盖内容 |
|--------|--------|---------|
| UserServiceImplTest | 15 | 注册、登录（成功/密码错/不存在/禁用）、登出、查询、管理员新增 |
| ProductServiceImplTest | 14 | 发布、编辑、删除、详情（缓存命中/穿透/回源）、状态修改、列表、我的商品 |
| OrderServiceImplTest | 14 | 下单（成功/不存在/自买/非在售/已锁定）、确认、取消、详情、买家/卖家列表 |
| CategoryServiceImplTest | 11 | 添加（成功/默认值/重名）、修改（成功/不存在/重名/跳过检查）、删除、列表、详情 |
| FavoriteServiceImplTest | 7 | 添加（成功/不存在/幂等）、取消（成功/未收藏）、状态查询、列表 |

测试策略：纯 Mockito 单元测试（不依赖 Spring 容器和数据库），通过反射注入 baseMapper，Mock 所有外部依赖，验证业务逻辑正确性。

## 本地启动

### 环境要求

- JDK 21+
- MySQL 8.x
- Redis 7.x
- Maven 3.8+

### 启动步骤

1. 创建数据库并执行 SQL 脚本：

```bash
# 在 MySQL 中创建数据库
CREATE DATABASE campus_trade DEFAULT CHARACTER SET utf8mb4;

# 依次执行 src/main/resources/db/ 下的 SQL 文件
# user.sql → product.sql → category.sql → order.sql → favorite.sql → comment.sql
```

2. 配置环境变量（或直接修改 application-dev.yaml）：

```bash
# 推荐通过环境变量注入数据库密码（避免明文提交到 Git）
set DB_USERNAME=root
set DB_PASSWORD=你的密码
```

3. 修改配置文件 `src/main/resources/application-dev.yaml`：

```yaml
spring:
  data:
    redis:
      host: localhost
      port: 6379

upload:
  path: D:/campus-trade-uploads   # 改成你的图片存储路径
```

4. 启动项目：

```bash
mvn spring-boot:run
```

5. 访问接口文档：

```
http://localhost:8080/doc.html
```

## 接口文档

启动项目后访问 Knife4j 文档页面：

```
http://localhost:8080/doc.html
```

包含 7 个模块共 40+ 个接口的完整文档，支持在线调试。

## 后续优化方向

- [ ] 接入 Elasticsearch 实现全文搜索（替代 MySQL LIKE）
- [ ] 订单超时取消升级为 RabbitMQ 延迟队列（更高精度）
- [ ] Docker 容器化部署（Dockerfile + docker-compose）
- [ ] JMeter 压测 + 性能优化记录
- [ ] 前端页面（Vue 3）
- [ ] 站内消息通知（订单状态变更、审核结果推送）
- [ ] 商品多图支持（当前为单图）
- [ ] 接口限流（pageSize 上限、频率限制）
