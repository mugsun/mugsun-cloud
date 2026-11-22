#!/usr/bin/env python3
"""从 mugsun-boot 同步业务代码到 mugsun-cloud。

mugsun-cloud 的业务代码和 mugsun-boot 同源：各模块 src/boot/ 下的文件由本脚本从 mugsun-boot 的
一个确定提交原样复制，不要手改。微服务版特有的代码写在各模块的 src/main/ 下。
部署要用的几个脚本（生成密钥、建库）也从同一个提交原样复制，放在规则里 files 写的目录下。

用法（在仓库根目录执行）：

    python3 scripts/sync_from_boot.py                  # 从 ../mugsun-boot 的 HEAD 同步
    python3 scripts/sync_from_boot.py --ref <提交>      # 同步指定提交、分支或标签
    python3 scripts/sync_from_boot.py --boot <路径>     # mugsun-boot 不在 ../mugsun-boot 时
    python3 scripts/sync_from_boot.py --check          # 只比较不写文件，有差异时退出码 1
    python3 scripts/sync_from_boot.py --locked --check # 和上次同步的提交比较：同步来的文件有没有被手改
    python3 scripts/sync_from_boot.py --explain <类名>  # 说明某个类分到了哪里、为什么
    python3 scripts/sync_from_boot.py --report         # 列出公共层和各服务各自用到的第三方包

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
FILES_MARKER_TEXT = """# 这个目录由脚本同步，不要手改

这里的文件从 mugsun-boot 原样复制，来源提交记在仓库根目录的 `boot-sync.lock.json`。

- 要改这些文件：改 mugsun-boot，再执行 `python3 scripts/sync_from_boot.py`。
- 微服务版自己的文件不要放进这个目录，同步时整个目录会重写。
- 同步哪些文件：`scripts/sync-rules.json` 的 `files`。
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


def read_files(boot, ref, paths):
    """读出某个提交里的若干单个文件：{仓库内路径: (内容, 是否带可执行位)}。"""
    if not paths:
        return {}
    modes = {}
    for entry in git(boot, "ls-tree", "-z", ref, "--", *paths).decode("utf-8").split("\0"):
        if entry:
            meta, _, name = entry.partition("\t")
            modes[name] = meta.split(" ")[0]
    for path in paths:
        if path not in modes:
            die("sync-rules.json 的 files 里写的 %s 在 mugsun-boot 的这个提交里没有，规则需要更新" % path)
        if modes[path] not in ("100644", "100755"):
            die("sync-rules.json 的 files 里写的 %s 不是普通文件，目录和链接不能这样同步" % path)
    data = read_tree(boot, ref, paths)
    return {path: (data[path], modes[path] == "100755") for path in paths}


class Layout:
    """同步来的文件放在哪：模块的放在 <模块目录>/src/boot 下；单独同步的文件直接放在规则里 to 写的目录下。"""

    def __init__(self):
        self.dirs = set()
        self.execs = set()

    def base(self, target):
        return target if target in self.dirs else target + "/" + SYNC_DIR


def plan_files(rules, boot, ref, out, layout):
    """规则里 files 写的单个文件：原样复制到 to 写的目录，文件名不变，可执行位跟着走。"""
    taken = set(out)
    for group in rules.get("files", []):
        to = group["to"].strip("/")
        parts = to.split("/")
        if not to or os.path.isabs(group["to"]) or ".." in parts or "." in parts:
            die("sync-rules.json 的 files 里 to 要写仓库内的相对目录：%s" % group["to"])
        for other in sorted(taken):
            if to == other or to.startswith(other + "/") or other.startswith(to + "/"):
                die("sync-rules.json 的 files 里 to 写的 %s 和 %s 重叠，换一个单独的目录" % (to, other))
        taken.add(to)
        layout.dirs.add(to)
        paths = group["paths"]
        if not paths or len(set(paths)) != len(paths):
            die("sync-rules.json 的 files 里 %s 的 paths 不能为空，也不能有重复" % to)
        for path, (content, executable) in read_files(boot, ref, paths).items():
            name = path.rpartition("/")[2]
            if name == MARKER or name in out[to]:
                die("sync-rules.json 的 files 里 %s 复制到 %s 会重名：%s" % (path, to, name))
            out[to][name] = content
            if executable:
                layout.execs.add((to, name))
        out[to][MARKER] = FILES_MARKER_TEXT.encode("utf-8")


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


