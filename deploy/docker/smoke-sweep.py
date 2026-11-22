"""每个接口：不登录进不去，网关把它转给了有这个接口的服务，没有任何角色的账号进得去的只有清单里那些。
由 smoke.sh 分三步调用，都在一次性容器里运行。

第一步 ask，在编排网络里。接口清单从各服务自己的接口文档里取（smoke.sh 只在这一步把文档打开），
清单里的每个接口都经管理后台和网关请求一次：

- 要登录的接口，不带登录令牌，按文档里的方法请求，必须返回 401；
- 不用登录的接口，只发一个普通的 OPTIONS：不执行业务代码，只为了让网关转发一次、记下一行访问日志。

哪些接口不用登录，读的是服务自己用的那份名单（LoginPublicPaths.java），不在这里另抄一份。
那份名单有一部分是按前缀算的，前缀下面新加一个接口就自动不用登录。所以算出来的「不用登录的接口」还要和
仓库里的清单 public-endpoints.txt 一模一样：多出一个、少了一个都不通过，要有人确认过、改了清单才行。
标准输出里除了给人看的 OK 行，还有给第二步的：每个请求一行「ASKED 方法 路径 可以转给的端口」，
以及开头、结尾两个标记请求的路径（BEGIN、END）。

第二步 routed，标准输入是第一步的输出加上网关的访问日志。网关每处理一个请求记一行，里面有它转给了哪个地址和端口，
端口对应服务：两个标记之间的每个请求，都要转给文档里有这个接口的服务。
转给了谁看网关自己记的，不从状态码推：状态码是服务按自己的规则给的，比如上传文件的请求没登录时
由过滤器先拒绝，轮不到看这个服务有没有这个接口。

第三步 roleless，在编排网络里。smoke-check.py 在前面建了一个没有任何角色的账号并登录，登录令牌留在交接用的卷里。
这一步带着它把要登录的接口按文档里的方法再请求一遍：返回 403 是被权限拦住了，别的都算进得去
（进去以后接口自己再校验什么，不在这里看）。进得去的接口必须和仓库里的清单 roleless-endpoints.txt 一模一样。
/system 下的写接口（POST、PUT、DELETE）没写权限码时另有一道兜底，默认拒绝；读接口和别的前缀下的接口没有这道兜底，
忘了写权限码，任何登录的人都进得去。
有了这份清单，这样的接口要有人看过、加进清单，自检才通过。

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
SERVICES = {"system": 8101, "gis": 8102, "track": 8103, "ai": 8104, "log": 8105, "report": 8106, "develop": 8107, "job": 8108, "resource": 8109, "flow": 8110, "desk": 8111}
ENTRY_HOST, ENTRY_PORT, ENTRY_PREFIX = "pc", 8080, "/api"
PUBLIC_PATHS_FILE = "/LoginPublicPaths.java"
PINNED_FILE = "/public-endpoints.txt"
ROLELESS_FILE = "/roleless-endpoints.txt"
ROLELESS_TOKEN_FILE = "/handoff/roleless-token"
# 第三步不请求的接口：它们的作用就是结束这次登录，请求了以后后面的接口都会变成「没登录」。
# 任何登录的人都能退出，按进得去算。/auth/logout 不用登录，不在第三步的范围里
ENDS_LOGIN = ("POST /app/auth/logout",)
METHODS = ("get", "head", "post", "put", "patch", "delete")
# 这几种返回等两秒再问一次：服务刚重新创建完，网关的实例列表可能还没跟上
RETRY = (0, 502, 503, 504)
# 网关访问日志里的一行，格式见网关的 AccessLogFilter
ACCESS = re.compile(r" ACCESS .*? method=(\S+) uri=(\S+) status=(\d+) .*? to=(\S+)")
SHOWN = 20


def fail(msg):
    print("FAIL " + msg, file=sys.stderr)
    sys.exit(1)


def listed(title, rows, shown=SHOWN):
    """标题加上一行一个的清单。shown 是最多列几个，None 表示全部列出。"""
    lines = ["%s %d 个：" % (title, len(rows))] + ["  " + row for row in rows[:shown]]
    if shown is not None and len(rows) > shown:
        lines.append("  ……还有 %d 个" % (len(rows) - shown))
    return "\n".join(lines)


def by_path(line):
    """「方法 路径」按路径排，路径相同的再按方法排。"""
    method, path = line.split()
    return path, method


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


def pinned(file):
    """读仓库里钉住的一份接口清单：一行一个「方法 路径」，# 开头的是注释。"""
    rows = set()
    with open(file, encoding="utf-8") as f:
        for number, raw in enumerate(f, 1):
            line = raw.strip()
            if not line or line.startswith("#"):
                continue
            parts = line.split()
            if len(parts) != 2 or parts[0].lower() not in METHODS or parts[0] != parts[0].upper() or not parts[1].startswith("/"):
                fail("%s 第 %d 行看不懂：%s（要写成「方法 路径」，比如 GET /ping）" % (file[1:], number, line))
            rows.add(" ".join(parts))
    return rows


