"""给 Nacos 设好管理员口令，再建一个接入账号给网关和各服务用：能注册和查询服务、能读配置，不能改配置，也不能管理账号。

每次 docker compose up 都会执行一遍，已经有的不重复创建。口令从初始化容器专用的密钥卷里读，不打印。

用法：
    python3 /nacos-init.py                       初始化，并核对接入账号可用、没带账号的请求会被拒绝
    python3 /nacos-init.py status                列出各服务在 Nacos 里登记的实例
    python3 /nacos-init.py config list           列出配置中心里有哪几份配置
    python3 /nacos-init.py config get <名字>      打印一份配置的内容
    python3 /nacos-init.py config put <名字>      把标准输入的内容存成一份配置，已经有的整份换掉
    python3 /nacos-init.py config delete <名字>   删掉一份配置
名字是 Nacos 里的 Data ID，比如 mugsun.yaml。分组固定是 DEFAULT_GROUP，命名空间是默认的那个（public）。
config 这几个命令用管理员账号执行：接入账号改不了配置。

只用 Python 标准库。
"""

import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

BASE = os.environ.get("MUGSUN_NACOS_URL", "http://nacos:8848/nacos")
SECRETS_FILE = os.environ.get("MUGSUN_NACOS_ADMIN_FILE", "/secrets/nacos-admin/admin.env")
ADMIN_USER = "nacos"
ROLE = "mugsun-service"
NAMESPACE = "public"
GROUP = "DEFAULT_GROUP"
# 接入账号的权限，都只在默认命名空间里：服务注册和发现要读写；配置只读，网关和各服务从配置中心读配置用。
# 不能改配置，也不能管理账号
PERMISSIONS = [(NAMESPACE + ":*:naming/*", "rw"), (NAMESPACE + ":*:config/*", "r")]
# 配置的格式按名字的后缀定，不认识的后缀按纯文本存
CONFIG_TYPES = {"yaml": "yaml", "yml": "yaml", "properties": "properties", "json": "json", "xml": "xml"}
USAGE = "用法：nacos-init.py [status | config list | config get <名字> | config put <名字> | config delete <名字>]"
SERVICES = ["mugsun-gateway", "mugsun-system", "mugsun-gis", "mugsun-track", "mugsun-ai", "mugsun-log", "mugsun-report", "mugsun-develop", "mugsun-job", "mugsun-resource", "mugsun-flow", "mugsun-desk", "mugsun-auth"]
RESET_HINT = "密钥卷和 Nacos 的数据卷不是同一次生成的。用 docker compose down -v 清空后重来"


def die(msg):
    sys.stderr.write("!! " + msg + "\n")
    sys.exit(1)


def read_secrets():
    values = {}
    try:
        with open(SECRETS_FILE, encoding="utf-8") as fh:
            for line in fh:
                key, sep, value = line.strip().partition("=")
                if sep:
                    values[key] = value
    except OSError as err:
        die("读不到 %s：%s" % (SECRETS_FILE, err.strerror))
    for key in ("NACOS_ADMIN_PASSWORD", "NACOS_SERVICE_USERNAME", "NACOS_SERVICE_PASSWORD"):
        if not values.get(key):
            die("%s 里没有 %s" % (SECRETS_FILE, key))
    return values


def request(method, path, params=None, token=None):
    """发一个请求，返回 (状态码, 响应体解析出的对象)。响应体为空或不是 JSON 时对象为 None。连不上时抛 OSError。"""
    url = BASE + path
    data = None
    if params is not None:
        encoded = urllib.parse.urlencode(params)
        if method == "GET":
            url += "?" + encoded
        else:
            data = encoded.encode("utf-8")
    req = urllib.request.Request(url, data=data, method=method)
    if token:
        req.add_header("accessToken", token)
    try:
        with urllib.request.urlopen(req, timeout=10) as resp:
            status, body = resp.status, resp.read()
    except urllib.error.HTTPError as err:
        status, body = err.code, err.read()
    try:
        parsed = json.loads(body.decode("utf-8")) if body else None
    except ValueError:
        parsed = None
    return status, parsed


def brief(status, parsed):
    """出错时给人看的简短说明。只取状态码和提示语，不带响应里的数据：设管理员口令的响应会把口令原样返回。"""
    if isinstance(parsed, dict):
        return "HTTP %s，code=%s，%s" % (status, parsed.get("code"), parsed.get("message"))
    return "HTTP %s" % status


def succeeded(status, parsed):
    return status == 200 and isinstance(parsed, dict) and parsed.get("code") == 0


def wait_ready():
    for _ in range(60):
        try:
            status, _ = request("GET", "/v3/admin/core/state/readiness")
            if status == 200:
                return
        except OSError:
            pass
        time.sleep(2)
    die("等了 120 秒 Nacos 还没就绪：%s" % BASE)


def login(username, password, attempts=1):
    """登录成功返回令牌；口令不对、账号不存在返回 None。attempts 大于 1 时隔两秒重试，给刚启动的 Nacos 一点时间。"""
    for attempt in range(attempts):
        if attempt:
            time.sleep(2)
        status, parsed = request("POST", "/v3/auth/user/login", {"username": username, "password": password})
        if status == 200 and isinstance(parsed, dict) and parsed.get("accessToken"):
            return parsed["accessToken"]
    return None


