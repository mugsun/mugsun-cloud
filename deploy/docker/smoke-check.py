"""微服务版的全套自检，在编排网络里的一次性容器里运行，由 smoke.sh 调用。

先原样执行从 mugsun-boot 同步来的 smoke.py（登录、改口令并重新登录、上报一条埋点并在管理端查到），
再用它留下的登录态检查微服务版才有的几件事：

- 登录信息里地图、埋点、AI 三个模块都是打开的：system 在 Nacos 里看得到另外三个服务；
- 同一个登录态在另外三个服务上也有效：四个服务用的是同一套密钥和同一个 Redis；
- AI 服务取得到地图和埋点两个服务的数据概况：服务之间的调用是通的，共享密钥对得上。
  这两次请求带着 smoke.sh 给的请求编号，它随后到网关、地图、埋点三处的日志里找这个编号；
- 登录日志里记下的是自检容器自己的地址：客户端地址经管理后台、网关一路传到了服务；
- 内部接口经网关访问不到；绕过网关直接访问服务，没有签名会被拒绝；
- Nacos 不接受没带账号的请求；
- 接口文档默认不提供：经管理后台取不到，直连每个服务也取不到；
- 带着 Accept-Language: en 请求时提示语是英文：工作台服务读到了公共层带的那份翻译文件，地图服务读到了它自己的；
- system 有两个实例时：登录态在两个实例上都有效；在一个实例上保存、删除的数据权限规则，另一个实例几秒内跟上；
- 建一个没有任何角色的账号并登录，登录令牌留给 smoke.sh 的最后一步：那一步看它进得去哪些接口。

上面的登录经网关轮流转给两个 system 实例：取验证码、校验验证码、登录这几步落在不同的实例上也要能过。
"""
import importlib.util
import json
import os
import socket
import sys
import time
import urllib.error
import urllib.request

# smoke.py 固定从 /secrets/boot/boot.env 读管理员初始口令、SM2 公钥和 Redis 口令。
# 这里格式相同、也有这三项的文件是四个业务服务共用的 service.env，做一个链接让它读得到
os.makedirs("/secrets/boot", exist_ok=True)
if not os.path.lexists("/secrets/boot/boot.env"):
    os.symlink("/secrets/service/service.env", "/secrets/boot/boot.env")

# 当作模块载入，载入时它从头到尾执行一遍；没通过时它自己退出，走不到下面
spec = importlib.util.spec_from_file_location("boot_smoke", "/smoke.py")
boot_smoke = importlib.util.module_from_spec(spec)
spec.loader.exec_module(boot_smoke)
BASE = boot_smoke.BASE
TOKEN = boot_smoke.token


def fail(msg):
    print("FAIL " + msg, file=sys.stderr)
    sys.exit(1)


def fetch(method, url, body=None, token=None, trace=None, language=None):
    """返回 (状态码, 响应体解析出的对象)。响应体不是 JSON 时对象为 None，连不上时状态码为 0。

    trace 是这次请求自带的请求编号；不给的话由网关定一个。language 是请求头 Accept-Language 的值，不给就不带。
    """
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", token)
    if trace:
        req.add_header("X-Trace-Id", trace)
    if language:
        req.add_header("Accept-Language", language)
    try:
        with urllib.request.urlopen(req, timeout=20) as resp:
            status, text = resp.status, resp.read()
    except urllib.error.HTTPError as err:
        status, text = err.code, err.read()
    except OSError:
        return 0, None
    try:
        return status, json.loads(text)
    except ValueError:
        return status, None


def api(method, path, body=None, trace=None):
    """带登录态访问管理后台反代出来的接口，要求业务上也成功，返回 data。"""
    status, res = fetch(method, BASE + path, body, TOKEN, trace)
    if status != 200 or not isinstance(res, dict) or res.get("code") != 200:
        fail("%s %s 没有成功：HTTP %s，%s" % (method, path, status, (res or {}).get("msg")))
    return res.get("data")


# 1. 三个模块的开关，由登录服务随登录信息返回。服务刚登记到 Nacos 时登录服务可能还没刷新到，等一等再下结论
flags = {}
for _ in range(30):
    info = api("GET", "/api/auth/info") or {}
    flags = {name: info.get(name) for name in ("gisEnabled", "trackEnabled", "aiEnabled")}
    if all(value is True for value in flags.values()):
        break
    time.sleep(2)
