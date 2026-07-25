# 校园二手交易平台 (Campus Trade)

基于 Spring Boot 4.1 + Java 21 的校园二手交易平台后端，支持商品发布、分类管理、订单交易、收藏、图片上传等核心功能。采用 Redis 存储登录态，自定义注解 + 拦截器实现轻量级 RBAC 权限控制，订单模块使用事务和条件更新保证数据一致性与并发安全。

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
| Maven | — | 构建工具 |

## 核心功能

- **用户模块**：注册、登录（Redis Token）、登出、用户管理
- **权限控制**：自定义 @PublicApi / @RequireRole 注解 + 拦截器实现轻量级 RBAC
- **商品模块**：发布、编辑、删除、详情、列表（分页/搜索/筛选/排序）、上下架
- **分类模块**：分类增删改查（管理员权限）
- **订单模块**：下单、确认、取消，事务保证一致性，条件更新防并发
- **收藏模块**：收藏/取消收藏（唯一索引防重复，幂等操作）
- **图片上传**：本地磁盘存储，支持 jpg/png/gif/webp
- **Redis 缓存**：商品详情缓存，防缓存穿透（空值缓存）和缓存雪崩（随机 TTL）

## 项目架构

```
campus-trade/
├── common/                  # 公共层
│   ├── Result.java          # 统一响应包装
│   ├── ResultCode.java      # 错误码枚举
│   ├── annotation/          # 自定义注解（@PublicApi、@RequireRole）
│   ├── constant/            # 常量（RedisConstants、ProductStatus、OrderStatus）
│   └── exception/           # 全局异常处理
├── config/                  # 配置层
│   ├── LoginInterceptor     # 登录拦截器（Token 验证 + @PublicApi 放行）
│   ├── RoleInterceptor      # 角色拦截器（@RequireRole 权限校验）
│   ├── WebMvcConfig         # 拦截器注册 + 静态资源映射
│   ├── MybatisPlusConfig    # 分页插件配置
│   ├── SecurityConfig       # BCrypt 密码编码器
│   └── OpenApiConfig        # Knife4j 接口文档配置
├── controller/              # 控制层（5 个 Controller，25+ 接口）
├── dto/                     # 请求参数对象（9 个 DTO）
├── entity/                  # 数据库实体（4 个 Entity）
├── mapper/                  # 数据访问层（4 个 Mapper）
├── service/                 # 业务逻辑层（4 个接口 + 4 个实现）
├── utils/                   # 工具类（UserHolder ThreadLocal）
└── vo/                      # 响应视图对象（5 个 VO）
```

## 数据库设计

### 表结构

| 表名 | 说明 | 核心字段 |
|------|------|---------|
| user | 用户表 | id, username, password(BCrypt), nickname, phone, avatar, role(0普通/1管理员), status |
| product | 商品表 | id, title, description, price, original_price, image, category_id, seller_id, condition_level, status(0下架/1在售/2锁定/3已售), view_count |
| category | 分类表 | id, name, icon, sort, status |
| `order` | 订单表 | id, order_no, product_id, product_title/price/image(快照), buyer_id, seller_id, status(0待确认/1已确认/2已取消) |
| favorite | 收藏表 | id, user_id, product_id（联合唯一索引防重复） |

### 索引设计

| 表 | 索引 | 用途 |
|----|------|------|
| product | idx_status_createtime (status, create_time) | 商品列表查询 + 排序 |
| product | idx_categoryid_status (category_id, status) | 分类筛选 |
| product | idx_sellerid_createtime (seller_id, create_time) | 我的商品列表 |
| order | idx_buyerid_createtime (buyer_id, create_time) | 我买的订单 |
| order | idx_sellerid_createtime (seller_id, create_time) | 我卖的订单 |
| favorite | uk_user_product (user_id, product_id) | 防重复收藏 |

## 核心业务流程

### 登录认证流程

