#!/usr/bin/env python3
"""
Think & Act · 真机测试数据助手（造/清/改 tasks，走 Supabase REST）

为什么：UI 造数据慢且易误触；REST 直接、确定、可逆（软删）。
谁用：cowork / 任何自动化会话做真机回归时，预置确定状态（如"一条已完成任务"）。

自包含：
  - token / user_id 从**机上已登录会话**自动取（adb run-as 读 prefs），无需登录。
  - Supabase URL / publishable key 从源码 SupabaseConfig.kt 读，**不在脚本里硬编码密钥**。
  - 日期/时间按**设备时钟**算（真机可能不在本机时区）。

依赖：仅标准库（urllib）。无需 pip。
环境变量（可选覆盖）：TNA_ADB, TNA_DEV, TNA_PKG。

用法：
  python3 scripts/tna_seed.py list
  python3 scripts/tna_seed.py clear                      # 软删今天所有任务（可逆）
  python3 scripts/tna_seed.py add --title "写周报" --start +25m --dur 30 --important
  python3 scripts/tna_seed.py add --title "读书" --start=-50m --dur 30 --status planned
  python3 scripts/tna_seed.py add --title "建议:散步" --start +60m --status suggested --source ai_suggestion
  python3 scripts/tna_seed.py done <task_id>             # status=done + actual_end=now
  python3 scripts/tna_seed.py set  <task_id> status skipped
  python3 scripts/tna_seed.py set  <task_id> planned_duration null
  python3 scripts/tna_seed.py set  <task_id> deleted_at now()    # = 软删单条

--start 接受：+Nm / -Nm / +Nh / -Nh（相对设备当前时刻），或 HH:MM（设备本地当天），或 now。
"""
import json, os, re, subprocess, sys, urllib.request, urllib.error, argparse, datetime, pathlib, base64, time

ADB = os.environ.get("TNA_ADB", "/Users/bryan/Library/Android/sdk/platform-tools/adb")
DEV = os.environ.get("TNA_DEV", "10AE3Z13YU002EL")
PKG = os.environ.get("TNA_PKG", "com.thinkandact.app")
REPO = pathlib.Path(__file__).resolve().parent.parent
CONFIG = REPO / "shared/src/commonMain/kotlin/com/thinkandact/core/config/SupabaseConfig.kt"
PREFS = "shared_prefs/think_and_act_session.xml"

VALID_TYPES = {"deep_work", "health", "admin"}
VALID_SOURCES = {"user_text", "user_voice", "ai_suggestion", "proposal"}


def sh(*args):
    return subprocess.run([ADB, "-s", DEV, *args], capture_output=True, text=True).stdout


def load_config():
    txt = CONFIG.read_text(encoding="utf-8")
    url = re.search(r'URL\s*=\s*"([^"]+)"', txt)
    key = re.search(r'CLIENT_KEY\s*=\s*"([^"]+)"', txt)
    if not url or not key:
        sys.exit(f"无法从 {CONFIG} 解析 URL/CLIENT_KEY")
    return url.group(1).rstrip("/"), key.group(1)


def _jwt_exp(token):
    try:
        payload = token.split(".")[1]
        payload += "=" * (-len(payload) % 4)
        return json.loads(base64.urlsafe_b64decode(payload)).get("exp", 0)
    except Exception:
        return 0


def _refresh(url, key, refresh_token):
    """机上 token 过期时，用 refresh_token 换新（Supabase auth）。"""
    data = json.dumps({"refresh_token": refresh_token}).encode()
    r = urllib.request.Request(url + "/auth/v1/token?grant_type=refresh_token", data=data, method="POST")
    r.add_header("apikey", key); r.add_header("Content-Type", "application/json")
    try:
        return json.loads(urllib.request.urlopen(r).read().decode()).get("access_token")
    except urllib.error.HTTPError:
        return None


def load_session(url, key):
    xml = sh("shell", "run-as", PKG, "cat", PREFS)
    tok = re.search(r'name="access_token">([^<]+)', xml)
    uid = re.search(r'name="user_id">([^<]+)', xml)
    rft = re.search(r'name="refresh_token">([^<]+)', xml)
    if not tok or not uid:
        sys.exit("读不到机上会话：先在手机上登录 App（run-as 需 debug 包）。")
    token = tok.group(1)
    # token 过期（或快过期）→ 先尝试 refresh_token 换新；不行就提示开 App 刷新。
    if _jwt_exp(token) < int(time.time()) + 30 and rft:
        new = _refresh(url, key, rft.group(1))
        if new:
            token = new
        else:
            sys.exit("机上 token 已过期且 refresh 失败：在手机上打开一次 App（会自动刷新会话）后重试。")
    return token, uid.group(1)


def device_now():
    epoch = int(sh("shell", "date", "+%s").strip())
    local_date = sh("shell", "date", "+%F").strip()  # 设备本地当天
    return epoch, local_date


URL, KEY = load_config()
TOKEN, USER = load_session(URL, KEY)
NOW_EPOCH, TODAY = device_now()


def iso_utc(epoch):
    return datetime.datetime.utcfromtimestamp(epoch).strftime("%Y-%m-%dT%H:%M:%S+00:00")