else:
    fail("登录信息里的模块开关不全是打开的：%s" % flags)
print("OK 登录信息里地图、埋点、AI 三个模块都是打开的")

# 2. 同一个登录态访问另外四个服务（登录是在登录服务上做的）。system 用的是取菜单的接口
for path in ("/api/v3/system/menus",
             "/api/system/gis/layer/page?pageNum=1&pageSize=1",
             "/api/system/track/app/page?pageNum=1&pageSize=1",
             "/api/system/ai/model/page?pageNum=1&pageSize=1"):
    api("GET", path)
print("OK 同一个登录态在 system、地图、埋点、AI 四个服务上都有效")

# 3. 服务之间的调用：这两个接口由 AI 服务接收，它再分别去地图、埋点服务取数据概况。
#    没配大模型也会返回取到的数据，只是没有解读文字。
#    两次请求都带着 smoke.sh 给的请求编号（TRACE_ID）：它随后到网关、地图、埋点三处的日志里找这个编号
trace_id = os.environ.get("TRACE_ID") or None
for name, path, source in (("地图", "/api/system/ai/weave/gisAsk", "gis_layer"),
                           ("埋点", "/api/system/ai/weave/trackInsight", "track_app")):
    data = api("POST", path, {"content": "自检"}, trace_id) or {}
    got = (data.get("data") or {}).get("source")
    if data.get("grounded") is not True or got != source:
        fail("AI 服务没有取到%s服务的数据：grounded=%s，source=%s" % (name, data.get("grounded"), got))
print("OK AI 服务取到了地图和埋点两个服务的数据（服务之间的调用正常）")

# 4. 客户端地址：管理后台把对端地址写进 X-Forwarded-For，网关只采信管理后台传来的，再传给服务。
#    哪一环没接上，记下来的都会是管理后台或网关的地址
me = socket.gethostbyname(socket.gethostname())
records = (api("GET", "/api/system/login-log/page?pageNum=1&pageSize=1&username=admin&exact=true") or {}).get("records") or []
logged = records[0].get("ip") if records else None
if logged != me:
    fail("登录日志记下的地址是 %s，自检容器的地址是 %s" % (logged, me))
print("OK 登录日志记下的是客户端自己的地址")

# 5. 内部接口：经管理后台、直连网关都访问不到；直连服务时没有签名会被拒绝，带着登录态也一样
for title, url, token, expected in (
        ("经管理后台访问内部接口", BASE + "/api/inner/gis/insight/snapshot", TOKEN, 404),
        ("直连网关访问内部接口", "http://gateway:8080/inner/gis/insight/snapshot", TOKEN, 404),
        ("直连地图服务的内部接口", "http://gis:8102/inner/gis/insight/snapshot", None, 401),
        ("带着登录态直连地图服务的内部接口", "http://gis:8102/inner/gis/insight/snapshot", TOKEN, 401),
        ("直连埋点服务的内部接口", "http://track:8103/inner/track/insight/snapshot", None, 401),
        ("直连 AI 服务的内部接口", "http://ai:8104/inner/ai/gen/draft", None, 401)):
    status, _ = fetch("POST", url, {}, token)
    if status != expected:
        fail("%s：应该返回 %s，实际是 %s" % (title, expected, status))
print("OK 内部接口经网关访问不到，直连服务时没有签名会被拒绝")

# 6. Nacos：没带账号查不到服务实例
status, _ = fetch("GET", "http://nacos:8848/nacos/v3/client/ns/instance/list?serviceName=mugsun-system")
if status != 403:
    fail("没带账号查 Nacos 的服务实例：应该返回 403，实际是 %s" % status)
print("OK Nacos 不接受没带账号的请求")

# 7. 接口文档默认不提供：带着管理员的登录态，经管理后台取不到，直连每个服务也取不到。
#    smoke.sh 的最后一步要从文档里取接口清单，到那时才临时打开
for title, url in (
        ("经管理后台取接口文档", BASE + "/api/v3/api-docs"),
        ("经管理后台打开接口文档的页面", BASE + "/api/swagger-ui/index.html"),
        ("直连 system 服务取接口文档", "http://system:8101/v3/api-docs"),
        ("直连地图服务取接口文档", "http://gis:8102/v3/api-docs"),
        ("直连埋点服务取接口文档", "http://track:8103/v3/api-docs"),
        ("直连 AI 服务取接口文档", "http://ai:8104/v3/api-docs")):
    status, _ = fetch("GET", url, None, TOKEN)
    if status != 404:
        fail("%s：默认不提供，应该返回 404，实际是 %s" % (title, status))
