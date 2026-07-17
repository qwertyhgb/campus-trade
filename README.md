# 🏫 Campus Trade - 校园二手交易平台

基于 Spring Boot 4 的校园二手交易平台后端服务。

## 技术栈

| 技术 | 版本 |
|------|------|
| Java | 21 |
| Spring Boot | 4.1.0 |
| MyBatis-Plus | 3.5.16 |
| MySQL | 8.x |
| Redis | 7.x |
| Knife4j (OpenAPI 3) | 4.5.0 |

## 功能模块

- **用户系统** — 注册、登录、角色管理（管理员/普通用户）
- **商品管理** — 发布、编辑、下架、多条件查询与状态控制
- **分类管理** — 商品分类维护
- **订单系统** — 下单、状态流转（待支付/已支付/已发货/已完成/已取消）
- **文件上传** — 商品图片等资源上传

## 快速启动

### 前置要求

- JDK 21+
- Maven 3.9+
- MySQL 8.0+
- Redis 7.0+

### 配置

1. 创建数据库并执行 `src/main/resources/db/` 下的 SQL 脚本
2. 修改 `application-dev.yaml` 中的数据库和 Redis 连接信息

### 运行

```bash
mvn spring-boot:run -Dspring.profiles.active=dev
```

### API 文档

启动后访问：http://localhost:8080/doc.html

## 项目结构

```
campus-trade/
├── src/main/java/com/ming/campustrade/
│   ├── common/          # 通用组件（异常、常量、注解、统一返回）
│   ├── config/          # 框架配置（拦截器、MyBatis-Plus、安全等）
│   ├── controller/      # 接口层
│   ├── dto/             # 数据传输对象
│   ├── entity/          # 数据实体
│   ├── mapper/          # MyBatis-Plus 映射接口
│   ├── service/         # 业务逻辑层
│   │   └── impl/        # 业务实现
│   ├── utils/           # 工具类
│   └── vo/              # 视图对象
├── src/main/resources/
│   ├── db/              # 数据库初始化脚本
│   └── application.yaml # 主配置
└── pom.xml
```
