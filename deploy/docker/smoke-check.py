"""微服务版的全套自检，在编排网络里的一次性容器里运行，由 smoke.sh 调用。

先原样执行从 mugsun-boot 同步来的 smoke.py（登录、改口令并重新登录、上报一条埋点并在管理端查到），
再用它留下的登录态检查微服务版才有的几件事：

- 登录信息里地图、埋点、AI 三个模块都是打开的：system 在 Nacos 里看得到另外三个服务；
- 同一个登录态在另外三个服务上也有效：四个服务用的是同一套密钥和同一个 Redis；
- AI 服务取得到地图和埋点两个服务的数据概况：服务之间的调用是通的，共享密钥对得上；
- 登录日志里记下的是自检容器自己的地址：客户端地址经管理后台、网关一路传到了服务；
- 内部接口经网关访问不到；绕过网关直接访问服务，没有签名会被拒绝；
- Nacos 不接受没带账号的请求。
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