print("OK 接口文档默认不提供")


# 7a. 多语言：带着 Accept-Language: en 请求时提示语是英文，不带时是中文。查一个不存在的编号，看报回来的那句话。两个接口各管一处：
#     公告详情在工作台服务上，它的句子在公共层带的那份翻译文件里（core 的类分在十几个服务里，这份文件每个服务都要读到）；
#     图层详情在地图服务上，句子在地图服务自己的翻译文件里。
#     只看是中文还是英文，不写死英文的原话：那句话在 mugsun-boot 里维护，改了用词这里不该跟着失败
def refusal(path, language):
    """带登录态请求一个会报「不存在」的接口，返回报回来的那句话。"""
    status, res = fetch("GET", BASE + path, None, TOKEN, language=language)
    msg = res.get("msg") if isinstance(res, dict) else None
    if not isinstance(msg, str) or not msg or res.get("code") == 200:
        fail("GET %s 应该报「不存在」，实际是 HTTP %s，%s" % (path, status, res))
    return msg


def has_chinese(text):
    return any("\u4e00" <= ch <= "\u9fff" for ch in text)


for title, path in (("工作台服务", "/api/system/notice/detail?id=987654321098765"),
                    ("地图服务", "/api/system/gis/layer/detail/987654321098765")):
    plain, english = refusal(path, None), refusal(path, "en")
    if not has_chinese(plain):
        fail("%s：没带语言时提示语应该是中文，实际是：%s" % (title, plain))
    if has_chinese(english):
        fail("%s：带着 Accept-Language: en 时提示语应该是英文，实际是：%s" % (title, english))
print("OK 带着 Accept-Language: en 请求时，工作台服务和地图服务的提示语是英文，不带时是中文")

# 8. 两个 system 实例。smoke.sh 把各实例的地址放在 SYSTEM_ADDRS 里，只有一个实例时跳过。
#    这里绕过网关直接访问指定的实例：经网关的话请求落在哪个实例上说不准
SYSTEM_ADDRS = os.environ.get("SYSTEM_ADDRS", "").split()


def direct(addr, method, path, body=None):
    """带登录态访问指定的 system 实例，要求业务上也成功，返回 data。"""
    status, res = fetch(method, "http://%s:8101%s" % (addr, path), body, TOKEN)
    if status != 200 or not isinstance(res, dict) or res.get("code") != 200:
        fail("实例 %s 上 %s %s 没有成功：HTTP %s，%s" % (addr, method, path, status, (res or {}).get("msg")))
    return res.get("data")


def in_effect(addr, table):
    """这个实例的内存里，有没有这张表的数据权限规则。"""
    return any(row.get("table") == table for row in direct(addr, "GET", "/system/data-scope/effective") or [])


def seconds_until(limit, done):
    """每 0.2 秒看一次，返回等了几秒；到时间还没等到返回 None。"""
    began = time.monotonic()
    while time.monotonic() - began < limit:
        if done():
            return time.monotonic() - began
        time.sleep(0.2)
    return None


