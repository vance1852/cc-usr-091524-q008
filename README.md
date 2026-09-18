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
- 维保工单查询、创建、状态流转（`/api/work-orders`，完成时记录关闭时间并落保养基准，重启撤回基准）
- 巡检点、巡检模板与周期计划维护（`/api/inspection/points`、`/api/inspection/templates`、`/api/inspection/plans`）
- 巡检任务生成与执行、异常转工单、复检闭环和路线比较（`/api/inspection/tasks`）
- 巡检完成率、设备历史与执行轨迹查询（`/api/inspection/stats`）
- 仪表盘统计（`/api/dashboard/stats`）
- 运行计量驱动的保养：计量项配置、控制器读数批量上报、授权换表、保养提醒与自动开工单、剩余量展示（`/api/metering/*`、`/api/maintenance/reminders`）
- 健康检查（`/api/health`）

除 `login` 与 `health` 外，接口均需 `Authorization: Bearer <token>`。

## 运行计量保养

为设备配置一种或多种累计计量项（如运行小时、启动次数），设置保养阈值与提前量：

- `GET/POST /api/metering/definitions?equipmentId=`、`PUT/DELETE /api/metering/definitions/{id}`：计量项、阈值、提前量配置（每设备每计量项唯一）。
- `POST /api/metering/readings/batch`：批量接收控制器读数，逐条返回 `accepted` / `duplicate` / `rejected` 及原因。以 **设备 + 计量项 + 来源序号（sourceSeq）** 幂等去重，正常读数累计值单调递增，倒退读数被拒绝。
- `GET /api/metering/readings?equipmentId=|meterId=`：读数历史（含表盘原值 `rawValue` 与折算累计值 `cumulativeValue`）。
- `POST /api/metering/replacements`：经授权换表，登记旧表终值与新表起点并计算折算偏移；历史读数与历史累计值不被覆盖，新表读数自动折算。
- `GET /api/metering/status?equipmentId=`：展示设备距各项保养的剩余量、周期、最新读数、保养基准、提醒与未关闭工单等触发来源。
- `GET /api/maintenance/reminders`、`POST /api/maintenance/reminders/{id}/confirm`：进入提前量区间生成待确认提醒（同周期唯一），可由工程师确认。
- 读数越过阈值且当前周期没有同计量项（或设备级通用）未关闭保养工单时，自动创建一张 `maintenance` 工单（`sourceType=metering`，携带计量项与触发读数溯源）；已有未关闭工单则不重复创建。
- 保养工单完成（`PATCH /api/work-orders/{id}/status` 置 `done`）时按当前累计值落保养基准，下一周期从基准起算，迟到的旧读数不会触发下一周期；工单由 `done` 重启时基准撤回、当周期提醒恢复。
- 并发上报、换表与工单关闭通过计量项行悲观锁串行化，批量逐条独立事务提交，重启后计算结果保持一致。
- 未配置任何计量项的设备（如现有种子设备）保持原有台账与手工工单行为；种子数据为二号空压机 EQ-1002 预置了运行小时计量（阈值 3000h、提前 300h，当前 2850h，启动即有一条待确认提醒）。

批量上报示例：

```json
POST /api/metering/readings/batch
{
  "readings": [
    {"equipmentId": 2, "metric": "run_hours", "sourceSeq": "ctrl-20260918-001", "value": 3012.5, "readAt": "2026-09-18T08:00:00"}
  ]
}
```

## 编码说明

数据库使用 utf8mb4，JDBC 连接显式指定 characterEncoding=utf8；Spring Boot 的 JSON 响应默认 UTF-8，中文不乱码。
