#!/usr/bin/env python3
"""生成并检查 docs/boot-vs-cloud.md：单体版（mugsun-boot）和微服务版不一样的地方。

用法（在仓库根目录执行）：

    python3 scripts/boot_cloud_diff.py           # 重新生成清单
    python3 scripts/boot_cloud_diff.py --check   # 只检查不写文件，不合格时退出码 1

业务代码只有一份，从 mugsun-boot 同步到各模块的 src/boot。清单列的是没法只有一份的地方，分四类：

    一、同一件事两边各有一个实现。脚本自己找：src/main 里实现或继承了同步来的类型的类。
        每一处为什么另写、怎么保证一样，写在 scripts/boot-cloud-diff.json 的 seams 里，没写不通过。
    二、业务上两边做法不同。脚本找不出来，靠人写在 differs 里；脚本只检查里面写的类和测试都还在。
    三、单体版有、这边不同步的类：sync-rules.json 的 core.skip，原因写在 skipped 里。
    四、只有微服务版才有的类：其余手写的类，按模块列出，说明取类注释的第一句，没有类注释不通过。

只读本仓库里的文件，不需要 mugsun-boot 的检出。只用 Python 标准库，兼容 Python 3.8+。
"""

import collections
import io
import json
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from sync_from_boot import REPO_ROOT, ROOT_PKG, RULES_FILE, strip_comments  # noqa: E402

CLOUD_PKG = "com.mugsun.cloud"
DATA_NAME = "scripts/boot-cloud-diff.json"
DOC_NAME = "docs/boot-vs-cloud.md"
DATA_FILE = os.path.join(REPO_ROOT, *DATA_NAME.split("/"))
DOC_FILE = os.path.join(REPO_ROOT, *DOC_NAME.split("/"))
HOW = collections.OrderedDict([
    ("forward", "转给对方服务"),
    ("rewrite", "这边重写"),
])
MODIFIERS = r"(?:(?:public|protected|private|static|final|abstract|sealed|non-sealed|strictfp)\s+)*"
KINDS = r"(?:class|interface|record|enum|@interface)"
JAVADOC_RE = re.compile(r"/\*\*((?:(?!\*/).)*)\*/", re.S)


def read(path):
    with io.open(path, encoding="utf-8") as fh:
        return fh.read()


def scan(kind):
    """仓库里 <模块>/<kind>/ 下的类：{完整类名: [(模块目录, 文件路径)]}。kind 如 src/boot/java。"""
    found = collections.OrderedDict()
    marker = "/" + kind + "/"
    for base, dirs, names in os.walk(REPO_ROOT):
        dirs[:] = sorted(d for d in dirs if d not in ("target", ".git", "node_modules"))
        rel = os.path.relpath(base, REPO_ROOT).replace(os.sep, "/") + "/"
        if marker not in rel:
            continue
        module, _, pkg = rel.partition(marker)
        for name in sorted(names):
            if name.endswith(".java") and name != "package-info.java":
                fqn = pkg.replace("/", ".") + name[:-len(".java")]
                found.setdefault(fqn, []).append((module, os.path.join(base, name)))
    return found


def simple(fqn):
    return fqn.rpartition(".")[2]


def short(fqn):
    for prefix in (ROOT_PKG + ".", CLOUD_PKG + "."):
        if fqn.startswith(prefix):
            return fqn[len(prefix):]
    return fqn