def listed(path, params, token):
    """查一个列表接口，返回里面的条目；查不了时返回 None。"""
    query = dict(params, pageNo=1, pageSize=100, search="accurate")
    status, parsed = request("GET", path, query, token)
    if not succeeded(status, parsed):
        return None
    return (parsed.get("data") or {}).get("pageItems") or []


def ensure(what, exists, create):
    """先查有没有，没有就创建，再查一遍确认。返回这次是不是新建的。

    不看创建接口自己的返回：「已经有了」和「刚启动、还没准备好」两种情况，Nacos 都是返回 200 和空的响应体，分不出来。
    Nacos 刚启动的头几秒里创建还会不生效，所以没查到就隔两秒再试，最多等一分钟。
    """
    deadline = time.time() + 60
    tried = False
    while not exists():
        if time.time() > deadline:
            die("%s没有成功：等了 60 秒，Nacos 里还是查不到" % what)
        if tried:
            time.sleep(2)
        create()
        tried = True
    return tried


def ensure_admin(password):
    """管理员口令只在第一次启动时设置；以后每次直接用密钥卷里的口令登录。"""
    token = login(ADMIN_USER, password)
    if token:
        return token
    status, parsed = request("POST", "/v3/auth/user/admin", {"password": password})
    code = parsed.get("code") if isinstance(parsed, dict) else None
    if succeeded(status, parsed):
        print(">> 已设置 Nacos 管理员口令")
    elif code != 409 and status != 409:
        # 409 表示管理员以前设过了：下面再登录几次，还不行就是口令对不上
        die("设置 Nacos 管理员口令失败：" + brief(status, parsed))
    token = login(ADMIN_USER, password, attempts=10)
    if not token:
        die("用密钥卷里的口令登录不了 Nacos 管理员。" + RESET_HINT)
    return token


def ensure_service_account(admin_token, username, password):
    def has_user():
        items = listed("/v3/auth/user/list", {"username": username}, admin_token)
        return bool(items) and any(item.get("username") == username for item in items)

    def has_role():
        items = listed("/v3/auth/role/list", {"username": username}, admin_token)
        return bool(items) and any(item.get("role") == ROLE and item.get("username") == username for item in items)

    def has_permission(resource, action):
        def check():
            items = listed("/v3/auth/permission/list", {"role": ROLE}, admin_token)
            return bool(items) and any(item.get("role") == ROLE and item.get("resource") == resource
                                       and item.get("action") == action for item in items)
        return check

    created = ensure("建接入账号 %s " % username, has_user, lambda: request(
        "POST", "/v3/auth/user", {"username": username, "password": password}, admin_token))
    ensure("给接入账号绑角色", has_role, lambda: request(
        "POST", "/v3/auth/role", {"role": ROLE, "username": username}, admin_token))
    # 以前建的接入账号只有第一项，这里会给它补上读配置的那一项
    for resource, action in PERMISSIONS:
        ensure("给接入账号授权（%s）" % resource, has_permission(resource, action),
               lambda resource=resource, action=action: request(
                   "POST", "/v3/auth/permission", {"role": ROLE, "resource": resource, "action": action}, admin_token))
    print(">> 接入账号 %s %s，权限：%s" % (username, "已创建" if created else "已存在", "、".join(
        "%s %s" % (resource, "读写" if action == "rw" else "只读") for resource, action in PERMISSIONS)))


def instances(token, service):
    return request("GET", "/v3/client/ns/instance/list", {"serviceName": service}, token)


def config_params(data_id):
    return {"dataId": data_id, "groupName": GROUP, "namespaceId": NAMESPACE}


def verify(username, password):
    """核对五件事：接入账号能查服务、能读配置、改不了配置；没带账号的请求被拒绝；接入账号碰不到账号管理。"""
    token = login(username, password, attempts=10)
    if not token:
        die("接入账号 %s 用密钥卷里的口令登录不了。%s" % (username, RESET_HINT))
    # 刚授的权限 Nacos 可能要过一会儿才认，等一等再下结论
    status, parsed = 0, None
    for _ in range(20):
        status, parsed = instances(token, SERVICES[1])
        if status == 200:
            break
        time.sleep(2)
    else:
        die("接入账号 %s 查不了服务实例：%s" % (username, brief(status, parsed)))
    # 读一份不存在的配置：有读配置的权限时 Nacos 3.0.3 应答 200（响应体里说没有这份配置），应答 404 也算有权限；
    # 没有权限时是 403。不读真有的那几份，内容不该经过这里
    for _ in range(20):
        status, parsed = request("GET", "/v3/client/cs/config", config_params("mugsun-permission-check"), token)
        if status in (200, 404):
            break
        time.sleep(2)
    else:
        die("接入账号 %s 读不了配置：%s" % (username, brief(status, parsed)))
    # 改配置要被拒绝。拿「删一份不存在的配置」来试：万一没有被拒绝，也没有东西被改动
    status, parsed = request("DELETE", "/v3/admin/cs/config?" + urllib.parse.urlencode(
        config_params("mugsun-permission-check")), None, token)
    if status != 403:
        die("接入账号 %s 改配置的请求没有被 Nacos 拒绝（HTTP %s），权限给多了" % (username, status))
    status, parsed = instances(None, SERVICES[1])
    if status != 403:
        die("没带账号的请求没有被 Nacos 拒绝（HTTP %s）：客户端鉴权没有打开，检查 NACOS_AUTH_ENABLE" % status)
    status, parsed = request("GET", "/v3/auth/user/list",
                             {"pageNo": 1, "pageSize": 1, "search": "accurate", "username": username}, token)
    if status != 403:
        die("接入账号 %s 能访问账号管理接口（HTTP %s），权限给多了" % (username, status))
    print(">> 已核对：接入账号能查服务、能读配置、改不了配置，没带账号的请求被拒绝，接入账号没有管理权限")


