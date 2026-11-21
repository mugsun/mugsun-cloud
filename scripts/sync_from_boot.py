#!/usr/bin/env python3
"""从 mugsun-boot 同步业务代码到 mugsun-cloud。

mugsun-cloud 的业务代码和 mugsun-boot 同源：各模块 src/boot/ 下的文件由本脚本从 mugsun-boot 的
一个确定提交原样复制，不要手改。微服务版特有的代码写在各模块的 src/main/ 下。

用法（在仓库根目录执行）：

    python3 scripts/sync_from_boot.py                  # 从 ../mugsun-boot 的 HEAD 同步
    python3 scripts/sync_from_boot.py --ref <提交>      # 同步指定提交、分支或标签
    python3 scripts/sync_from_boot.py --boot <路径>     # mugsun-boot 不在 ../mugsun-boot 时
    python3 scripts/sync_from_boot.py --check          # 只比较不写文件，有差异时退出码 1
    python3 scripts/sync_from_boot.py --locked --check # 和上次同步的提交比较：src/boot 有没有被手改
    python3 scripts/sync_from_boot.py --explain <类名>  # 说明某个类为什么进了公共层
    python3 scripts/sync_from_boot.py --report         # 列出公共层和 system 各自用到的第三方包

划分规则在 scripts/sync-rules.json，说明见 docs/sync-from-boot.md。
只用 Python 标准库，兼容 Python 3.8+。
"""

import argparse
import collections
import fnmatch
import hashlib
import io
import json
import os
import re
import shutil
import subprocess
import sys
import tarfile

ROOT_PKG = "com.mugsun.boot"
REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RULES_FILE = os.path.join(REPO_ROOT, "scripts", "sync-rules.json")
LOCK_FILE = os.path.join(REPO_ROOT, "boot-sync.lock.json")
SYNC_DIR = "src/boot"
MARKER = "README.md"
MARKER_TEXT = """# 这个目录由脚本同步，不要手改

这里的文件从 mugsun-boot 原样复制，来源提交记在仓库根目录的 `boot-sync.lock.json`。

- 要改业务逻辑：改 mugsun-boot，再执行 `python3 scripts/sync_from_boot.py`。
- 要写微服务版特有的代码：写在本模块的 `src/main/` 下。
- 划分规则：`scripts/sync-rules.json`。
"""

IMPORT_RE = re.compile(r"^import\s+(static\s+)?([\w.]+?)(\.\*)?\s*;", re.M)
CONTROLLER_RE = re.compile(r"@(Rest)?Controller\b")


def die(msg):
    sys.stderr.write("错误：" + msg + "\n")
    sys.exit(2)