def split_services(index, rules, common, system):
    """core 里分给别的服务的类（规则 core.services）：从 system 里拿出来，放进规则里 target 写的模块。

    分出去的类只能引用公共层的类和一起分出去的类，留在 system 的类也不能再引用它们：拆开以后两边不在一个进程里。
    返回 ({目标模块: 类}, 留在 system 的类)。"""
    moved, owner = collections.OrderedDict(), {}
    for service in rules.get("services", []):
        target = service["target"]
        if target in moved or target in (rules["common"], rules["system"]):
            die("sync-rules.json 的 core.services 里 target 写的 %s 重复了，或者就是公共层、system 的模块" % target)
        classes = expand(index, service["classes"])
        for cls in sorted(classes):
            if cls in common:
                die("core.services 里的 %s 在公共层，各服务都带着它，不用再分给 %s" % (short(cls), target))
            if cls not in system:
                die("core.services 里的 %s 不同步（写在 skip 里），不能分给 %s" % (short(cls), target))
            if cls in owner:
                die("core.services 里的 %s 同时分给了 %s 和 %s" % (short(cls), owner[cls], target))
            owner[cls] = target
        moved[target] = classes
    rest = system - set(owner)

    def where(cls):
        if cls in owner:
            return "分给了 " + owner[cls]
        return "留在 system" if cls in rest else "不同步"

    problems = []
    for target, classes in moved.items():
        for cls in sorted(classes):
            for dep in sorted(index.deps[cls]):
                if dep not in common and dep not in classes:
                    problems.append("  %s（%s）-> %s（%s）" % (short(cls), where(cls), short(dep), where(dep)))
    for cls in sorted(rest):
        for dep in sorted(index.deps[cls]):
            if dep in owner:
                problems.append("  %s（%s）-> %s（%s）" % (short(cls), where(cls), short(dep), where(dep)))
    if problems:
        lines = ["分给别的服务的类和留在 system 的类互相引用了，拆开以后两边不在一个进程里，编译不过："]
        lines += problems
        lines.append("处理办法：两边都要用的类加进 core.commonClasses；只有那个服务用的类一起写进它的 classes；"
                     "是一次业务调用的话，先在 mugsun-boot 里把它抽成接口。")
        die("\n".join(lines))
    return moved, rest


def short(fqn):
    return fqn[len(ROOT_PKG) + 1:] if fqn.startswith(ROOT_PKG + ".") else fqn


def core_refs_in(source, index):
    """一份源码引用到的 core 类。source 是源码文本，index 是 core 的类索引。"""
    body = strip_comments(source)
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
    return found


def module_core_refs(files, module, index):
    """可选模块的源码引用到的 core 类：{core 类: 引用它的一个模块文件}。"""
    refs = {}
    prefix = module["module"] + "/src/main/java/"
    for path in sorted(files):
        if not path.startswith(prefix) or not path.endswith(".java"):
            continue
        for cls in core_refs_in(files[path].decode("utf-8"), index):
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
    """算出每个目标目录下应该有哪些文件。返回 ({目标: {相对路径: 内容}}, 索引, 划分结果, 存放位置)。
    划分结果是 (公共层的类, 留在 system 的类, 公共层的类是被谁带进来的, {目标模块: 分给它的类})。"""
    core = rules["core"]
    modules = rules.get("modules", [])
    extras = rules.get("extras", [])
    sources = [core["module"]] + [m["module"] for m in modules] + [e["module"] for e in extras]
    prefixes = [name + "/src/main" for name in sorted(set(sources))]
    files = read_tree(boot, ref, prefixes)
    out = collections.defaultdict(dict)

    java_root = core["module"] + "/src/main/java"
    index = JavaIndex(files, java_root)
    common, system, parent = partition_core(index, core)
    moved, system = split_services(index, core, common, system)
    targets = {"common": core["common"], "system": core["system"]}
    owner = {}
    for cls in common:
        owner[cls] = targets["common"]
    for cls in system:
        owner[cls] = targets["system"]
    for target, classes in moved.items():
        for cls in classes:
            owner[cls] = target
    for cls, path in index.paths.items():
        if cls in owner:
            out[owner[cls]]["java/" + path[len(java_root) + 1:]] = files[path]

    # 单独拷贝的类：可以来自 core，也可以来自可选模块。它引用的类要么一起拷，要么目标模块里本来就有
    # （目标是 system 时有公共层和 system 的类，是 core.services 里的服务时有公共层和分给它的类，
    # 是其它服务时只有公共层的类，网关不依赖公共层，什么都没有）。
    service_targets = {m["target"] for m in modules}
    module_indexes = {}
    for extra in extras:
        source, to = extra["module"], extra["to"]
        if to == targets["system"]:
            available = common | system
        elif to in moved:
            available = common | moved[to]
        elif to == targets["common"] or to in service_targets:
            available = common
        else:
            available = set()
        if source == core["module"]:
            src_index, src_root = index, java_root
        else:
            src_root = source + "/src/main/java"
            if source not in module_indexes:
                module_indexes[source] = JavaIndex(files, src_root)
            src_index = module_indexes[source]
        wanted = expand(src_index, extra["classes"])
        for cls in sorted(wanted):
            if source == core["module"]:
                outside = {dep for dep in src_index.deps[cls] if dep not in wanted and dep not in available}
            else:
                outside = {dep for dep in src_index.deps[cls] if dep not in wanted}
                outside |= {dep for dep in core_refs_in(src_index.text[cls], index) if dep not in available}
            if outside:
                die("extras 里的 %s 还引用了没有一起拷贝、目标模块 %s 里也没有的类：%s"
                    % (short(cls), to, "、".join(sorted(short(dep) for dep in outside))))
            rel = "java/" + src_index.paths[cls][len(src_root) + 1:]
            if rel in out[to]:
                die("extras 里的 %s 在目标模块 %s 里已经有了，不用单独拷贝" % (short(cls), to))
            out[to][rel] = files[src_index.paths[cls]]

    res_root = core["module"] + "/src/main/resources/"
    for path in sorted(files):
        if not path.startswith(res_root):
            continue
        rel = path[len(res_root):]
        for rule in core["resources"]:
            if not fnmatch.fnmatch(rel, rule["match"]):
                continue
            if rule["to"] == "split":
                for target, content in split_factories(files[path], owner.get).items():
                    out[target]["resources/" + rel] = content
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
    layout = Layout()
    plan_files(rules, boot, ref, out, layout)
    return out, index, (common, system, parent, moved), layout