def document(name, port):
    url = "http://%s:%d/v3/api-docs" % (name, port)
    try:
        with urllib.request.urlopen(url, timeout=60) as resp:
            return json.loads(resp.read())
    except (urllib.error.URLError, OSError, ValueError) as e:
        fail("取不到 %s 的接口文档（%s）：%s" % (name, url, e))


conn = None


def ask_once(method, path, content_type, body, token):
    global conn
    headers = {"Accept": "*/*"}
    if content_type:
        headers["Content-Type"] = content_type
    if token:
        headers["Authorization"] = token
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


def ask(method, path, content_type=None, body=None, token=None):
    """经管理后台和网关请求一次，返回状态码；连不上时是 0。token 是登录令牌，不给就是没登录。"""
    status = ask_once(method, path, content_type, body, token)
    if status in RETRY:
        time.sleep(2)
        status = ask_once(method, path, content_type, body, token)
    return status


def send(method, path, operation, token=None):
    """按文档里的方法和请求体类型发一次。token 是登录令牌，不给就是没登录。"""
    content = list((operation.get("requestBody") or {}).get("content") or {})
    if not content:
        return ask(method, path, token=token)
    if content[0].startswith("multipart/"):
        return ask(method, path, "multipart/form-data; boundary=x", b"--x--\r\n", token)
    if "json" in content[0]:
        return ask(method, path, content[0], b"{}", token)
    return ask(method, path, content[0], b"", token)


def marker(name):
    """发一个路径独一无二的请求，第二步靠它在网关的访问日志里圈出这次检查发的请求。"""
    path = "/smoke-sweep-%s-%s" % (name, os.urandom(6).hex())
    status = ask("GET", path)
    if status in RETRY:
        fail("经管理后台和网关发标记请求，返回了 %d" % status)
    return path


def setup():
    """读服务自己用的免登录名单，取四个服务的接口文档。"""
    expected = set(os.environ.get("APP_SERVICES", "").split())
    if expected != set(SERVICES):
        fail("smoke.sh 里的服务是 %s，smoke-sweep.py 里的是 %s，两边要一致" % (sorted(expected), sorted(SERVICES)))
    with open(PUBLIC_PATHS_FILE, encoding="utf-8") as f:
        exact, prefixes = public_paths(f.read())
    return exact, prefixes, {name: document(name, port) for name, port in SERVICES.items()}


def operations(name, doc, exact, prefixes):
    """一份接口文档里的每个接口：路径模板、请求时用的路径、是不是不用登录、大写的方法、文档里对它的描述。"""
    for template, item in sorted((doc.get("paths") or {}).items()):
        path = re.sub(r"\{[^}]+\}", "1", template)
        public = is_public(path, exact, prefixes)
        for method, operation in sorted(item.items()):
            if not isinstance(operation, dict) or "responses" not in operation:
                continue
            if method not in METHODS:
                fail("%s 的接口文档里 %s %s 用的方法这里不会检查，要在 smoke-sweep.py 里补上" % (name, method.upper(), template))
            yield template, path, public, method.upper(), operation


