# 国产大模型近期动态复核报告

日期：2026-06-03  
信息截至：2026-06-03 公开可访问资料  
检索口径：优先覆盖 2026-04-01 至 2026-06-03；若模型是主流厂商当前代表版本、但公开时间稍早，则作为背景纳入并标注时间。  
报告用途：给老板做产品与模型选型判断，不作为 benchmark 最终排名。

## 一句话结论

**事实**：最近 60 天国产大模型的重心已经从“聊天模型发布”转向 **Agent、代码、长上下文、多模态、低成本 API、开放权重**。  
**判断**：Bryan 近期不应该押单一模型，而应该做一个轻量模型路由：默认用 Qwen / DeepSeek 做通用与成本底座，用 Kimi / GLM / MiniMax / StepFun 做 Agent 与代码任务，用 ERNIE / 讯飞做中文教育或行业知识任务，用 Doubao 做内容生产和电商营销类任务。

## 给老板的 8 条关键结论

1. **第一优先关注 Qwen、DeepSeek、Kimi、GLM**：这四家最接近 Bryan 当前产品需求，即 AI Agent、代码协作、长上下文、中文任务。
2. **DeepSeek V4 是 4 月下旬最重要的新动态之一**：官方发布 V4 Preview，主打 1M 上下文，并明确旧模型名将于 2026-07-24 停用，生产系统必须关注迁移。
3. **Qwen 的优势不是单点模型，而是生态**：Qwen3.6 开放权重、Qwen Code 多端 Agent、Qwen Agent 框架组合起来，适合做开发者工具和产品原型底座。
4. **Kimi K2.6 值得用于长文档、多模态 Agent 与复杂代码任务**：官方文档显示其支持文本、图像、视频输入、256K 上下文与 thinking / non-thinking 模式。
5. **GLM-5.1、MiniMax M2.7、StepFun Step 3.7 Flash 都在抢“长程 Agent / coding agent”**：这说明国产模型竞争已经进入任务执行能力，而不是只比聊天质量。
6. **ERNIE、讯飞星火、百川更适合行业场景**：ERNIE 偏搜索/知识/中文表达；讯飞偏教育、语音与国产算力；百川 M3 偏医疗。不要把它们当通用默认模型。
7. **Doubao / 火山引擎的产品化和价格信号很强**：Doubao-Seed-2.0-lite 已进入全模态理解与企业批量部署方向，适合电商内容、客服、营销工具验证。
8. **真正的产品机会不是“模型资讯站”**：应落到三个闭环：英语学习 App 的每日学习记忆闭环、电商 AI 工具的素材到上架闭环、AI 一人公司的模型路由和自动验收闭环。

## 近期重大动态 Top 10

| 时间 | 厂商/模型 | 已核实事实 | 产品含义 |
| --- | --- | --- | --- |
| 2026-05-29 | StepFun Step 3.7 Flash | 官方博客发布 Step 3.7 Flash；NVIDIA 技术博客称其为 198B 参数 MoE 视觉语言模型，支持图像/视频输入和 256K 上下文。 | 新增一个国产开放权重、多模态、Agent/coding 候选。 |
| 2026-05-26 | MiniMax M2 系列 / M2.7 | MiniMax 在 arXiv 提交 M2 系列技术报告；M2.7 被描述为面向 agentic coding、deep search、office-task、reasoning 的最新 checkpoint。 | 适合评估软件工程、Office 自动化、电商内容工作流。 |
| 2026-05-25 前后 | Kimi K2.6 | Kimi API 文档显示 K2 系列旧模型于 2026-05-25 停止维护，推荐使用 Kimi K2.6；K2.6 支持视觉/文本输入、thinking/non-thinking、Agent 任务、256K 上下文。 | 需要从旧 Kimi 模型迁移到 K2.6；适合长文档、多模态 Agent。 |
| 2026-05-09 | 百度 ERNIE 5.1 | 百度官方发布 ERNIE 5.1，称在继承 ERNIE 5.0 预训练基础上压缩参数和训练成本，并面向 autonomous decision-making agents 做强化学习基础设施。 | 适合中文知识、搜索增强、写作、企业办公，但需实测官方 benchmark。 |
| 2026-05-07 | Doubao-Seed-2.0-lite | 火山引擎开发者社区称 Doubao-Seed-2.0-lite 升级为豆包家族首款全模态理解模型，支持视频、图像、音频、文本统一理解，Agent、Coding、GUI 能力同步升级。 | 电商内容、客服质检、销售培训、视频理解值得优先试。 |
| 2026-04-24 | DeepSeek V4 Preview | DeepSeek 官方发布 V4 Preview；中文版说明可在 chat.deepseek.com / 官方 App 使用，强调 1M 上下文。API changelog 也给出旧模型停用时间。 | 适合高性价比长上下文、代码、推理；生产侧要做模型名迁移。 |
| 2026-04-23 | 腾讯混元 Hy3 preview | 腾讯官方发布 Hy3 preview，强调复杂推理、长文理解、指令遵循、工具调用、Agent 能力；GitHub 显示 2026-04-23 开放 Hy3 preview 权重。 | 腾讯云生态、企业 Agent、低价 API 可观察，但许可和稳定性需测。 |
| 2026-04-22 / 04-16 | Qwen3.6 | QwenLM GitHub 显示 Qwen3.6-27B 于 2026-04-22、Qwen3.6-35B-A3B 于 2026-04-16 发布到 Hugging Face / ModelScope；强调 Agentic Coding、前端工作流、仓库级推理。 | 默认国产开放权重候选；适合前端生成、仓库理解、开发工具。 |
| 2026-04-29 | 讯飞星火 X2-Flash | 科技日报/C114 等报道星火 X2-Flash 30B 发布并开放 API，称 token 消耗约为主流大尺寸模型的 1/3；讯飞官方同期在教育场景发布教师超级智能体“星光”。 | 英语学习/教育 App 可重点看讯飞，但 X2-Flash 一手模型页仍需补核。 |
| 2026-02-06 / 02-09 | 百川 Baichuan-M3 | Baichuan-M3 论文与 Hugging Face 模型卡显示其是医疗增强模型，公开模型权重，Hugging Face 页面 2026-02-09 更新。 | 垂直医疗能力强，但 Bryan 当前产品不应优先依赖医疗模型。 |