def git(boot, *args):
    out = subprocess.run(["git", "-C", boot] + list(args), stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if out.returncode != 0:
        die("git %s 失败：%s" % (" ".join(args), out.stderr.decode("utf-8", "replace").strip()))
    return out.stdout


def read_tree(boot, ref, prefixes):
    """读出某个提交里若干目录下的全部文件：{仓库内路径: 内容}。读的是提交，不是工作区。"""
    data = git(boot, "archive", "--format=tar", ref, *prefixes)
    files = {}
    with tarfile.open(fileobj=io.BytesIO(data)) as tar:
        for member in tar:
            if member.isfile():
                files[member.name] = tar.extractfile(member).read()
    return files


def strip_comments(text):
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    return re.sub(r"//[^\n]*", "", text)


class JavaIndex:
    """一个模块 src/main/java 下的类索引和类之间的引用关系（按源码里的 import 和同包引用推断）。"""

    def __init__(self, files, java_root):
        self.paths = {}
        self.text = {}
        self.by_pkg = collections.defaultdict(set)
        prefix = java_root.rstrip("/") + "/"
        for path, content in files.items():
            if not path.startswith(prefix) or not path.endswith(".java"):
                continue
            fqn = path[len(prefix):-len(".java")].replace("/", ".")
            self.paths[fqn] = path
            self.text[fqn] = content.decode("utf-8")
            pkg, _, name = fqn.rpartition(".")
            self.by_pkg[pkg].add(name)
        self.deps = {fqn: self._deps_of(fqn) for fqn in self.paths}

    def _resolve(self, name):
        while name and name not in self.paths:
            name = name.rpartition(".")[0]
        return name

    def _deps_of(self, fqn):
        pkg = fqn.rpartition(".")[0]
        body = strip_comments(self.text[fqn])
        deps = set()
        for match in IMPORT_RE.finditer(body):
            name, star = match.group(2), match.group(3)
            if star and name in self.by_pkg:
                for simple in self.by_pkg[name]:
                    if re.search(r"\b" + re.escape(simple) + r"\b", body):
                        deps.add(name + "." + simple)
                continue
            target = self._resolve(name)
            if target:
                deps.add(target)
        code = re.sub(r"^(import|package)[^\n]*\n", "", body, flags=re.M)
        for simple in self.by_pkg[pkg]:
            other = pkg + "." + simple
            if other != fqn and re.search(r"\b" + re.escape(simple) + r"\b", code):
                deps.add(other)
        for match in re.finditer(re.escape(ROOT_PKG) + r"\.[\w.]+", code):
            target = self._resolve(match.group(0))
            if target:
                deps.add(target)
        deps.discard(fqn)
        return deps

    def is_controller(self, fqn):
        return CONTROLLER_RE.search(strip_comments(self.text[fqn])) is not None

    def third_party(self, fqn):
        out = set()
        for match in IMPORT_RE.finditer(strip_comments(self.text[fqn])):
            name = match.group(2)
            if name.startswith(ROOT_PKG + ".") or name.startswith("java."):
                continue
            out.add(".".join(name.split(".")[:3]))
        return out


def expand(index, patterns):
    """规则里的类名写法：相对 com.mugsun.boot 的类名；`包.*` 表示包下的类，`包.**` 连子包一起；
    类名里带 `*`（如 `config.*SchemaRunner`）表示这个包下名字匹配的类。"""
    out = set()
    for pattern in patterns:
        full = ROOT_PKG + "." + pattern
        if pattern.endswith(".**"):
            base = full[:-3]
            found = {c for c in index.paths if c.startswith(base + ".")}
        elif pattern.endswith(".*"):
            base = full[:-2]
            found = {c for c in index.paths if c.rpartition(".")[0] == base}
        elif "*" in pattern:
            base, _, name = full.rpartition(".")
            found = {c for c in index.paths
                     if c.rpartition(".")[0] == base and fnmatch.fnmatchcase(c.rpartition(".")[2], name)}
        else:
            found = {full} if full in index.paths else set()
        if not found:
            die("sync-rules.json 里的 %s 在 mugsun-boot 里找不到对应的类，规则需要更新" % pattern)
        out |= found
    return out


def partition_core(index, rules):
    """把 core 的类分成公共层（各服务都带）和 system（只在 system 服务里）。返回 (common, system, parent)。"""
    skip = expand(index, rules.get("skip", []))
    seeds = set()
    for pkg in rules["commonPackages"]:
        seeds |= expand(index, [pkg + ".**"])
    seeds |= expand(index, rules.get("commonClasses", []))
    allowed_controllers = expand(index, rules.get("commonControllers", []))
    seeds |= allowed_controllers
    forced = expand(index, rules.get("systemClasses", []))
    forced |= {c for c in index.paths if index.is_controller(c) and c not in allowed_controllers}
    seeds -= forced
    seeds -= skip

    common, parent, stack = set(), {}, sorted(seeds)
    problems = []
    while stack:
        cls = stack.pop()
        if cls in common:
            continue
        common.add(cls)
        for dep in sorted(index.deps[cls]):
            if dep in forced or dep in skip:
                problems.append((cls, dep))
            elif dep not in common:
                if dep not in seeds:
                    parent.setdefault(dep, cls)
                stack.append(dep)
    if problems:
        lines = ["公共层的类引用了只能留在 system 的类（控制器、systemClasses 或 skip 里的类）："]
        for cls, dep in sorted(problems):
            lines.append("  %s -> %s" % (short(cls), short(dep)))
        lines.append("处理办法：把前者从公共层规则里拿掉，或者把后者加进 commonControllers / 从 systemClasses 里拿掉。")
        die("\n".join(lines))
    system = set(index.paths) - common - skip
    return common, system, parent


def short(fqn):
    return fqn[len(ROOT_PKG) + 1:] if fqn.startswith(ROOT_PKG + ".") else fqn


def module_core_refs(files, module, index):
    """可选模块的源码引用到的 core 类：{core 类: 引用它的一个模块文件}。"""
    refs = {}
    prefix = module["module"] + "/src/main/java/"
    for path in sorted(files):
        if not path.startswith(prefix) or not path.endswith(".java"):
            continue
        body = strip_comments(files[path].decode("utf-8"))
        found = set()
        for match in IMPORT_RE.finditer(body):
            name, star = match.group(2), match.group(3)
            if star and name in index.by_pkg:
                found |= {name + "." + simple for simple in index.by_pkg[name]
                          if re.search(r"\b" + re.escape(simple) + r"\b", body)}
        for match in re.finditer(re.escape(ROOT_PKG) + r"\.[\w.]+", body):
            target = index._resolve(match.group(0))
            if target:
                found.add(target)
        for cls in found:
            refs.setdefault(cls, path[len(prefix):])
    return refs


def split_factories(content, owner_of):
    """spring.factories 里登记的类分属不同模块，按类的归属拆成几份。"""
    text = content.decode("utf-8").replace("\\\n", "")
    result = collections.defaultdict(list)
    for line in text.splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        key, _, value = line.partition("=")
        by_owner = collections.defaultdict(list)
        for cls in [v.strip() for v in value.split(",") if v.strip()]:
            owner = owner_of(cls)
            if owner is None:
                die("spring.factories 里登记的 %s 不在同步范围内" % cls)
            by_owner[owner].append(cls)
        for owner, classes in by_owner.items():
            result[owner].append(key.strip() + "=\\\n" + ",\\\n".join(classes) + "\n")
    return {owner: "\n".join(blocks).encode("utf-8") for owner, blocks in result.items()}


def plan(rules, boot, ref):
    """算出每个目标模块 src/boot 下应该有哪些文件。返回 ({模块目录: {相对路径: 内容}}, 索引, 划分结果)。"""
    core = rules["core"]
    modules = rules.get("modules", [])
    prefixes = [core["module"] + "/src/main"] + [m["module"] + "/src/main" for m in modules]
    files = read_tree(boot, ref, prefixes)
    out = collections.defaultdict(dict)

    java_root = core["module"] + "/src/main/java"
    index = JavaIndex(files, java_root)
    common, system, parent = partition_core(index, core)
    targets = {"common": core["common"], "system": core["system"]}
    for cls, path in index.paths.items():
        rel = "java/" + path[len(java_root) + 1:]
        if cls in common:
            out[targets["common"]][rel] = files[path]
        elif cls in system:
            out[targets["system"]][rel] = files[path]

    for extra in rules.get("extras", []):
        if extra["module"] != core["module"]:
            die("extras 目前只支持从 %s 拷贝" % core["module"])
        wanted = expand(index, extra["classes"])
        for cls in sorted(wanted):
            outside = sorted(short(dep) for dep in index.deps[cls] if dep not in wanted)
            if outside:
                die("extras 里的 %s 还引用了没有一起拷贝的类：%s" % (short(cls), "、".join(outside)))
            out[extra["to"]]["java/" + index.paths[cls][len(java_root) + 1:]] = files[index.paths[cls]]

    def owner_of(cls):
        if cls in common:
            return "common"
        if cls in system:
            return "system"
        return None

    res_root = core["module"] + "/src/main/resources/"
    for path in sorted(files):
        if not path.startswith(res_root):
            continue
        rel = path[len(res_root):]
        for rule in core["resources"]:
            if not fnmatch.fnmatch(rel, rule["match"]):
                continue
            if rule["to"] == "split":
                for owner, content in split_factories(files[path], owner_of).items():
                    out[targets[owner]]["resources/" + rel] = content
            elif rule["to"] != "skip":
                out[targets[rule["to"]]]["resources/" + rule.get("as", rel)] = files[path]
            break
        else:
            die("资源文件 %s 没有匹配的规则，请在 sync-rules.json 的 core.resources 里补上" % rel)

    for module in modules:
        base = module["module"] + "/src/main/"
        refs = module_core_refs(files, module, index)
        outside = sorted(cls for cls in refs if cls not in common)
        if outside:
            lines = ["%s 引用了不在公共层的类，拆出去的服务里没有这些类，编译不过：" % module["module"]]
            for cls in outside:
                lines.append("  %s（引用处：%s）" % (short(cls), refs[cls]))
            lines.append("处理办法：把这些类加进 core.commonClasses，它们引用的类会一起进公共层。")
            die("\n".join(lines))
        for path in sorted(files):
            if path.startswith(base):
                out[module["target"]][path[len(base):]] = files[path]

    for target in out:
        out[target][MARKER] = MARKER_TEXT.encode("utf-8")
    return out, index, (common, system, parent)


def existing(target):
    base = os.path.join(REPO_ROOT, target, SYNC_DIR)
    found = {}
    for dirpath, _, names in os.walk(base):
        for name in names:
            full = os.path.join(dirpath, name)
            with open(full, "rb") as fh:
                found[os.path.relpath(full, base).replace(os.sep, "/")] = fh.read()
    return found


def check(planned):
    dirty = 0
    for target in sorted(planned):
        have, want = existing(target), planned[target]
        for rel in sorted(set(have) | set(want)):
            if rel not in want:
                print("多出：%s/%s/%s" % (target, SYNC_DIR, rel))
            elif rel not in have:
                print("缺少：%s/%s/%s" % (target, SYNC_DIR, rel))
            elif have[rel] != want[rel]:
                print("不同：%s/%s/%s" % (target, SYNC_DIR, rel))
            else:
                continue
            dirty += 1
    return dirty


def write(planned):
    for target in sorted(planned):
        base = os.path.join(REPO_ROOT, target, SYNC_DIR)
        if os.path.isdir(base):
            shutil.rmtree(base)
        for rel, content in planned[target].items():
            full = os.path.join(base, rel)
            os.makedirs(os.path.dirname(full), exist_ok=True)
            with open(full, "wb") as fh:
                fh.write(content)


def digest(planned):
    sha = hashlib.sha256()
    for target in sorted(planned):
        for rel in sorted(planned[target]):
            sha.update((target + "/" + rel + "\0").encode("utf-8"))
            sha.update(hashlib.sha256(planned[target][rel]).digest())
    return sha.hexdigest()


def explain(index, parts, name):
    common, system, parent = parts
    matches = [c for c in index.paths if c == name or short(c) == name or c.endswith("." + name)]
    if not matches:
        die("找不到类 %s" % name)
    for cls in sorted(matches):
        if cls in system:
            print("%s：在 system 服务里" % short(cls))
        elif cls not in common:
            print("%s：不同步（skip）" % short(cls))
        else:
            chain = [cls]
            while chain[-1] in parent:
                chain.append(parent[chain[-1]])
            if len(chain) == 1:
                print("%s：在公共层，规则直接指定" % short(cls))
            else:
                print("%s：在公共层，被引用链带入：%s" % (short(cls), " <- ".join(short(c) for c in chain)))


def report(index, parts):
    common, system, _ = parts
    for label, group in (("公共层", common), ("system", system)):
        used = collections.Counter()
        for cls in group:
            for pkg in index.third_party(cls):
                used[pkg] += 1
        print("== %s（%d 个类）用到的第三方包：" % (label, len(group)))
        print("  " + " ".join("%s:%d" % item for item in sorted(used.items())))


def main():
    parser = argparse.ArgumentParser(description="从 mugsun-boot 同步业务代码到 mugsun-cloud")
    parser.add_argument("--boot", default=os.path.join(REPO_ROOT, "..", "mugsun-boot"), help="mugsun-boot 仓库的位置")
    parser.add_argument("--ref", default="HEAD", help="要同步的提交、分支或标签，默认 HEAD")
    parser.add_argument("--locked", action="store_true", help="用 boot-sync.lock.json 里记的提交，不看 --ref")
    parser.add_argument("--check", action="store_true", help="只比较不写文件，有差异时退出码 1")
    parser.add_argument("--explain", metavar="类名", help="说明某个类分到了哪里、为什么")
    parser.add_argument("--report", action="store_true", help="列出公共层和 system 各自用到的第三方包")
    args = parser.parse_args()

    boot = os.path.abspath(args.boot)
    if not os.path.isdir(os.path.join(boot, ".git")):
        die("%s 不是 git 仓库，用 --boot 指定 mugsun-boot 的位置" % boot)
    with open(RULES_FILE, encoding="utf-8") as fh:
        rules = json.load(fh)
    ref = args.ref
    if args.locked:
        if not os.path.isfile(LOCK_FILE):
            die("还没有 boot-sync.lock.json，先不带 --locked 同步一次")
        with open(LOCK_FILE, encoding="utf-8") as fh:
            ref = json.load(fh)["commit"]
    commit = git(boot, "rev-parse", ref + "^{commit}").decode().strip()
    planned, index, parts = plan(rules, boot, commit)

    if args.explain:
        explain(index, parts, args.explain)
        return
    if args.report:
        report(index, parts)
        return
    if args.check:
        dirty = check(planned)
        print("和 mugsun-boot %s 比较：%s" % (commit[:12], "一致" if dirty == 0 else "%d 个文件有差异" % dirty))
        sys.exit(1 if dirty else 0)

    write(planned)
    lock = collections.OrderedDict()
    lock["source"] = rules["source"]
    lock["commit"] = commit
    lock["digest"] = digest(planned)
    lock["files"] = collections.OrderedDict((t, len(planned[t]) - 1) for t in sorted(planned))
    with open(LOCK_FILE, "w", encoding="utf-8") as fh:
        json.dump(lock, fh, ensure_ascii=False, indent=2)
        fh.write("\n")
    print("已从 mugsun-boot %s 同步：" % commit[:12])
    for target in sorted(planned):
        print("  %s/%s  %d 个文件" % (target, SYNC_DIR, len(planned[target]) - 1))


if __name__ == "__main__":
    main()
