"""微服务版的全套自检，在编排网络里的一次性容器里运行，由 smoke.sh 调用。

先原样执行从 mugsun-boot 同步来的 smoke.py（登录、改口令并重新登录、上报一条埋点并在管理端查到），
再用它留下的登录态检查微服务版才有的几件事：

- 登录信息里地图、埋点、AI 三个模块都是打开的：system 在 Nacos 里看得到另外三个服务；
- 同一个登录态在另外三个服务上也有效：四个服务用的是同一套密钥和同一个 Redis；
- AI 服务取得到地图和埋点两个服务的数据概况：服务之间的调用是通的，共享密钥对得上；
- 登录日志里记下的是自检容器自己的地址：客户端地址经管理后台、网关一路传到了服务；
- 内部接口经网关访问不到；绕过网关直接访问服务，没有签名会被拒绝；
- Nacos 不接受没带账号的请求；
- system 有两个实例时：登录态在两个实例上都有效；在一个实例上保存、删除的数据权限规则，另一个实例几秒内跟上。

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


def fetch(method, url, body=None, token=None):
    """返回 (状态码, 响应体解析出的对象)。响应体不是 JSON 时对象为 None，连不上时状态码为 0。"""
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", token)
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


def api(method, path, body=None):
    """带登录态访问管理后台反代出来的接口，要求业务上也成功，返回 data。"""
    status, res = fetch(method, BASE + path, body, TOKEN)
    if status != 200 or not isinstance(res, dict) or res.get("code") != 200:
        fail("%s %s 没有成功：HTTP %s，%s" % (method, path, status, (res or {}).get("msg")))
    return res.get("data")


# 1. 三个模块的开关。服务刚登记到 Nacos 时 system 可能还没刷新到，等一等再下结论
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

# 2. 同一个登录态访问另外三个服务（system 在上面登录时已经用过）
for path in ("/api/system/gis/layer/page?pageNum=1&pageSize=1",
             "/api/system/track/app/page?pageNum=1&pageSize=1",
             "/api/system/ai/model/page?pageNum=1&pageSize=1"):
    api("GET", path)
print("OK 同一个登录态在地图、埋点、AI 三个服务上都有效")

# 3. 服务之间的调用：这两个接口由 AI 服务接收，它再分别去地图、埋点服务取数据概况。
#    没配大模型也会返回取到的数据，只是没有解读文字
for name, path, source in (("地图", "/api/system/ai/weave/gisAsk", "gis_layer"),
                           ("埋点", "/api/system/ai/weave/trackInsight", "track_app")):
    data = api("POST", path, {"content": "自检"}) or {}
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

# 7. 两个 system 实例。smoke.sh 把各实例的地址放在 SYSTEM_ADDRS 里，只有一个实例时跳过。
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
        direct(addr, "GET", "/auth/info")
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
