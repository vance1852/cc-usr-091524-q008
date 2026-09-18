# 工业设备巡检与维保工单管理平台（纯后端）

工业设备台账、巡检与维保工单管理的纯后端 API 服务。

## 技术栈

- Java 17 + Spring Boot 3 + Spring Web
- Spring Data JPA + MySQL 8（字符集 utf8mb4）
- JWT 鉴权（jjwt，自定义过滤器）、PBKDF2 密码哈希（JDK 自带）

## 启动（Docker）

```bash
docker compose up --build
```

MySQL 就绪后，应用通过 JPA 自动建表（ddl-auto=update）并在启动时灌入种子数据，服务监听 `http://127.0.0.1:7654`。

## 内置账号

唯一管理员（本平台只有 admin 一个角色）：

- 用户名：`admin`
- 密码：`admin123`

## 已实现的基础功能

- 登录签发 JWT、获取当前用户（`/api/auth/login`、`/api/auth/me`）
- 设备台账增删改查（`/api/equipments`，编号唯一校验）
- 维保工单查询、创建、状态流转（`/api/work-orders`，完成时记录关闭时间）
- 巡检点、巡检模板与周期计划维护（`/api/inspection/points`、`/api/inspection/templates`、`/api/inspection/plans`）
- 巡检任务生成与执行、异常转工单、复检闭环和路线比较（`/api/inspection/tasks`）
- 巡检完成率、设备历史与执行轨迹查询（`/api/inspection/stats`）
- 运行计量驱动的保养（`/api/meters`，见下节）
- 仪表盘统计（`/api/dashboard/stats`）
- 健康检查（`/api/health`）

## 运行计量驱动的保养

在设备台账与 maintenance 工单之上，为设备增加“累计计量项 → 阈值 → 保养工单”的闭环：

- **计量项配置**：每台设备可配置一个或多个累计计量项（如空压机的累计运行小时 `running_hours`、启动次数 `starts`），含保养阈值 `threshold` 与提前量 `lead`。
  - `GET/POST /api/meters/definitions?equipmentId=`、`DELETE /api/meters/definitions/{id}`
- **批量读数上报**：`POST /api/meters/readings/batch`，请求体 `{"readings":[...]}`，每条以
  `equipmentId`（或 `equipmentCode`）+ `metric` + `sourceSeq`（控制器来源序号）去重；
  正常读数表显单调递增。逐条返回 `accepted` / `duplicate` / `rejected` 及拒绝原因（回退、未登记换表、设备不存在等）。
- **授权换表/计数器回绕**：`POST /api/meters/equipment/{id}/metrics/{metric}/replacements`，
  登记旧表终值、新表起点；旧表读数流水原样保留、不覆盖历史累计值，新表读数按“历史结转量 + 表显差值”续算。
- **提前提醒**：周期累计达到 `threshold - lead` 时生成一张待确认提醒
  （`GET /api/meters/reminders`、`POST /api/meters/reminders/{id}` 确认/忽略）。
- **自动工单**：越过阈值且当前没有同周期（同计量项、同周期起点）的 maintenance 工单时，
  自动创建一张工单，工单带 `meterMetric`、`cycleStartValue`、`meterTriggerValue`、`meterTriggerSource` 溯源字段；
  工单完成（`PATCH /api/work-orders/{id}/status`）时记录保养基准并关闭同周期提醒，
  之后迟到的旧读数只入库/被拒，绝不能触发下一周期；已完工单不能改回未完成。
- **剩余量与触发来源**：`GET /api/meters/equipment/{id}/status`（单设备）、
  `GET /api/meters/status`（全部设备），返回每项计量的周期起点、周期用量、剩余量、
  状态（`normal` / `due_soon` / `due` / `no_data`）以及关联提醒/工单和触发来源。

并发上报、换表、工单关闭在同一“设备+计量项”上通过数据库行锁串行化，配合来源序号唯一约束兜底；
全部判断只依赖持久化的历史累计值与保养基准，进程重启后计算结果不变。
未配置任何计量项的设备（包括种子设备）行为完全不变，`metrics` 返回空数组。

种子数据中二号空压机（EQ-1002）预置了“累计运行小时”（阈值 1000h、提前量 100h）、
两条读数与一张待确认提醒作为示范；其余种子设备无计量配置。

除 `login` 与 `health` 外，接口均需 `Authorization: Bearer <token>`。

## 编码说明

数据库使用 utf8mb4，JDBC 连接显式指定 characterEncoding=utf8；Spring Boot 的 JSON 响应默认 UTF-8，中文不乱码。