def parents(path, name, known):
    """这个类写在 extends、implements 后面的类型里，属于 known 的那些（完整类名）。"""
    body = strip_comments(read(path))
    match = re.search(r"\b(?:class|interface|record|enum)\s+%s\b([^{]*)\{" % re.escape(name), body)
    if not match:
        return []
    header = match.group(1)
    while True:
        slim = re.sub(r"<[^<>]*>|\([^()]*\)", "", header)
        if slim == header:
            break
        header = slim
    match = re.search(r"\b(?:extends|implements)\b(.*)", header.split(" permits ")[0], re.S)
    if not match:
        return []
    pkg = re.search(r"^\s*package\s+([\w.]+)\s*;", body, re.M).group(1)
    imports, wildcards = {}, []
    for found in re.finditer(r"^\s*import\s+(?!static\b)([\w.]+?)(\.\*)?\s*;", body, re.M):
        if found.group(2):
            wildcards.append(found.group(1))
        else:
            imports[simple(found.group(1))] = found.group(1)
    out = []
    for token in re.findall(r"[A-Za-z_][\w.]*", match.group(1)):
        head = token.split(".")[0]
        for candidate in [token, imports.get(head), pkg + "." + head] + [w + "." + head for w in wildcards]:
            if candidate in known:
                if candidate not in out:
                    out.append(candidate)
                break
    return out


def is_concrete(path, name):
    body = strip_comments(read(path))
    match = re.search(r"(%s)(class|interface|record|enum)\s+%s\b" % (MODIFIERS, re.escape(name)), body)
    return bool(match) and match.group(2) != "interface" and "abstract" not in match.group(1)


def summary(path, name):
    """类注释的第一句，转成 Markdown 的写法；没有类注释时返回 None。"""
    text = read(path)
    decl = re.search(r"^%s%s\s+%s\b" % (MODIFIERS, KINDS, re.escape(name)), text, re.M)
    if not decl:
        return None
    doc = None
    for doc in JAVADOC_RE.finditer(text, 0, decl.start()):
        pass
    if doc is None or ";" in text[doc.end():decl.start()]:
        return None
    lines = [re.sub(r"^\s*\*\s?", "", line).strip() for line in doc.group(1).strip().split("\n")]
    text = re.split(r"<(?:p|ul|ol|pre|br)\b|\n@\w+", "\n".join(lines), maxsplit=1)[0].strip()
    text = re.sub(r"(?<=[^\x00-\x7f])\n(?=[^\x00-\x7f])", "", text).replace("\n", " ")
    text = re.sub(r"\{@(?:code|literal)\s+([^{}]*)\}", lambda m: "`%s`" % m.group(1).strip(), text)
    text = re.sub(r"\{@link(?:plain)?\s+([^{}\s]+)[^{}]*\}",
                  lambda m: "`%s`" % m.group(1).lstrip("#").replace("#", "."), text)
    text = re.sub(r"\s+", " ", re.sub(r"</?[a-zA-Z][^>]*>", "", text)).strip()
    end = text.find("。")
    return text[:end + 1] if end >= 0 else text.rstrip("：:；;，,. ") + "。"