## 分层推荐

| 层级 | 模型/厂商 | 结论 |
| --- | --- | --- |
| 第一梯队：优先实测 | Qwen、DeepSeek、Kimi、GLM | 最贴合 Bryan 的 Agent、英语学习、电商工具和一人公司自动化需求。 |
| 第二梯队：按场景实测 | MiniMax、StepFun、Doubao、腾讯混元 | 在 coding agent、多模态、营销内容、企业低价 API 上有价值，但需压测稳定性和许可。 |
| 行业/垂直梯队 | ERNIE、讯飞星火、百川 | 中文知识、教育、语音、医疗等行业场景强；不建议作为通用默认模型。 |
| 暂不优先 | 华为盘古、百川通用模型旧线 | 近期未看到足够清晰的通用前沿公开动态；适合行业云/国产算力生态观察。 |

## 各模型复核与产品启发

### 1. Qwen / 阿里通义

- **最新进展（事实）**：QwenLM 官方 GitHub 显示 Qwen3.6-35B-A3B 于 2026-04-16 发布，Qwen3.6-27B 于 2026-04-22 发布；Qwen3.6 强调 Agentic Coding、前端工作流、仓库级推理、Thinking Preservation。Qwen Code 2026-05-28 周报继续强化 parallel agent panel、auto-memory、worktree 等开发者 Agent 能力。
- **核心能力（事实+判断）**：开放权重生态、开发者工具、前端/代码生成、仓库理解、Agent 框架最完整。
- **适合场景**：默认国产基座、代码 Agent、前端生成、低成本原型、私有化候选。
- **风险/短板**：闭源旗舰、API 版本、开源版本能力之间差异大；不能用小模型体验直接推断旗舰表现。
- **对 Bryan 的启发**：AI 一人公司优先用 Qwen 做“默认国产模型 + 开放权重备份”；电商工具可用 Qwen 做商品资料结构化、标题/详情页初稿。

### 2. DeepSeek

- **最新进展（事实）**：DeepSeek 官方 2026-04-24 发布 V4 Preview；API changelog 显示新增 `deepseek-v4-pro`、`deepseek-v4-flash`，并说明旧模型名 `deepseek-chat`、`deepseek-reasoner` 将于 2026-07-24 停用。
- **核心能力**：长上下文、代码、推理、性价比；V4 中文版说明强调 1M 超长上下文。
- **适合场景**：长文档 RAG、代码审查、数学/逻辑任务、低成本高频 API。
- **风险/短板**：版本迁移节奏快；敏感议题合规限制明显；生产环境需固定模型名并做回归测试。
- **对 Bryan 的启发**：适合作为 Think & Act 的计划生成/任务拆解备选模型，也适合电商工具批量生成低成本文案，但必须有人工确认和失败 fallback。

