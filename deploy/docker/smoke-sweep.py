"""每个接口：不登录进不去，网关把它转给了有这个接口的服务。由 smoke.sh 分两步调用，都在一次性容器里运行。

第一步 ask，在编排网络里。接口清单从各服务自己的接口文档里取（smoke.sh 只在这一步把文档打开），
清单里的每个接口都经管理后台和网关请求一次：

- 要登录的接口，不带登录令牌，按文档里的方法请求，必须返回 401；
- 不用登录的接口，只发一个普通的 OPTIONS：不执行业务代码，只为了让网关转发一次、记下一行访问日志。

哪些接口不用登录，读的是服务自己用的那份名单（LoginPublicPaths.java），不在这里另抄一份。
标准输出里除了给人看的 OK 行，还有给第二步的：每个请求一行「ASKED 方法 路径 可以转给的端口」，
以及开头、结尾两个标记请求的路径（BEGIN、END）。

第二步 routed，标准输入是第一步的输出加上网关的访问日志。网关每处理一个请求记一行，里面有它转给了哪个地址和端口，
端口对应服务：两个标记之间的每个请求，都要转给文档里有这个接口的服务。
转给了谁看网关自己记的，不从状态码推：状态码是服务按自己的规则给的，比如上传文件的请求没登录时
由过滤器先拒绝，轮不到看这个服务有没有这个接口。

只用标准库，不装依赖。
"""
import http.client
import json
import os
import re
import sys
import time
import urllib.error
import urllib.request

# 拆出新服务时在这里补上名字和端口。忘了补会报错：第一步拿它和 smoke.sh 传进来的服务名单对照
SERVICES = {"system": 8101, "gis": 8102, "track": 8103, "ai": 8104}
ENTRY_HOST, ENTRY_PORT, ENTRY_PREFIX = "pc", 8080, "/api"
PUBLIC_PATHS_FILE = "/LoginPublicPaths.java"
METHODS = ("get", "head", "post", "put", "patch", "delete")
# 这几种返回等两秒再问一次：服务刚重新创建完，网关的实例列表可能还没跟上
RETRY = (0, 502, 503, 504)
# 网关访问日志里的一行，格式见网关的 AccessLogFilter
ACCESS = re.compile(r" ACCESS .*? method=(\S+) uri=(\S+) status=(\d+) .*? to=(\S+)")
SHOWN = 20


def fail(msg):
    print("FAIL " + msg, file=sys.stderr)
    sys.exit(1)


def listed(title, rows):
    lines = ["%s %d 个：" % (title, len(rows))] + ["  " + row for row in rows[:SHOWN]]
    if len(rows) > SHOWN:
        lines.append("  ……还有 %d 个" % (len(rows) - SHOWN))
    return "\n".join(lines)


def public_paths(source):
    """从 LoginPublicPaths.java 里取出两份名单：整条路径相同才算的，和按前缀算的。"""
    def literals(name, kind):
        found = re.search(name + r"\s*=\s*" + kind + r"\.of\((.*?)\);", source, re.S)
        if not found:
            fail("LoginPublicPaths.java 里没有找到 %s = %s.of(...)，这个文件的写法变了，要跟着改 smoke-sweep.py" % (name, kind))
        return re.findall(r'"([^"]+)"', re.sub(r"//[^\n]*", "", found.group(1)))

    exact, prefixes = set(literals("EXACT", "Set")), literals("PREFIXES", "List")
    # 两个一定在名单里的路径：读不到它们说明没读对，不能拿一份残缺的名单接着查
    if "/auth/login" not in exact or "/open" not in prefixes:
        fail("从 LoginPublicPaths.java 里读到的免登录名单不对：整条路径 %d 个，前缀 %d 个" % (len(exact), len(prefixes)))
    return exact, prefixes


def is_public(path, exact, prefixes):
    """和 LoginPublicPaths.isPublic 同一个算法：去掉结尾的斜杠，整条相同，或者等于某个前缀、以「前缀加斜杠」开头。"""
    if len(path) > 1 and path.endswith("/"):
        path = path[:-1]
    return path in exact or any(path == prefix or path.startswith(prefix + "/") for prefix in prefixes)


def document(name, port):
    url = "http://%s:%d/v3/api-docs" % (name, port)
    try:
        with urllib.request.urlopen(url, timeout=60) as resp:
            return json.loads(resp.read())
    except (urllib.error.URLError, OSError, ValueError) as e:
        fail("取不到 %s 的接口文档（%s）：%s" % (name, url, e))


conn = None


def ask_once(method, path, content_type, body):
    global conn
    headers = {"Accept": "*/*"}
    if content_type:
        headers["Content-Type"] = content_type
    # 一条连接用到一定次数 Nginx 会关掉它，换一条再发一次
    for _ in (1, 2):
        try:
            if conn is None:
                conn = http.client.HTTPConnection(ENTRY_HOST, ENTRY_PORT, timeout=30)
            conn.request(method, ENTRY_PREFIX + path, body=body, headers=headers)
            resp = conn.getresponse()
        except (http.client.HTTPException, OSError):
            conn = None
            continue
        try:
            resp.read()
        except (http.client.HTTPException, OSError):
            # 应答体没读完（比如一直不结束的流）：状态码已经拿到，这条连接不要了
            conn = None
        return resp.status
    return 0


def ask(method, path, content_type=None, body=None):
    """经管理后台和网关请求一次，返回状态码；连不上时是 0。"""
    status = ask_once(method, path, content_type, body)
    if status in RETRY:
        time.sleep(2)
        status = ask_once(method, path, content_type, body)
    return status