def collect():
    """读代码和两份规则，返回 (清单要用的东西, 不合格的地方)。"""
    with io.open(RULES_FILE, encoding="utf-8") as fh:
        rules = json.load(fh)
    with io.open(DATA_FILE, encoding="utf-8") as fh:
        data = json.load(fh, object_pairs_hook=collections.OrderedDict)
    boot, hand = scan("src/boot/java"), scan("src/main/java")
    tests = set(simple(fqn) for fqn in scan("src/test/java"))
    problems = []

    known = set(boot)
    ups = dict((fqn, parents(places[0][1], simple(fqn), known)) for fqn, places in boot.items())
    concrete = dict((fqn, is_concrete(places[0][1], simple(fqn))) for fqn, places in boot.items())

    def implementations(root):
        """同步来的类里实现或继承了 root 的非抽象类，隔着几层也算。"""
        seen, todo = set(), [root]
        while todo:
            current = todo.pop()
            for fqn in ups:
                if current in ups[fqn] and fqn not in seen:
                    seen.add(fqn)
                    todo.append(fqn)
        return sorted(fqn for fqn in seen if concrete[fqn])

    module_of, notes = {}, {}
    for fqn, places in hand.items():
        if len(places) > 1:
            problems.append("%s 在 %s 里各手写了一份" % (fqn, "、".join(module for module, _ in places)))
        module_of[fqn] = places[0][0]
        notes[fqn] = summary(places[0][1], simple(fqn))
        if notes[fqn] is None:
            problems.append("%s 没有类注释：清单里要用它的第一句说明这个类是干什么的" % fqn)

    def need(entry, where, fields):
        missing = [field for field in fields if not entry.get(field)]
        if missing:
            problems.append("%s 缺 %s" % (where, "、".join(missing)))
        for name in entry.get("tests", []):
            if name not in tests:
                problems.append("%s 写的测试 %s 找不到" % (where, name))

    def missing_classes(entry, field, where, pool, what):
        for fqn in entry.get(field, []):
            if fqn not in pool:
                problems.append("%s 的 %s 里写的 %s 不是%s" % (where, field, fqn, what))

    seams = collections.OrderedDict()
    found = collections.OrderedDict((fqn, parents(hand[fqn][0][1], simple(fqn), known)) for fqn in sorted(hand))
    for fqn, roots in found.items():
        if roots and fqn not in data["seams"]:
            problems.append("漏记：%s 实现了同步来的 %s，%s 的 seams 里没有写它为什么另写一份、怎么保证和单体版一样"
                            % (fqn, "、".join(short(root) for root in roots), DATA_NAME))
    for fqn, entry in data["seams"].items():
        where = "seams 里的 %s" % fqn
        if not found.get(fqn):
            problems.append("%s 已经对不上了：这个类没了，或者不再实现同步来的类型。把这一条删掉" % where)
            continue
        need(entry, where, ["title", "how", "why", "same", "tests"])
        if entry.get("how") not in HOW:
            problems.append("%s 的 how 只能写 %s" % (where, "、".join(HOW)))
        missing_classes(entry, "related", where, hand, "手写的类")
        seams[fqn] = dict(entry, roots=[(root, implementations(root)) for root in found[fqn]])

    for number, entry in enumerate(data["differs"], 1):
        where = "differs 的第 %d 条" % number
        need(entry, where, ["title", "boot_side", "cloud_side", "why", "cloud", "tests"])
        missing_classes(entry, "cloud", where, hand, "手写的类")
        missing_classes(entry, "boot", where, boot, "同步来的类")

    skip = rules["core"].get("skip", [])
    for pattern in skip:
        if not data["skipped"].get(pattern):
            problems.append("漏记：sync-rules.json 的 core.skip 里有 %s，%s 的 skipped 里没有写为什么不同步" % (pattern, DATA_NAME))
    for pattern in data["skipped"]:
        if pattern not in skip:
            problems.append("skipped 里的 %s 已经不在 sync-rules.json 的 core.skip 里。把这一条删掉" % pattern)

    listed = set(seams)
    for entry in list(seams.values()) + list(data["differs"]):
        listed.update(entry.get("related", []) + entry.get("cloud", []))
    rest = collections.OrderedDict()
    for fqn in sorted(hand):
        if fqn not in listed:
            rest.setdefault(module_of[fqn], collections.OrderedDict()).setdefault(fqn.rpartition(".")[0], []).append(fqn)
    for module in sorted(set(module_of.values())):
        if not data["modules"].get(module):
            problems.append("漏记：%s 里有手写的类，%s 的 modules 里没有写这个模块是干什么的" % (module, DATA_NAME))
    for module in data["modules"]:
        if module not in set(module_of.values()):
            problems.append("modules 里的 %s 已经没有手写的类。把这一条删掉" % module)

    model = dict(data=data, seams=seams, rest=rest, notes=notes, module_of=module_of,
                 boot_module=dict((fqn, places[0][0]) for fqn, places in boot.items()), total=len(hand))
    return model, problems