### 3. Kimi / Moonshot

- **最新进展（事实）**：Kimi API 文档显示 `kimi-k2.6` 为当前最新模型；旧 K2 系列 2026-05-25 停止维护。K2.6 文档称其支持文本、图片、视频输入，支持 thinking / non-thinking，支持 256K 上下文和 Agent 任务。
- **核心能力**：长文档、多模态理解、复杂代码任务、长程 Agent。
- **适合场景**：合同/论文/长网页摘要、视频/图片理解、复杂代码改造、学习 App 的长期学习档案。
- **风险/短板**：256K 上下文低于 DeepSeek/Qwen 1M 阵营；官方 benchmark 仍需用真实任务复核；thinking 参数和工具调用有兼容约束。
- **对 Bryan 的启发**：英语学习 App 可用 Kimi 做“长期记忆 + 个性化复盘”；电商工具可用 Kimi 处理商品图片/视频脚本理解。

### 4. GLM / 智谱 Z.ai

- **最新进展（事实）**：Z.ai release notes 显示 GLM-5.1 面向 long-horizon tasks，可支持从规划、执行、迭代到交付的长程任务；同时提到 GLM-4.6V 为多模态模型新版本。
- **核心能力**：长程 Agent、自动规划、代码/终端任务、工具调用。
- **适合场景**：长链路自动化、软件工程、内部运营 Agent、自动测试修复。
- **风险/短板**：官方“可独立工作数小时”属于能力方向，实际产品可靠性需要端到端任务集验证；社区生态声量小于 Qwen/DeepSeek。
- **对 Bryan 的启发**：适合做“自动执行型 Agent”的对照组，比如一人公司日常运营任务、代码改动任务、资料整理任务。

### 5. ERNIE / 百度文心

- **最新进展（事实）**：百度 2026-05-09 官方发布 ERNIE 5.1，称其在 ERNIE 5.0 基础上压缩参数与预训练成本，并面向 autonomous decision-making agents 建设新的异步强化学习基础设施。
- **核心能力**：中文知识、搜索增强、写作、企业办公与表格/Agent 类任务。
- **适合场景**：中文内容生成、知识问答、企业内部知识库、搜索增强问答。
- **风险/短板**：开放权重和开发者社区不如 Qwen/DeepSeek；官方指标需要用真实任务复核。
- **对 Bryan 的启发**：英语学习 App 里可用 ERNIE 做中文解释、语义纠错说明；电商工具可用其做中文品牌文案和知识型 FAQ。

### 6. MiniMax

- **最新进展（事实）**：MiniMax 官方页面介绍 M2.7，强调复杂 Agent harness、软件工程、Office、工具脚手架；MiniMax 2026-05-26 arXiv 技术报告称 M2 系列面向 agentic coding、agentic cowork、deep search、office-task 和 reasoning。
- **核心能力**：复杂软件工程、Office 自动化、Agent 工具适配、交互娱乐。
- **适合场景**：电商运营表格/报告、PPT/文档自动化、代码任务、长链路办公 Agent。
- **风险/短板**：开放权重许可和商业使用需单独审；官方演示不等于生产稳定性。
- **对 Bryan 的启发**：电商 AI 工具可以用 MiniMax 做“商品资料 → Excel/文案/PPT/客服 FAQ”的办公流实验。

### 7. StepFun / 阶跃星辰

- **最新进展（事实）**：StepFun 官方 2026-05-29 发布 Step 3.7 Flash；NVIDIA 2026-05-28 技术博客称 Step 3.7 Flash 是 198B 参数 MoE 视觉语言模型，面向企业工作流，支持图像/视频输入和 256K 上下文。Step 3.5 Flash 已在 Hugging Face 以 Apache 2.0 发布。
- **核心能力**：开放权重、视觉语言、多步推理、coding agent、较低激活参数。
- **适合场景**：本地/私有化实验、多模态电商分析、代码 Agent。
- **风险/短板**：新模型刚发布，真实稳定性、量化部署、工具生态仍需测试。
- **对 Bryan 的启发**：如果后续要做本地运行或低成本私有化，StepFun 值得加入模型池，但现在不应作为默认线上模型。

### 8. Doubao / 字节火山引擎