def do_ask():
    exact, prefixes, documents = setup()
    listed_public = pinned(PINNED_FILE)

    counts, asked, entered, wrong = {}, {}, [], []
    open_now = set()
    need_login = 0
    begin = marker("begin")
    for name, doc in documents.items():
        counts[name] = guarded = 0
        for template, path, public, method, operation in operations(name, doc, exact, prefixes):
            counts[name] += 1
            sent = "OPTIONS" if public else method
            asked.setdefault((sent, path), set()).add(SERVICES[name])
            if public:
                open_now.add("%s %s" % (method, template))
                ask(sent, path)
                continue
            guarded += 1
            status = send(sent, path, operation)
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
    # 这两种全部列出，写法和清单里的一样，可以整行拷进清单或者照着删
    extra, gone = sorted(open_now - listed_public, key=by_path), sorted(listed_public - open_now, key=by_path)
    if extra:
        problems.append(listed("不用登录的接口比清单（deploy/docker/public-endpoints.txt）多出", extra, None)
                        + "\n  先确认它们确实该对没登录的人开放，再把上面这几行加进清单")
    if gone:
        problems.append(listed("清单（deploy/docker/public-endpoints.txt）里现在不是「不用登录」的接口有", gone, None)
                        + "\n  它们被删掉了，或者现在要登录了：从清单里删掉上面这几行")
    if problems:
        fail("\n".join(problems))

    total = sum(counts.values())
    print("OK %d 个服务的接口文档里共 %d 个接口（%s）" % (len(counts), total, "、".join("%s %d" % pair for pair in counts.items())))
    print("OK 要登录的 %d 个接口不带登录令牌请求，全部返回 401" % need_login)
    print("OK 不用登录的 %d 个接口去掉重复是 %d 个，和清单 public-endpoints.txt 一模一样" % (total - need_login, len(open_now)))
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


def do_roleless():
    exact, prefixes, documents = setup()
    allowed = pinned(ROLELESS_FILE)
    try:
        with open(ROLELESS_TOKEN_FILE, encoding="utf-8") as f:
            token = f.read().strip()
    except OSError as e:
        fail("读不到没有角色的账号的登录令牌：%s。它由 smoke-check.py 建好账号、登录以后留下" % e)
    if not token:
        fail("没有角色的账号的登录令牌是空的")
    # 这个令牌是在重启 Nacos、把全部容器停掉再启动之前拿到的。登录状态存在 Redis 里，重启以后应该还在
    status = ask("GET", "/auth/info", token=token)
    if status != 200:
        fail("没有角色的账号在前面登录拿到的令牌，现在访问 /auth/info 返回 %d：全部容器重新启动以后，登录状态没有留住" % status)

    seen, denied, entered, last = set(), set(), set(), None
    for name, doc in documents.items():
        for template, path, public, method, operation in operations(name, doc, exact, prefixes):
            line = "%s %s" % (method, template)
            if public or line in seen:
                continue
            seen.add(line)
            if line in ENDS_LOGIN:
                entered.add(line)
                continue
            status = send(method, path, operation, token)
            if status == 403:
                denied.add(line)
            elif status == 401:
                fail("带着登录令牌请求 %s 返回了 401，这次登录中途失效了。它前面最后一个进得去的接口是 %s："
                     "要是那个接口会结束登录，把它加进 smoke-sweep.py 的 ENDS_LOGIN" % (line, last or "（还没有）"))
            elif status in RETRY:
                fail("带着登录令牌请求 %s 返回了 %d：请求没有到服务" % (line, status))
            else:
                entered.add(line)
                last = line
    if not denied:
        fail("没有任何角色的账号请求了 %d 个要登录的接口，一个 403 都没有：这个账号不是没有角色的，或者权限校验没有起作用" % len(seen))

    # 这两种全部列出，写法和清单里的一样，可以整行拷进清单或者照着删
    extra, gone = sorted(entered - allowed, key=by_path), sorted(allowed - entered, key=by_path)
    problems = []
    if extra:
        problems.append(listed("没有任何角色的账号进得去的接口比清单（deploy/docker/roleless-endpoints.txt）多出", extra, None)
                        + "\n  新接口忘了写权限码时会出现在这里。先确认它该对任何登录的人开放、给出的只是这个人自己的东西；"
                          "该要权限的去补权限码，确认过的再把上面这几行加进清单")
    if gone:
        problems.append(listed("清单（deploy/docker/roleless-endpoints.txt）里，没有任何角色的账号现在进不去的接口有", gone, None)
                        + "\n  它们被删掉了、改成不用登录了，或者现在要权限了：从清单里删掉上面这几行")
    if problems:
        fail("\n".join(problems))
    print("OK 没有任何角色的账号带着登录令牌把要登录的 %d 个接口请求了一遍：%d 个被权限拦住（403），"
          "进得去的 %d 个和清单 roleless-endpoints.txt 一模一样" % (len(seen), len(denied), len(entered)))


if sys.argv[1:] == ["ask"]:
    do_ask()
elif sys.argv[1:] == ["routed"]:
    do_routed()
elif sys.argv[1:] == ["roleless"]:
    do_roleless()
else:
    fail("用法：smoke-sweep.py ask | routed | roleless")