def without_login(method, path, operation):
    """按文档里的方法和请求体类型发一次，不带登录令牌。"""
    content = list((operation.get("requestBody") or {}).get("content") or {})
    if not content:
        return ask(method, path)
    if content[0].startswith("multipart/"):
        return ask(method, path, "multipart/form-data; boundary=x", b"--x--\r\n")
    if "json" in content[0]:
        return ask(method, path, content[0], b"{}")
    return ask(method, path, content[0], b"")


def marker(name):
    """发一个路径独一无二的请求，第二步靠它在网关的访问日志里圈出这次检查发的请求。"""
    path = "/smoke-sweep-%s-%s" % (name, os.urandom(6).hex())
    status = ask("GET", path)
    if status in RETRY:
        fail("经管理后台和网关发标记请求，返回了 %d" % status)
    return path


def do_ask():
    expected = set(os.environ.get("APP_SERVICES", "").split())
    if expected != set(SERVICES):
        fail("smoke.sh 里的服务是 %s，smoke-sweep.py 里的是 %s，两边要一致" % (sorted(expected), sorted(SERVICES)))
    with open(PUBLIC_PATHS_FILE, encoding="utf-8") as f:
        exact, prefixes = public_paths(f.read())
    documents = {name: document(name, port) for name, port in SERVICES.items()}

    counts, asked, entered, wrong = {}, {}, [], []
    need_login = 0
    begin = marker("begin")
    for name, doc in documents.items():
        counts[name] = guarded = 0
        for template, item in sorted((doc.get("paths") or {}).items()):
            path = re.sub(r"\{[^}]+\}", "1", template)
            public = is_public(path, exact, prefixes)
            for method, operation in sorted(item.items()):
                if not isinstance(operation, dict) or "responses" not in operation:
                    continue
                if method not in METHODS:
                    fail("%s 的接口文档里 %s %s 用的方法这里不会检查，要在 smoke-sweep.py 里补上" % (name, method.upper(), template))
                counts[name] += 1
                sent = "OPTIONS" if public else method.upper()
                asked.setdefault((sent, path), set()).add(SERVICES[name])
                if public:
                    ask(sent, path)
                    continue
                guarded += 1
                status = without_login(sent, path, operation)
                row = "%-6s %-6s %s → %d" % (name, sent, template, status)
                if 200 <= status < 400:
                    entered.append(row)
                elif status != 401:
                    wrong.append(row)
        if not guarded:
            fail("%s 的接口文档里没有一个要登录的接口（共 %d 个接口）：文档没取对，或者免登录名单读错了" % (name, counts[name]))
        need_login += guarded
    end = marker("end")

    problems = []
    if entered:
        problems.append(listed("不登录就进得去的接口", entered))
    if wrong:
        problems.append(listed("要登录的接口不登录请求，返回的不是 401，这样的", wrong))
    if problems:
        fail("\n".join(problems))

    total = sum(counts.values())
    print("OK %d 个服务的接口文档里共 %d 个接口（%s）" % (len(counts), total, "、".join("%s %d" % pair for pair in counts.items())))
    print("OK 要登录的 %d 个接口不带登录令牌请求，全部返回 401；另外 %d 个不用登录" % (need_login, total - need_login))
    print("BEGIN " + begin)
    for (sent, path), ports in sorted(asked.items()):
        print("ASKED %s %s %s" % (sent, path, ",".join(str(port) for port in sorted(ports))))
    print("END " + end)


def do_routed():
    names = {str(port): name for name, port in SERVICES.items()}
    asked, begin, end, lines = {}, None, None, []
    for raw in sys.stdin.buffer:
        line = raw.decode("utf-8", "replace")
        if line.startswith("ASKED "):
            _, sent, path, ports = line.split()
            asked[(sent, path)] = ports.split(",")
        elif line.startswith("BEGIN "):
            begin = line.split()[1]
        elif line.startswith("END "):
            end = line.split()[1]
        else:
            found = ACCESS.search(line)
            if found:
                lines.append(found.groups())
    if not asked or begin is None or end is None:
        fail("没有收到第一步发了哪些请求")
    uris = [uri for _, uri, _, _ in lines]
    if begin not in uris or end not in uris or uris.index(end) < uris.index(begin):
        fail("网关的访问日志里没有找到这次检查开头和结尾的两个标记请求（读到 %d 行访问日志）" % len(lines))
    # 同一个请求重发过的，以最后一次为准
    seen = {(method, uri): (status, to) for method, uri, status, to in lines[uris.index(begin) + 1:uris.index(end)]}

    missing, elsewhere = [], []
    for (sent, path), ports in sorted(asked.items()):
        if (sent, path) not in seen:
            missing.append("%-7s %s" % (sent, path))
            continue
        status, to = seen[(sent, path)]
        port = to.rsplit(":", 1)[-1]
        if port not in ports:
            went = "转给了 " + names.get(port, to) if port in names else "没有转给哪个服务（to=%s，网关返回 %s）" % (to, status)
            elsewhere.append("%-7s %s %s，文档里有这个接口的是 %s" % (sent, path, went, "、".join(names[p] for p in ports)))
    problems = []
    if elsewhere:
        problems.append(listed("网关没有转给有这个接口的服务，这样的请求", elsewhere))
    if missing:
        problems.append(listed("网关的访问日志里没有找到的请求", missing))
    if problems:
        fail("\n".join(problems))
    print("OK 这些接口共 %d 个不同的请求，网关都转给了文档里有这个接口的服务" % len(asked))


if sys.argv[1:] == ["ask"]:
    do_ask()
elif sys.argv[1:] == ["routed"]:
    do_routed()
else:
    fail("用法：smoke-sweep.py ask | routed")