- **最新进展（事实）**：火山引擎开发者社区 2026-05-07 称 Doubao-Seed-2.0-lite 升级为豆包家族首款全模态理解模型，支持视频、图像、音频、文本统一理解，并同步升级 Agent、Coding、GUI 能力。火山引擎产品页显示 Doubao-Seed-2.0-pro/lite/mini 的按量定价与缓存价格。
- **核心能力**：产品化、低价 API、多模态理解、内容生产、客服/销售。
- **适合场景**：电商营销素材、客服质检、销售培训、短视频脚本/素材理解。
- **风险/短板**：官方产品页更偏商业接入，公开技术报告细节少；部分新闻稿来自开发者社区文章，重要能力仍需实测。
- **对 Bryan 的启发**：电商 AI 工具优先试 Doubao 的内容生产和多模态输入，不建议用它做高风险推理决策。

### 9. 腾讯混元 Hy3

- **最新进展（事实）**：腾讯 2026-04 发布 Hy3 preview，称强化复杂推理、长文理解、指令遵循、工具调用等实用能力；GitHub 显示 Hy3 preview 权重于 2026-04-23 开放到 Hugging Face、ModelScope、GitCode。
- **核心能力**：企业级 Agent、低价 API、开源权重、腾讯云生态。
- **适合场景**：腾讯云/企业客户、内部 Agent、代码与长文任务。
- **风险/短板**：preview 状态；许可、稳定性、实际吞吐需要压测。
- **对 Bryan 的启发**：作为备选供应商观察即可；除非业务接腾讯云，否则优先级低于 Qwen/DeepSeek/Kimi。

### 10. 讯飞星火

- **最新进展（事实）**：讯飞智慧教育 2026-04-24 发布教师超级智能体“星光”；科技日报 2026-05-13 报道称科大讯飞与中国移动发布“灵犀·星火智盒”，并提到 2026 年 2 月星火 X2、4 月 X2-Flash 30B。X2-Flash 的一手模型技术页本轮未找到，标注为**待补一手来源**。
- **核心能力**：教育、语音、国产算力、行业方案。
- **适合场景**：英语学习、教育内容、听说训练、课堂/教师工具。
- **风险/短板**：通用前沿模型公开资料不如 Qwen/DeepSeek/Kimi 完整；部分最新模型信息依赖媒体报道。
- **对 Bryan 的启发**：英语学习 App 可以重点看讯飞的语音、教育应用和国产部署，但通用推理不建议默认用讯飞。

### 11. 百川 Baichuan

- **最新进展（事实）**：Baichuan-M3 技术报告 2026-02-06 提交；Hugging Face 上 Baichuan-M3-235B 及量化版本 2026-02-09 更新；官方博客强调其医疗问诊、临床推理与可靠医疗决策能力。
- **核心能力**：医疗垂直、主动问诊、临床推理。
- **适合场景**：医疗问答研究、健康咨询辅助、医疗知识库。
- **风险/短板**：医疗高风险领域必须有合规、医生审核和免责声明；不适合作为 Bryan 当前通用产品底座。
- **对 Bryan 的启发**：除非以后做健康/医疗方向，否则只作为“垂直模型如何做问诊流程”的参考。

## 对 Bryan 三类产品的建议

### A. AI 一人公司

**推荐模型池**：

1. 默认通用：Qwen / DeepSeek
2. 长文档与复杂 Agent：Kimi / GLM
3. 办公与代码工具：MiniMax / StepFun
4. 低价内容与多模态：Doubao

**产品策略**：

- 先做模型路由，不做模型信仰。
- 每个任务记录：输入、模型、成本、耗时、失败原因、人工修正。
- 建立小型真实任务集，比如“写 PRD、改代码、整理邮件、生成商品文案、生成学习复习卡”。

### B. 英语学习 App

**推荐方向**：

- Kimi：长期学习档案、作文/口语多轮复盘。
- Qwen / DeepSeek：低成本生成练习、错题解释、句型变体。
- ERNIE / 讯飞：中文解释、教育场景、语音听说能力。

**不要做**：

- 不要只做“AI 老师聊天”。
- 不要把模型能力当产品壁垒。

**应该做**：

- 每日输入 5 分钟。
- 自动纠错。
- 生成明天复习卡。
- 追踪用户是否真的记住。

### C. 电商 AI 工具

**推荐方向**：

- Doubao：营销内容、客服、销售培训、多模态素材理解。
- Qwen：商品标题、详情页、结构化信息抽取。
- MiniMax：Excel/PPT/Word、运营报告、批量办公自动化。
- Kimi：商品图片/视频/长资料理解。

**核心闭环**：

商品资料输入 → 卖点抽取 → 标题/详情页/FAQ → 人工确认 → 导出到店铺/表格。

## 风险与不确定性

