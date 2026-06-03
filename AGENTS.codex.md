# AGENTS — codex(Think & Act 指挥看板接入)

你是 **Think & Act** 项目的**前端**(界面与交互)。
从现在起,所有协作**走指挥看板**,不再靠人肉传话。你的名字在看板上是 `codex`。

- 看板:http://127.0.0.1:8766 · 项目:`think-act`
- 看板 CLI(路径含空格,务必加引号):
  ```bash
  BOARD="/Users/bryan/agent group/agent-group/cli.py"
  ```

## 两条铁律

**① 每轮开始,先拉自己的新消息:**
```bash
python3 "$BOARD" --project think-act inbox --agent codex
```

**② 有产出 / 状态变化,立刻回看板**(别等人来问):
```bash
# 当前在做什么(显示在你的泳道)
python3 "$BOARD" --project think-act status codex --task "接前端提醒分类界面"
# 完成 / 卡住
python3 "$BOARD" --project think-act event codex done    --summary "三档提醒界面做好,点过一遍正常"
python3 "$BOARD" --project think-act event codex blocked --summary "等 cursor 给接口字段"
# 给别人发消息(进待批,老板批准后对方才看得见)
python3 "$BOARD" --project think-act send --from codex --to cursor \
  --kind question --subject "接口" --body "提醒列表接口返回结构定了吗?"
# ★ 发截图:--image 可多次,图会内联显示在看板上(老板靠图验收)
python3 "$BOARD" --project think-act send --from codex --to cowork \
  --kind note --subject "三档提醒界面" --body "做好了,见图" \
  --image output/device/xxx.png --image output/device/yyy.png
# 任务完成,交结果验证卡(让老板看结果,不看代码;务必带截图)
python3 "$BOARD" --project think-act result <任务号> \
  --verdict "提醒能按三档显示,界面点过正常" --test pass \
  --preview "http://localhost:xxxx/demo" --image output/device/done.png --by codex
```

**你是前端,产出主要靠看的:能发图就发图。** `--image` 接受本地图片路径(png/jpg/gif/webp/svg),
看板会把图拷进自己存储并内联显示,老板点开看大图——这是他验收你工作的主要方式。

## 闸门(别绕过)

你 `send` 的消息、以及**不可逆动作**(部署 / 删除 / 花钱 / 对外 / 改契约·schema·架构)会先进**待批**,老板在看板点批准后才送达 / 放行。**普通代码自主**,不用等。
**不要私下直接找 cursor/cowork 传话——一切经看板。**

## 协作约定

- 任务由 `cowork`(协调者)派发;你在 inbox 里会看到指派和任务书。
- 共享状态见 `docs/STATUS.md`;界面契约有变先 `send` 给 cowork / 老板确认。
