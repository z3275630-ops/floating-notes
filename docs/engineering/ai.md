# 检索、洞察、Agent、记忆与定时任务

本文件定义真实行为与失败边界。模型是受控推理组件；可靠性来自证据、工具权限和持久状态。应用内展示已执行动作，不展示或捏造模型内部思维过程。

## AI01：共享检索管道

1. 读取认证主体和授权版本，将入口 NoteScope 与权限范围求交。
2. 对关键词查询与语义查询分别召回；中文子串/精确检索保留独立路径。
3. 初始候选最多 100 个 chunk（调参起点）；按当前权限、删除状态和版本过滤，合并同笔记相邻片段。
4. 重排后选择足够回答问题的材料，常规对话初始不超过 12 条笔记；长文按 token 预算裁剪并保留原段落边界。该数字只控制成本，不等于问题已被完整覆盖。
5. 生成 EvidenceBundle：记录候选数量、实际读取数量、总范围数量、截断/抽样、笔记 revision、片段 hash、授权版本。
6. 生成时只允许引用 bundle 内的 citation_id；输出后检查引用存在、版本和删除状态。语义支持程度通过标注数据集评估。

召回使用问题原文与必要关键词，不擅自把敏感原句送到公开网络搜索。embedding 模型在中文短句、口语、否定句和中英混合样本上比较；不同模型维度不能混库。新模型影子建索引、评测通过、原子切换，失败回旧索引。

## AI02：整体总结与认知地图

“这一年我在想什么”走集合聚合任务：固定范围和同步水位 → 计数/分批读取 → 分组摘要 → 引用原文复核 → 全局汇总。报告 eligible/processed/failed/excluded/sample 五种数量。若只读取样本，明确展示抽样比例，不使用“全部”措辞。

地图用同一授权索引，先生成聚类快照再布局。初期可比较层次聚类与 HDBSCAN/UMAP 候选，M0/M4 按中文样本、稳定性和性能决定，不锁死未经验证的算法。固定随机种子、参数版本、数据 hash；新的快照尽量匹配旧主题保持空间连续。统计数字来自数据计数，不从地图气泡面积反推。

## AI03：洞察运行合同

状态：draft → queued → retrieving → generating → verifying → completed。可进入 failed/cancelled/partial；partial 必须标清失败阶段和哪些段落未核验。用户修改材料或视角后创建新 run，不原地篡改已保存报告的证据。

自定义视角是受约束 prompt 模板，不能改工具权限。内置视角：总结要点、寻找共性、寻找例外、观点变化、提出问题、行动候选。行动候选只作为文字建议，不自动创建任务。洞察“继续聊”继承 evidence_id、问题、视角和 scope；后续扩围显示变化。

## AI04：工具注册表

| 工具 | 输入要点 | 输出要点 | 权限与副作用 |
| --- | --- | --- | --- |
| search_notes | query, scope, cursor, limit | note/片段 ID、版本、片段、覆盖状态 | read:notes；始终范围交集 |
| read_note | note_id, revision?, block_ids? | 授权正文/来源/版本 | read:notes；不能任意读取历史快照 |
| get_related | note_id, scope, strategy, limit | 相关原文及关联原因标签 | read:notes；去重与排除 |
| get_memory | conversation_id, query, limit | 已允许且未过期的记忆与来源 | read:memory；禁用即返回空 |
| create_note | document, provenance, action_id | note_id, revision, undo_ref | write:notes；明确用户意图或已授权 job |
| propose_note_patch | note_id, base_revision, patch | preview_id, diff, expires_at | 只生成提案；应用由确认票据驱动 |
| create_task | note_id?/new_document, task_fields, action_id | note/block/reminder IDs | write:tasks；提醒授权独立核对 |
| schedule_job | spec, timezone, scope, tools, budget | job_id, next_run, enabled_state | write:schedules；需明确周期执行意图 |
| fetch_user_url | url, max_bytes | 网页证据片段、抓取时间 | network:fetch；SSRF 防护；禁止发私人正文 |
| search_web | public_query, freshness? | 标题/URL/摘要/来源时间 | network:search；会话可禁用 |

工具不暴露任意 SQL、shell、脚本执行或 unrestricted HTTP。用户需要新的动作时新增经过审查的类型化工具，禁止用“万能执行”绕过契约。

## AI05：写操作与确认

“把刚才的结论存成笔记”属于明确创建授权，直接创建一次并提供撤销；不再问“是否真的保存”。“这段值得记吗”属于讨论，默认不写。AI 主动建议保存时先生成草稿卡，点击保存后执行。