1. **版本漂移**：DeepSeek 已明确旧模型名停用时间，其他厂商也可能快速切模型。
2. **官方 benchmark 不等于产品效果**：Agent 任务尤其依赖工具环境、上下文缓存、重试策略。
3. **开放权重不等于可商用**：MiniMax、腾讯、StepFun、百川等均需分别审许可证。
4. **国产模型敏感议题限制**：新闻、历史、政策、国际化内容需加人工审核。
5. **多模态能力成本不透明**：视频、图片输入可能产生高 token 成本，必须提前估算。
6. **教育/医疗是高责任场景**：英语学习可做建议和练习，不能承诺诊断式或考试保过式效果；医疗模型更必须合规。

## 下一步建议

1. 用 20 个 Bryan 真实任务做一轮模型 A/B，不用公开 benchmark 做最终判断。
2. 首批模型：Qwen3.6、DeepSeek V4 Flash、Kimi K2.6、GLM-5.1、Doubao-Seed-2.0-lite。
3. 每个任务记录四个指标：质量、成本、耗时、是否需要人工修。
4. 英语学习 App 先做“每日纠错 + 复习卡”MVP。
5. 电商工具先做“商品资料 → 标题/卖点/FAQ → 表格导出”MVP。
6. 暂不做模型资讯网站；报告结论应转化为模型路由和产品验证计划。

## 信息来源

访问时间均为 2026-06-03。

1. DeepSeek V4 Preview Release，DeepSeek API Docs，发布时间：2026-04-24  
   https://api-docs.deepseek.com/news/news260424
2. DeepSeek Change Log，DeepSeek API Docs，访问时间：2026-06-03  
   https://api-docs.deepseek.com/updates/
3. Qwen3.6 GitHub 官方仓库，新闻时间：2026-04-16 / 2026-04-22  
   https://github.com/QwenLM/Qwen3.6
4. Qwen Code Weekly，发布时间：2026-05-28  
   https://qwenlm.github.io/qwen-code-docs/en/blog/weekly-update-2026-05-28/
5. Kimi Model List，Kimi API Platform，访问时间：2026-06-03  
   https://platform.kimi.ai/docs/models
6. Kimi K2.6 Quickstart，Kimi API Platform，访问时间：2026-06-03  
   https://platform.kimi.ai/docs/guide/kimi-k2-6-quickstart
7. Z.ai New Released / GLM-5.1，访问时间：2026-06-03  
   https://docs.z.ai/release-notes/new-released
8. ERNIE 5.1 官方发布博客，发布时间：2026-05-09  
   https://ernie.baidu.com/blog/posts/ernie-5.1-0508-release/
9. MiniMax M2.7 官方页面，访问时间：2026-06-03  
   https://www.minimax.io/models/text/m27
10. MiniMax-M2 Series Technical Report，arXiv，提交时间：2026-05-26  
    https://arxiv.org/abs/2605.26494
11. Tencent Hy3 preview 官方文章，发布时间：2026-04  
    https://www.tencent.com/zh-cn/articles/2202320.html
12. Tencent-Hunyuan/Hy3-preview GitHub，发布时间：2026-04-23  
    https://github.com/Tencent-Hunyuan/Hy3-preview
13. Step 3.7 Flash 官方博客，发布时间：2026-05-29  
    https://static.stepfun.com/blog/step-3.7-flash/
14. NVIDIA 技术博客：Run Step 3.7 Flash，发布时间：2026-05-28  
    https://developer.nvidia.com/blog/run-step-3-7-flash-on-nvidia-gpus-with-enterprise-ready-multimodal-ai/
15. Doubao-Seed-2.0-lite 升级说明，火山引擎开发者社区，发布时间：2026-05-07  
    https://developer.volcengine.com/articles/7636596381943070763
16. 豆包大模型产品页，火山引擎，访问时间：2026-06-03  
    https://www.volcengine.com/product/doubao
17. 科大讯飞教师超级智能体“星光”发布，发布时间：2026-04-24  
    https://edu.iflytek.com/about-us/news/company-news/2709
18. 科技日报：科大讯飞与中国移动发布“灵犀·星火智盒”，发布时间：2026-05-13  
    https://www.stdaily.com/web/gdxw/2026-05/13/content_515799.html
19. Baichuan-M3 Hugging Face 模型卡，访问时间：2026-06-03  
    https://huggingface.co/baichuan-inc/Baichuan-M3-235B
20. Baichuan-M3 Technical Report，arXiv，提交时间：2026-02-06  
    https://arxiv.org/abs/2602.06570
