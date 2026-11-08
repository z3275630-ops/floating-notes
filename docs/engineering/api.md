# 服务 API 与事件协议

状态：设计合同，尚未运行。前缀 `/v1`，JSON 字段统一 snake_case；文档中 UUID 均为字符串。客户端与服务端以 contracts 和 examples 为同源测试材料，未列出的实现细节不得影响既有语义。

## 认证与共同约定

服务端账户采用 OIDC Authorization Code + PKCE 的托管身份提供方作为默认方向，具体提供方、区域和费用在启用前决定；不自建明文密码系统。token 验证 issuer/audience/signature/expiry，owner 从 subject 映射，禁止信任请求 owner_id。refresh rotation、logout/revoke 和设备清单为上线前要求。

客户端以 bearer token 访问 TLS API。写请求使用 Idempotency-Key，需更新实体时携带 expected_revision。幂等记录至少覆盖离线重试窗口，普通创建初始 7 天；同步 op 去重由持久 op_id 保留更久，不可直接套 7 天。范围/授权变化使缓存失效。

成功响应包含 data、request_id、server_time；列表追加 next_cursor、has_more。错误包含 error.code、message、retryable、details、request_id；details 只能有安全资源 ID 与字段错误。HTTP 状态和业务码对应，不把所有错误包成 200。

## 接口清单

| 方法与路径 | 请求要点 | 响应 / 副作用 |
| --- | --- | --- |
| GET /health | 无敏感参数 | 最小可用状态，无配置泄露 |
| GET /v1/me | 认证 | 当前 profile、能力、额度摘要 |
| POST /v1/sync/push | device_id、operations[] | accepted/conflicts/rejected，逐项结果 |
| GET /v1/sync/pull | cursor、limit | 变更、tombstone、下一 cursor |
| GET /v1/sync/snapshot | snapshot_id?、cursor? | 固定水位全量页 |
| POST /v1/notes | document、provenance | 新 Note；幂等创建 |
| GET /v1/notes/{id} | revision? | 可访问版本；已删返回语义状态 |
| PATCH /v1/notes/{id} | expected_revision、patch | 新 revision 或冲突 |
| DELETE /v1/notes/{id} | expected_revision | 放回收站；返回 tombstone revision |
| POST /v1/notes/{id}/restore | expected_revision | 新 revision，关系一致 |
| GET /v1/notes/{id}/versions | cursor | 不可变历史列表 |
| POST /v1/search | query、mode、scope、cursor | 命中片段/版本/coverage |
| POST /v1/evidence | selection、scope | 固定 evidence_id 与覆盖说明 |
| POST /v1/insights | question、perspective、evidence_id、budget | run_id、insight_id |
| POST /v1/conversations | scope、memory_policy | conversation_id |
| POST /v1/conversations/{id}/runs | message、evidence_id?、budget | run_id，异步启动 |
| GET /v1/runs/{id} | 无 | 当前 snapshot、已完成动作 |
| GET /v1/runs/{id}/events | Last-Event-ID | SSE 事件，断点恢复 |
| POST /v1/runs/{id}/cancel | reason? | cancelling 或现有终态 |
| POST /v1/actions/{id}/confirm | preview_hash、base_revision、grant_version | 票据校验后执行；可能过期 |
| GET /v1/actions/{id} | 无 | 执行状态与实际结果引用 |
| POST /v1/memories/proposals | scope | 候选记忆 run |
| PATCH /v1/memories/{id} | expected_revision、state/text | 修改/采用/禁用；审计 |
| POST /v1/jobs | rule、zone、scope、tools、budget | job_id、next_run 预览 |
| PATCH /v1/jobs/{id} | expected_revision、spec/enabled | 升级 schedule_version |
| GET /v1/jobs/{id}/runs | cursor | 执行结果和错过原因 |
| POST /v1/maps | scope、strategy_version | 异步 map run |
| GET /v1/maps/{id} | viewport?、level? | 覆盖+聚合节点+稳定坐标 |
| POST /v1/uploads | mime、size、sha256 | upload_id、限定上传地址 |
| POST /v1/uploads/{id}/finalize | 校验信息 | ready attachment 或失败 |
| POST /v1/integration-grants | scope、tools、expires_at | 授权记录/一次性凭据展示 |
| DELETE /v1/integration-grants/{id} | expected_revision | 立即撤销，后续调用拒绝 |
| GET /v1/usage | period | 按模型/任务用量与预算 |

MCP transport 与协议版本在 M4 按官方规范固定，复用相同业务工具与授权服务。不得另写一套权限较弱的 MCP 查询函数。公开 API 和 MCP 的 write 都经过幂等、版本和审计。

## 错误码

| 状态 | code | 客户端动作 |
| --- | --- | --- |
| 400 | INVALID_SCOPE / INVALID_DOCUMENT | 指明无效字段，保留草稿 |
| 401 | AUTH_EXPIRED | 尝试一次受控刷新；失败转登录，仍可本地保存 |
| 403 | SCOPE_DENIED / GRANT_REVOKED | 停止重试，不返回被拒绝正文 |
| 404 | NOT_FOUND | 不透露他人对象是否存在 |
| 409 | STALE_REVISION / IDEMPOTENCY_MISMATCH | 取最新状态/差异，禁止盲写 |
| 410 | CURSOR_EXPIRED / SOURCE_DELETED | 全量重建基线或引用失效显示 |
| 413 | PAYLOAD_TOO_LARGE | 本地内容仍在；说明限制与拆分选择 |
| 422 | INSUFFICIENT_EVIDENCE | 返回真实覆盖，允许换范围 |
| 429 | RATE_LIMITED / BUDGET_EXCEEDED | 按 Retry-After 或等预算恢复；不循环重试 |
| 503 | PROVIDER_UNAVAILABLE / SERVICE_UNAVAILABLE | 有上限指数退避+随机抖动；用户可取消 |

## SSE 事件

每条事件持久化 sequence、run_id、type、occurred_at、payload。类型：run.started、retrieval.completed、tool.started、tool.completed、tool.failed、message.delta、citation.ready、confirmation.required、usage.updated、run.completed、run.failed、run.cancelled。

delta 带 message_id、offset 和 text；客户端按序去重，缺序先补 snapshot。工具完成事件包含 result_ref，不重复触发工具。客户端 UI 每 50–100ms 聚合文字更新作为起点，按实测调整。心跳无正文，连接断开不自动重建 run。

## 安全与限额

每个 endpoint 在鉴权后再做范围和资源归属校验；批量接口逐项复核，防止只验证第一项。查询和上传设置体积/并发/超时限制。分页 cursor 签名并绑定 owner 与查询条件，不能换 scope 继续旧 cursor。

模型和工具的实际费用由 UsageLedger 记录；价格未知时界面只展示用量和保守估计，不能伪造准确金额。生产与测试账号/存储桶/密钥隔离。API schema 演进必须配兼容 fixture；字段删除或语义改变提升 major 版本。