覆盖已有内容、批量删除、扩大外部分享、添加超出当前授权范围的周期任务必须先显示具体影响并取得对应确认。确认票据绑定 action_id、args_hash、base_revision、scope/grant_version、过期时间。任一改变都不能复用旧确认。

AI 对已有笔记修改统一采用 patch proposal。确认时若当前 revision 不同，返回 STALE_REVISION 并重建差异；不静默应用。对纯追加可单独设计安全追加策略，但仍保持来源与撤销。

## AI06：执行状态与恢复

AgentRun：queued/running/awaiting_confirmation/cancelling/completed/partial/failed/cancelled。ToolAction：prepared/authorized/executing/committed/failed/skipped。写工具执行以数据库事务为提交边界；事件流断开不代表写失败。

每个 run_id 使用递增 event_seq。客户端重连发送 last_event_id，服务端重放已持久事件，再接实时事件。事件超过保留期返回 EVENT_CURSOR_EXPIRED，客户端读取 run snapshot，不能重新执行整个 run。

幂等键按 owner + tool + action_id 唯一；请求 hash 不同返回冲突。客户端未知结果先查 action status，不能盲目重发新 action。供应商请求超时但可能计费时记录 unknown usage，禁止无上限重试。

取消标记立即持久化，在每次读/写工具前、模型调用前后检查。已经 committed 的动作在结果中列出，可提供补偿性撤销。取消外部请求可能不停止供应商计费，界面如实说明。不允许显示“全部取消”却仍创建新任务。

## AI07：预算与成本

配置 per-run 最大输入/输出 token、工具调用次数、墙钟时长、网页请求数、音频时长、日/月费用上限。开发初始工具循环上限 8、run 超时 120 秒，长报告走单独 job 预算。数值需在 fixtures 与评测中调优，不用于宣传速度。

运行前预留估计预算；运行后结算实际 usage；未知 usage 以保守上限占额直到核对。供应商价格单版本化，货币/汇率来源及时间明确。重试产生的费用记入同 run。剩余额度不足时停止后续动作并保留已有结果。用户可以完全关闭联网和自动任务。

## AI08：记忆政策

初期记忆采用手动生成草案 + 用户采用。对话中明确“记住这个偏好”可直接生成明确来源的已确认记忆并允许撤销。自动学习只能在用户开启后进行，仍保留来源、类别、时间和修改入口。

事实、偏好、推断、临时目标分别标记；他人引语、小说、收藏文章和 AI 输出不能自动归于用户。记忆冲突并列展示“可能已改变”，新明确确认可 supersede 旧项。没有已接受记忆时直接检索原文，不能用模糊人格画像填空。

删除原文后，基于该原文且没有其他有效来源的记忆转 stale，不再注入会话；用户可改写为独立确认事实后重新采用。删除会话时说明该会话衍生的记忆候选与已采用记忆，支持同时删除。

## AI09：定时任务执行

job 明确频率、IANA zone、资料范围、允许工具、费用上限、结果去向和通知偏好。自然语言解析先呈现具体下次时间；用户明确要求建立计划时创建，歧义不能靠猜测扩大执行频率。

调度器从数据库 claim 到期任务，分配 lease 与递增 fencing token；worker 崩溃后可重领，但只有最新 token 可提交结果。run 唯一键为 job_id + schedule_version + scheduled_instant。修改计划使旧版本未开始任务失效；暂停/撤销在执行前再次检查。

同步未齐时固定已同步水位，报告覆盖截止点。通知时间与生成时间分开；免打扰内结果存好，等允许时段再通知。默认不补发多轮错过任务；最多生成一份“最近未执行”的汇总，经规则明确开启才补跑。

## AI10：质量评测

维护至少 100 个合成/经同意脱敏的中文检索问题，含相关、无答案、否定句、引语、时间范围、已删内容、注入攻击和越权请求；另有 30 个工具行为场景。固定资料快照与模型参数，人工标注来源与期望工具行为。记录召回、引用支持、拒答准确和工具副作用，不采用模型自评作为唯一通过依据。

初始硬门槛：权限越界 0、无效 citation ID 0、重复写入 0；有证据问题的引用支持率目标 ≥95%，Recall@10 目标 ≥90%，以样本集基数和人审分歧同时报告。未达到时先改善检索/提示/范围，不能删掉困难样本美化结果。统计目标属于待验证门槛。