def parse_start(s):
    if s in (None, "now"):
        return iso_utc(NOW_EPOCH)
    m = re.fullmatch(r'([+-])(\d+)([mh])', s)
    if m:
        sign = 1 if m.group(1) == "+" else -1
        n = int(m.group(2)) * (60 if m.group(3) == "m" else 3600)
        return iso_utc(NOW_EPOCH + sign * n)
    m = re.fullmatch(r'(\d{1,2}):(\d{2})', s)
    if m:  # HH:MM 设备本地当天 → 用设备时区偏移换 UTC
        off = NOW_EPOCH - int(datetime.datetime.strptime(
            datetime.datetime.utcfromtimestamp(NOW_EPOCH).strftime("%H:%M"), "%H:%M").timestamp())  # 粗略
        base = datetime.datetime.strptime(f"{TODAY} {m.group(1)}:{m.group(2)}", "%Y-%m-%d %H:%M")
        # 设备本地偏移：device epoch 的本地小时 - UTC 小时
        loc_h = int(sh("shell", "date", "+%H").strip()); loc_m = int(sh("shell", "date", "+%M").strip())
        utc_dt = datetime.datetime.utcfromtimestamp(NOW_EPOCH)
        offset_min = (loc_h * 60 + loc_m) - (utc_dt.hour * 60 + utc_dt.minute)
        target = base - datetime.timedelta(minutes=offset_min)
        return target.strftime("%Y-%m-%dT%H:%M:%S+00:00")
    sys.exit(f"--start 格式不认：{s}（用 +25m/-50m/+1h/HH:MM/now）")


def req(method, path, body=None, prefer=None):
    data = json.dumps(body).encode() if body is not None else None
    r = urllib.request.Request(URL + path, data=data, method=method)
    r.add_header("apikey", KEY)
    r.add_header("Authorization", "Bearer " + TOKEN)
    r.add_header("Content-Type", "application/json")
    if prefer:
        r.add_header("Prefer", prefer)
    try:
        resp = urllib.request.urlopen(r)
        return resp.status, resp.read().decode()
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode()


def coerce(field, val):
    if val == "null":
        return None
    if val == "now()":
        return "now()"
    if field in ("planned_duration", "reschedule_count"):
        return int(val)
    if field == "important":
        return val.lower() in ("1", "true", "yes")
    return val


def cmd_list(_):
    st, tx = req("GET", f"/rest/v1/tasks?user_id=eq.{USER}&date=eq.{TODAY}&deleted_at=is.null"
                 "&select=id,title,planned_start,planned_duration,status,important,source&order=planned_start.asc")
    print("list", st, f"(today={TODAY})")
    if st < 300:
        for r in json.loads(tx):
            star = "★" if r.get("important") else " "
            print(f"  {r['id'][:8]} {star} {r.get('planned_start')} {r.get('planned_duration')}m "
                  f"{r['status']:9} {r.get('source')}  {r['title']}")
    else:
        print(tx[:400])


def cmd_clear(_):
    st, tx = req("PATCH", f"/rest/v1/tasks?user_id=eq.{USER}&date=eq.{TODAY}&deleted_at=is.null",
                 {"deleted_at": "now()"}, prefer="return=minimal")
    print("clear(soft-delete today)", st, tx[:200])


def cmd_add(a):
    if a.type and a.type not in VALID_TYPES:
        sys.exit(f"task_type 只认 {VALID_TYPES}")
    if a.source not in VALID_SOURCES:
        sys.exit(f"source 只认 {VALID_SOURCES}")
    row = {
        "user_id": USER, "date": TODAY, "title": a.title,
        "planned_start": parse_start(a.start), "planned_duration": a.dur,
        "important": a.important, "status": a.status,
        "task_type": a.type, "source": a.source,
    }
    st, tx = req("POST", "/rest/v1/tasks", [row], prefer="return=representation")
    print("add", st)
    if st < 300:
        for r in json.loads(tx):
            print(f"  id={r['id']} {r['planned_start']} {r['status']} {r['title']}")
    else:
        print(tx[:400])


def cmd_done(a):
    st, tx = req("PATCH", f"/rest/v1/tasks?id=eq.{a.id}",
                 {"status": "done", "actual_end": iso_utc(NOW_EPOCH)}, prefer="return=minimal")
    print("done", st, tx[:120])


def cmd_set(a):
    st, tx = req("PATCH", f"/rest/v1/tasks?id=eq.{a.id}",
                 {a.field: coerce(a.field, a.value)}, prefer="return=minimal")
    print(f"set {a.field}={a.value}", st, tx[:120])


p = argparse.ArgumentParser(description="Think & Act 真机测试数据助手")
sub = p.add_subparsers(dest="cmd", required=True)
sub.add_parser("list").set_defaults(fn=cmd_list)
sub.add_parser("clear").set_defaults(fn=cmd_clear)
pa = sub.add_parser("add"); pa.set_defaults(fn=cmd_add)
pa.add_argument("--title", required=True)
pa.add_argument("--start", default="now")
pa.add_argument("--dur", type=int, default=30)
pa.add_argument("--important", action="store_true")
pa.add_argument("--status", default="planned", choices=["planned", "suggested", "done", "skipped", "dropped"])
pa.add_argument("--type", default="deep_work")
pa.add_argument("--source", default="user_text")
pd = sub.add_parser("done"); pd.set_defaults(fn=cmd_done); pd.add_argument("id")
ps = sub.add_parser("set"); ps.set_defaults(fn=cmd_set)
ps.add_argument("id"); ps.add_argument("field"); ps.add_argument("value")

args = p.parse_args()
args.fn(args)
