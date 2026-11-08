# ADR 0005：FastAPI、PostgreSQL 与持久 worker

日期：2026-11-08；状态：accepted。

## 决策

服务端单体 API + 独立 worker，PostgreSQL/pgvector 和 S3 兼容存储；初期不用 Redis。

## 理由、替代与代价

降低系统组件数，Python 方便 AI 处理；代价是双语言契约与实际部署成本。身份提供方/区域在部署前决定。

## 验证与重开条件

T19；本地集成环境、租约与鉴权测试。

关键前提改变、验证失败或用户明确改变目标时新建 superseding ADR，保留本记录，不静默改写历史。