```
用户提交用户名密码
  → 后端查询用户，BCrypt 校验密码
  → 生成 UUID Token
  → 用户信息存入 Redis Hash（key: login:user:{token}，TTL 30分钟）
  → 返回 Token 给前端
  → 后续请求携带 Authorization: Bearer {token}
  → LoginInterceptor 从 Redis 取用户信息 → 存入 ThreadLocal
  → 请求结束清理 ThreadLocal（防内存泄漏）
  → 每次请求刷新 TTL（滑动过期）
```

### 订单状态流转

```
买家下单
  → 商品: ON_SALE(1) → LOCKED(2)    订单: PENDING(0)
  → 条件更新: UPDATE product SET status=2 WHERE id=? AND status=1（防并发）

卖家确认
  → 商品: LOCKED(2) → SOLD(3)       订单: PENDING(0) → CONFIRMED(1)

买家/卖家取消
  → 商品: LOCKED(2) → ON_SALE(1)    订单: PENDING(0) → CANCELED(2)
```

### 商品缓存策略（Cache-Aside）

```
读请求:
  → 查 Redis（product:detail:{id}）
  → 命中 → 直接返回（检查是否为空值标记）
  → 未命中 → 查 MySQL → 写入 Redis（TTL = 30min + 随机偏移）→ 返回
  → 商品不存在 → 缓存 "NULL" 标记（TTL 5min，防穿透）

写请求（修改/删除/上下架）:
  → 更新 MySQL → 删除 Redis 缓存
```

## 项目亮点

1. **Redis Token 登录态**：无状态登录，支持滑动过期自动续期
2. **轻量级 RBAC**：自定义 @PublicApi + @RequireRole 注解配合拦截器，无需引入完整 Spring Security
3. **ThreadLocal 用户上下文**：请求级隔离，afterCompletion 中清理防内存泄漏
4. **订单事务控制**：@Transactional 保证订单创建与商品状态修改的原子性
5. **条件更新防并发**：UPDATE ... WHERE status=ON_SALE 防止同一商品被多人下单
6. **商品快照**：订单记录下单时的标题、价格、图片，不受商品后续修改影响
7. **Redis 商品缓存**：Cache-Aside 模式 + 空值缓存防穿透 + 随机 TTL 防雪崩
8. **唯一索引防重复收藏**：数据库层面保证幂等性
9. **联合索引优化**：针对高频查询设计复合索引，消除 filesort
10. **统一响应 + 全局异常 + 错误码体系**：接口规范，前端对接友好

## 项目难点

1. **并发下单**：两个用户同时购买同一商品，通过条件更新（乐观锁思想）解决，影响行数为 0 则说明已被抢
2. **缓存一致性**：采用先更新 DB 再删缓存的 Cache-Aside 策略，极端情况下短暂不一致可接受
3. **缓存穿透**：查询不存在的商品 ID 会反复打到 MySQL，通过缓存空值标记（短 TTL）解决
4. **订单状态机**：严格校验状态流转合法性，待确认→已确认/已取消，不允许非法跳转

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
# user.sql → role.sql → product.sql → category.sql → order.sql → favorite.sql
```

2. 修改配置文件 `src/main/resources/application-dev.yaml`：

```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/campus_trade
    username: 你的用户名
    password: 你的密码
  data:
    redis:
      host: localhost
      port: 6379

upload:
  path: D:/campus-trade-uploads   # 改成你的图片存储路径
```

3. 启动项目：

```bash
mvn spring-boot:run
```

4. 访问接口文档：

```
http://localhost:8080/doc.html
```

## 接口文档

启动项目后访问 Knife4j 文档页面：

```
http://localhost:8080/doc.html
```

包含 5 个模块共 25+ 个接口的完整文档，支持在线调试。

## 后续优化方向

- [ ] 接入 Elasticsearch 实现全文搜索（替代 MySQL LIKE）
- [ ] 订单超时未确认自动取消（定时任务 → RabbitMQ 延迟队列）
- [ ] 站内消息/留言模块
- [ ] Docker 容器化部署（Dockerfile + docker-compose）
- [ ] JMeter 压测 + 性能优化记录
- [ ] 前端页面（Vue 3）