def show_status(username, password):
    token = login(username, password, attempts=5)
    if not token:
        die("接入账号 %s 登录不了" % username)
    for service in SERVICES:
        status, parsed = instances(token, service)
        if not succeeded(status, parsed):
            die("查不到 %s 的实例：%s" % (service, brief(status, parsed)))
        found = ["%s:%s" % (item.get("ip"), item.get("port"))
                 for item in parsed.get("data") or [] if item.get("healthy") and item.get("enabled")]
        print("%s: %s" % (service, " ".join(sorted(found)) or "没有实例"))


def config_command(admin_token, args):
    """配置中心里的配置：列出、打印、保存、删除。只有 get 会把配置的内容打出来。"""
    action = args[0] if args else ""
    if action == "list" and len(args) == 1:
        items = listed("/v3/admin/cs/config/list", {"namespaceId": NAMESPACE, "groupName": GROUP, "dataId": ""},
                       admin_token)
        if items is None:
            die("列不出配置：Nacos 没有正常应答")
        for name in sorted(item.get("dataId") or "" for item in items):
            print(name)
        return
    if action not in ("get", "put", "delete") or len(args) != 2 or not args[1].strip():
        die(USAGE)
    data_id = args[1].strip()
    if action == "get":
        status, parsed = request("GET", "/v3/admin/cs/config", config_params(data_id), admin_token)
        content = (parsed.get("data") or {}).get("content") if succeeded(status, parsed) else None
        if content is None:
            die("没有 %s 这份配置（%s）" % (data_id, brief(status, parsed)))
        sys.stdout.write(content if content.endswith("\n") else content + "\n")
    elif action == "put":
        if sys.stdin.isatty():
            die("要存的内容从标准输入给，比如：docker compose run --rm -T nacos-init config put %s < %s" % (data_id, data_id))
        content = sys.stdin.buffer.read().decode("utf-8")
        if not content.strip():
            die("内容是空的，没有保存。要删掉这份配置用 config delete %s" % data_id)
        kind = CONFIG_TYPES.get(data_id.rpartition(".")[2].lower(), "text")
        status, parsed = request("POST", "/v3/admin/cs/config", dict(config_params(data_id), content=content, type=kind),
                                 admin_token)
        if not succeeded(status, parsed):
            die("保存 %s 没有成功：%s" % (data_id, brief(status, parsed)))
        print(">> 已保存 %s（%s，%d 字节）" % (data_id, kind, len(content.encode("utf-8"))))
    else:
        # 先看有没有：删一份不存在的配置，Nacos 也应答成功，名字打错了会看不出来
        status, parsed = request("GET", "/v3/admin/cs/config", config_params(data_id), admin_token)
        if not succeeded(status, parsed):
            die("没有 %s 这份配置（%s）" % (data_id, brief(status, parsed)))
        status, parsed = request("DELETE", "/v3/admin/cs/config?" + urllib.parse.urlencode(config_params(data_id)),
                                 None, admin_token)
        if not succeeded(status, parsed):
            die("删除 %s 没有成功：%s" % (data_id, brief(status, parsed)))
        print(">> 已删除 %s" % data_id)


def main():
    mode = sys.argv[1] if len(sys.argv) > 1 else "init"
    if mode not in ("init", "status", "config"):
        die(USAGE)
    secrets = read_secrets()
    username, password = secrets["NACOS_SERVICE_USERNAME"], secrets["NACOS_SERVICE_PASSWORD"]
    wait_ready()
    if mode == "status":
        show_status(username, password)
        return
    if mode == "config":
        admin_token = login(ADMIN_USER, secrets["NACOS_ADMIN_PASSWORD"], attempts=5)
        if not admin_token:
            die("用密钥卷里的口令登录不了 Nacos 管理员。" + RESET_HINT)
        config_command(admin_token, sys.argv[2:])
        return
    admin_token = ensure_admin(secrets["NACOS_ADMIN_PASSWORD"])
    ensure_service_account(admin_token, username, password)
    verify(username, password)


if __name__ == "__main__":
    try:
        main()
    except OSError as err:
        die("连不上 Nacos（%s）：%s" % (BASE, err))