def render(model):
    data, seams, rest, notes = model["data"], model["seams"], model["rest"], model["notes"]
    out = []

    def module(path):
        return path.rpartition("/")[2]

    def cloud(names):
        return "、".join("`%s`（%s）" % (short(fqn), module(model["module_of"][fqn])) for fqn in names)

    def synced(names):
        return "、".join("`%s`（同步在 %s）" % (short(fqn), module(model["boot_module"][fqn])) for fqn in names)

    def code(names):
        return "、".join("`%s`" % name for name in names)

    rest_count = sum(len(classes) for packages in rest.values() for classes in packages.values())
    out += [
        "# 单体版和微服务版不一样的地方",
        "",
        "> 这一页由 `scripts/boot_cloud_diff.py` 生成，不要手改。要改里面的说明：原因写在 `%s` 里，" % DATA_NAME
        + "第四节每个类后面的那句话是它类注释的第一句。改完重新生成。",
        "",
        "业务代码只有一份：在单体版（mugsun-boot）里维护，用脚本同步到这边各模块的 `src/boot/`，同步来的不手改"
        "（见 [从 mugsun-boot 同步代码](./sync-from-boot.md)）。两边的业务逻辑一样，首先靠的是这一点。",
        "",
        "这一页列的是**没法只有一份**的地方：",
        "",
        "| | 是什么 | 现在有多少 |",
        "| --- | --- | --- |",
        "| 一 | 同一件事，两边各有一个实现 | %d 处 |" % len(seams),
        "| 二 | 业务上两边做法不同 | %d 处 |" % len(data["differs"]),
        "| 三 | 单体版有、这边不带的类 | %d 个 |" % len(data["skipped"]),
        "| 四 | 只有微服务版才有的类 | %d 个 |" % rest_count,
        "",
        "微服务版手写的类一共 %d 个，每一个都在这一页的某一节里。" % model["total"]
        + "类名的写法：单体版的类省略开头的 `%s.`，微服务版手写的类省略 `%s.`。" % (ROOT_PKG, CLOUD_PKG),
        "",
        "## 这一页怎么保持是对的",
        "",
        "```bash",
        "python3 scripts/boot_cloud_diff.py           # 重新生成这一页",
        "python3 scripts/boot_cloud_diff.py --check   # 只检查，推送前要通过",
        "```",
        "",
        "下面几种情况 `--check` 不通过：",
        "",
        "- 漏记：手写的类实现或继承了同步来的类型，第一节却没有它。",
        "- 过期：记了的类或测试已经不存在，或者那个类不再实现同步来的类型。",
        "- `sync-rules.json` 里不同步的类没有写原因。",
        "- 手写的类没有类注释，或者有手写代码的模块没有写它是干什么的。",
        "- 这一页和按现在的代码重新生成出来的不一样。",
        "",
        "脚本查不出来的两样，靠改的人自己记：",
        "",
        "- 第二节「业务上两边做法不同」。一个手写的类有没有改变业务的做法，脚本判断不了。"
        "新写了这样的类，在 `%s` 的 `differs` 里加一条。" % DATA_NAME,
        "- 配置。微服务版改掉单体版默认值的地方都在 `mugsun-cloud-common` 的 `mugsun-cloud-defaults.yml` 里，"
        "每一项上面有注释写着原因，这一页不逐项列。",
        "",
        "## 两边的结果怎么保证一样",
        "",
        "1. 绝大部分业务代码两边是同一份文件。`python3 scripts/sync_from_boot.py --locked --check` 检查同步来的代码没有被手改。",
        "2. 第一节、第二节的每一处都有测试，名字列在各自下面。",
        "3. 同一组请求分别发给单体版和微服务版，逐个对比返回的结果：**还没有做**，排在各模块拆完以后。",
        "",
        "## 一、同一件事，两边各有一个实现（%d 处）" % len(seams),
        "",
        "单体版里各模块在同一个进程里，互相直接调用。微服务版里它们在不同的服务里，所以同一个接口这边另写了一个实现。"
        "接口本身在同步来的代码里，两边用的是同一个。做法有两种：",
        "",
        "- **转给对方服务**：这边的实现只负责把请求发给对方服务，对方服务里执行的还是单体版的那个实现，业务逻辑没有第二份。",
        "- **这边重写**：照单体版的规则另写了一遍，靠测试守着。单体版改了这条规则，这边要跟着改。",
        "",
    ]
    for number, (fqn, entry) in enumerate(seams.items(), 1):
        out += ["### %d. %s" % (number, entry["title"]), ""]
        for root, impls in entry["roots"]:
            out.append("- 接口：%s" % synced([root]))
            out.append("- 单体版的实现：%s" % (synced(impls) if impls else "没有找到"))
        out.append("- 微服务版的实现：%s" % cloud([fqn]))
        if entry.get("related"):
            out.append("- 一起用到的手写类：%s" % cloud(entry["related"]))
        out += [
            "- 为什么另写：%s" % entry["why"],
            "- 做法：%s" % HOW[entry["how"]],
            "- 怎么保证一样：%s" % entry["same"],
            "- 测试：%s" % code(entry["tests"]),
            "",
        ]

    out += [
        "## 二、业务上两边做法不同（%d 处）" % len(data["differs"]),
        "",
        "同一个功能，用户在两个版本里看到的不一样。每一处都是暂时没法做成一样，原因写在下面。",
        "",
    ]
    for number, entry in enumerate(data["differs"], 1):
        out += [
            "### %d. %s" % (number, entry["title"]),
            "",
            "- 单体版：%s" % entry["boot_side"],
            "- 微服务版：%s" % entry["cloud_side"],
            "- 为什么不一样：%s" % entry["why"],
            "- 微服务版的类：%s" % cloud(entry["cloud"]),
        ]
        if entry.get("boot"):
            out.append("- 单体版相关的类：%s" % synced(entry["boot"]))
        out += ["- 测试：%s" % code(entry["tests"]), ""]

    out += [
        "## 三、单体版有、这边不带的类（%d 个）" % len(data["skipped"]),
        "",
        "| 单体版的类 | 为什么不带，这边用什么代替 |",
        "| --- | --- |",
    ]
    out += ["| `%s` | %s |" % (pattern, reason) for pattern, reason in data["skipped"].items()]
    out += [
        "",
        "另外有两样也不同步：单体版的打包模块 `mugsun-boot-server`（只有 pom 和测试），和各模块的测试代码。业务测试在 mugsun-boot 里跑。",
        "",
        "## 四、只有微服务版才有的类（%d 个）" % rest_count,
        "",
        "单体版是一个进程，用不着这些。每个类后面的话是它类注释的第一句。",
        "",
    ]
    for path in data["modules"]:
        packages = rest.get(path)
        if not packages:
            continue
        out += ["### %s（%d 个）" % (module(path), sum(len(classes) for classes in packages.values())), "",
                data["modules"][path], ""]
        for package, classes in packages.items():
            out.append("- `%s`" % short(package))
            out += ["  - `%s`：%s" % (simple(fqn), notes[fqn]) for fqn in classes]
        out.append("")
    return "\n".join(out).rstrip("\n") + "\n"


def main():
    args = sys.argv[1:]
    if [arg for arg in args if arg != "--check"]:
        sys.stderr.write(__doc__)
        sys.exit(2)
    model, problems = collect()
    if problems:
        sys.stderr.write("单体版和微服务版不一样的地方，有 %d 处没记对：\n" % len(problems))
        for problem in problems:
            sys.stderr.write("  - " + problem + "\n")
        sys.exit(1)
    text = render(model)
    counts = "两边各有一个实现 %d 处，做法不同 %d 处，手写的类 %d 个" % (
        len(model["seams"]), len(model["data"]["differs"]), model["total"])
    if "--check" in args:
        if not os.path.exists(DOC_FILE) or read(DOC_FILE) != text:
            sys.stderr.write("%s 不是最新的，执行 python3 scripts/boot_cloud_diff.py 重新生成\n" % DOC_NAME)
            sys.exit(1)
        print("一致：%s" % counts)
        return
    with io.open(DOC_FILE, "w", encoding="utf-8") as fh:
        fh.write(text)
    print("已写 %s：%s" % (DOC_NAME, counts))


if __name__ == "__main__":
    main()