if len(SYSTEM_ADDRS) > 1:
    # 7.1 登录态存在 Redis 里，在哪个实例上都有效
    for addr in SYSTEM_ADDRS:
        direct(addr, "GET", "/v3/system/menus")
    print("OK 同一个登录态在 %d 个 system 实例上都有效" % len(SYSTEM_ADDRS))

    # 7.2 数据权限规则每个实例在内存里各有一份。在一个实例上保存，再删除，另一个实例都要在 5 秒内跟上，两个方向各做一遍。
    #     各实例另有每 30 秒一次的定时加载兜底，它最多碰巧让其中一步过关：连着两步都在 5 秒内跟上，靠的只能是保存后的通知
    for saver, other in ((SYSTEM_ADDRS[0], SYSTEM_ADDRS[1]), (SYSTEM_ADDRS[1], SYSTEM_ADDRS[0])):
        table = "smoke_" + os.urandom(4).hex()
        direct(saver, "POST", "/system/data-scope/submit", {
            "scopeName": table, "resourceCode": table, "tableName": table,
            "deptColumn": "dept_id", "userColumn": "create_user", "scopeType": 2, "status": 1})
        if not in_effect(saver, table):
            fail("在实例 %s 上保存的数据权限规则，它自己没有马上生效" % saver)
        saved = seconds_until(5, lambda: in_effect(other, table))
        if saved is None:
            fail("在实例 %s 上保存的数据权限规则，5 秒后实例 %s 上还没有生效" % (saver, other))
        page = direct(saver, "GET", "/system/data-scope/page?pageNum=1&pageSize=10&name=" + table) or {}
        ids = [row.get("id") for row in page.get("records") or [] if row.get("tableName") == table]
        if len(ids) != 1:
            fail("刚保存的数据权限规则在列表里查到 %d 条，应该是 1 条" % len(ids))
        direct(saver, "POST", "/system/data-scope/remove", ids)
        removed = seconds_until(5, lambda: not in_effect(other, table))
        if removed is None:
            fail("在实例 %s 上删除的数据权限规则，5 秒后实例 %s 上还在生效" % (saver, other))
        print("OK 实例 %s 上保存、删除数据权限规则，实例 %s 分别在 %.1f 秒、%.1f 秒后跟上" % (saver, other, saved, removed))

# 9. 建一个没有任何角色的账号并登录，把登录令牌留给 smoke.sh 的最后一步（smoke-sweep.py roleless）：
#    那一步带着它把每个接口请求一遍，看没有角色的人进得去哪些。接口清单要到那时打开接口文档才有，
#    建账号又要用管理员的登录态，所以账号在这里建。令牌只写进交接用的卷，不打印。
#    这里登录两次，加上 smoke.py 的两次一共四次：行为验证码的键盘方式按来源地址限每分钟 6 次，再加登录要先看这个上限
HANDOFF = "/handoff"


def login_as(username, password):
    """用 smoke.py 的登录流程登录另一个账号。它的登录函数把用户名写死成了 admin，这里在登录请求发出去之前换掉。"""
    original = boot_smoke.call

    def call(method, path, body=None, token=None):
        if path == "/api/auth/login":
            body = dict(body, username=username)
        return original(method, path, body, token)

    boot_smoke.call = call
    try:
        return boot_smoke.login_with_password(password)
    except AssertionError as err:
        fail("账号 %s 登录失败：%s" % (username, err))
    finally:
        boot_smoke.call = original


if os.path.isdir(HANDOFF):
    roleless = "smoke_roleless_" + os.urandom(4).hex()
    initial = (api("POST", "/api/system/user/submit", {"username": roleless, "nickname": "自检：没有角色"}) or {}).get("initialPassword")
    if not initial:
        fail("新建账号 %s 的应答里没有初始口令" % roleless)
    # 新账号第一次登录以后只能先改口令；改完旧的登录令牌失效，用新口令再登录一次
    first = login_as(roleless, initial)
    changed = "Smk-" + os.urandom(6).hex() + "aZ9"
    status, res = fetch("POST", BASE + "/api/auth/update-password",
                        {"oldPassword": boot_smoke.sm2enc(initial), "newPassword": boot_smoke.sm2enc(changed)}, first)
    if status != 200 or not isinstance(res, dict) or res.get("code") != 200:
        fail("账号 %s 第一次登录后改口令没有成功：HTTP %s，%s" % (roleless, status, (res or {}).get("msg")))
    roleless_token = login_as(roleless, changed)
    status, res = fetch("GET", BASE + "/api/auth/info", None, roleless_token)
    info = res.get("data") if status == 200 and isinstance(res, dict) else None
    if not isinstance(info, dict):
        fail("账号 %s 登录后取不到登录信息：HTTP %s" % (roleless, status))
    if info.get("roles") or info.get("buttons"):
        fail("刚建的账号 %s 没有分配角色，登录信息里却有 %d 个角色、%d 个权限码"
             % (roleless, len(info.get("roles") or []), len(info.get("buttons") or [])))
    fd = os.open(HANDOFF + "/roleless-token", os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w") as f:
        f.write(roleless_token)
    print("OK 建了一个没有任何角色的账号 %s 并登录，留给最后一步：看它进得去哪些接口" % roleless)