def existing(base):
    base = os.path.join(REPO_ROOT, base)
    found = {}
    for dirpath, _, names in os.walk(base):
        for name in names:
            full = os.path.join(dirpath, name)
            with open(full, "rb") as fh:
                found[os.path.relpath(full, base).replace(os.sep, "/")] = fh.read()
    return found


def is_executable(base, rel):
    return bool(os.stat(os.path.join(REPO_ROOT, base, rel)).st_mode & 0o100)


def check(planned, layout):
    dirty = 0
    for target in sorted(planned):
        base = layout.base(target)
        have, want = existing(base), planned[target]
        for rel in sorted(set(have) | set(want)):
            if rel not in want:
                print("多出：%s/%s" % (base, rel))
            elif rel not in have:
                print("缺少：%s/%s" % (base, rel))
            elif have[rel] != want[rel]:
                print("不同：%s/%s" % (base, rel))
            elif (target in layout.dirs and os.name != "nt"
                  and is_executable(base, rel) != ((target, rel) in layout.execs)):
                print("可执行位不同：%s/%s" % (base, rel))
            else:
                continue
            dirty += 1
    return dirty


def write(planned, layout):
    # 单独同步文件的目录是规则里写的，写错了会把别的文件删掉：目录里有东西又没有同步标记时不动它
    for target in sorted(layout.dirs):
        base = os.path.join(REPO_ROOT, target)
        if os.path.isdir(base) and os.listdir(base) and not os.path.isfile(os.path.join(base, MARKER)):
            die("%s 里有不是同步来的文件（没有 %s 标记），换一个单独的目录，或者先把里面的文件挪走" % (target, MARKER))
    for target in sorted(planned):
        base = os.path.join(REPO_ROOT, layout.base(target))
        if os.path.isdir(base):
            shutil.rmtree(base)
        for rel, content in planned[target].items():
            full = os.path.join(base, rel)
            os.makedirs(os.path.dirname(full), exist_ok=True)
            with open(full, "wb") as fh:
                fh.write(content)
            if (target, rel) in layout.execs:
                os.chmod(full, 0o755)


def digest(planned, layout):
    sha = hashlib.sha256()
    for target in sorted(planned):
        for rel in sorted(planned[target]):
            sha.update((target + "/" + rel + "\0").encode("utf-8"))
            sha.update(hashlib.sha256(planned[target][rel]).digest())
            if (target, rel) in layout.execs:
                sha.update(b"+x")
    return sha.hexdigest()


def explain(index, parts, name):
    common, system, parent, moved = parts
    matches = [c for c in index.paths if c == name or short(c) == name or c.endswith("." + name)]
    if not matches:
        die("找不到类 %s" % name)
    for cls in sorted(matches):
        service = [target for target, classes in moved.items() if cls in classes]
        if service:
            print("%s：在 %s 里，规则 core.services 指定" % (short(cls), service[0]))
        elif cls in system:
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
    common, system, _, moved = parts
    groups = [("公共层", common), ("system", system)]
    groups += [(target.rpartition("/")[2], classes) for target, classes in moved.items()]
    for label, group in groups:
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
    parser.add_argument("--report", action="store_true", help="列出公共层和各服务各自用到的第三方包")
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
    planned, index, parts, layout = plan(rules, boot, commit)

    if args.explain:
        explain(index, parts, args.explain)
        return
    if args.report:
        report(index, parts)
        return
    if args.check:
        dirty = check(planned, layout)
        print("和 mugsun-boot %s 比较：%s" % (commit[:12], "一致" if dirty == 0 else "%d 个文件有差异" % dirty))
        sys.exit(1 if dirty else 0)

    write(planned, layout)
    lock = collections.OrderedDict()
    lock["source"] = rules["source"]
    lock["commit"] = commit
    lock["digest"] = digest(planned, layout)
    lock["files"] = collections.OrderedDict((t, len(planned[t]) - 1) for t in sorted(planned))
    with open(LOCK_FILE, "w", encoding="utf-8") as fh:
        json.dump(lock, fh, ensure_ascii=False, indent=2)
        fh.write("\n")
    print("已从 mugsun-boot %s 同步：" % commit[:12])
    for target in sorted(planned):
        print("  %s  %d 个文件" % (layout.base(target), len(planned[target]) - 1))


if __name__ == "__main__":
    main()
