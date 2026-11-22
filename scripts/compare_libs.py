#!/usr/bin/env python3
"""比较各服务打出的包和单体版的包里第三方库的版本。

微服务版的业务代码和 mugsun-boot 是同一份，业务代码用到的第三方库也要和单体版一样：两边的包里都有的库，
版本应当相同。本脚本列出版本不同的库，有不同时退出码 1。

用法（在仓库根目录执行，两边都要先打好包）：

    python3 scripts/compare_libs.py                    # 单体版的包取 ../mugsun-boot/mugsun-boot-server/target/mugsun-boot.jar
    python3 scripts/compare_libs.py --boot-jar <路径>   # 单体版的包在别处时
    python3 scripts/compare_libs.py <某个服务的包> ...  # 只比较指定的包，默认比较网关和所有服务

单体版的包要用 boot-sync.lock.json 里记的那个提交打。说明见 docs/sync-from-boot.md 的「第三方库的版本」。
只用 Python 标准库，兼容 Python 3.8+。
"""

import argparse
import glob
import os
import re
import sys
import zipfile

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DEFAULT_BOOT_JAR = os.path.join(REPO_ROOT, "..", "mugsun-boot", "mugsun-boot-server", "target", "mugsun-boot.jar")
SERVICE_JARS = ("mugsun-cloud-gateway/target/*.jar", "mugsun-cloud-auth/target/*.jar", "mugsun-cloud-service/*/target/*.jar")
LIB_DIR = "BOOT-INF/lib/"
# 文件名是「库名-版本.jar」，版本从第一个以数字开头的段算起
NAME_RE = re.compile(r"^(.+?)-(\d[\w.+-]*)$")
# 只有注解、运行时不起作用的库。它们的版本跟着别的库走，两边不同不影响运行：只提示，不算差异。
# 要往这里加库，先确认那个库的包里只有注解定义
ANNOTATION_ONLY = frozenset(["annotations", "error_prone_annotations"])


def die(msg):
    sys.stderr.write("错误：" + msg + "\n")
    sys.exit(2)


def read_libs(path):
    """读出一个可执行包里的库，返回 {库名: 版本的集合}。包里没有库时返回空字典。

    同一个库可以有几个文件，比如 netty 按操作系统分的原生库，文件名在版本后面带着系统名，所以版本用集合记，
    系统名算在版本里。
    """
    libs = {}
    try:
        with zipfile.ZipFile(path) as jar:
            names = jar.namelist()
    except (OSError, zipfile.BadZipFile) as err:
        die("读不了 %s：%s" % (path, err))
    for name in names:
        if not (name.startswith(LIB_DIR) and name.endswith(".jar")):
            continue
        stem = name[len(LIB_DIR):-len(".jar")]
        match = NAME_RE.match(stem)
        if match:
            libs.setdefault(match.group(1), set()).add(match.group(2))
        else:
            libs.setdefault(stem, set()).add("")
    return libs


def show(versions):
    return " / ".join(sorted(versions))


def find_service_jars():
    found = []
    for pattern in SERVICE_JARS:
        found.extend(sorted(glob.glob(os.path.join(REPO_ROOT, pattern))))
    return found


def renamed_in_boot(lib, versions, boot):
    """同一个包里有两个文件名相同的库时，Spring Boot 会给文件名前面加上 groupId。

    比如 jaxb-core 在单体版包里是 org.glassfish.jaxb-jaxb-core 和 com.sun.xml.bind-jaxb-core 两个文件，
    而只带了其中一个的服务包里文件名还是 jaxb-core。这种情况算两边都有。
    """
    suffix = "-" + lib
    return any(name.endswith(suffix) and "." in name[:-len(suffix)] and boot[name] == versions for name in boot)


def compare(path, boot):
    """比较一个包，打印结果，返回版本不同的库的个数。"""
    libs = read_libs(path)
    label = os.path.basename(path)
    if not libs:
        print("== %s：不是可执行包，跳过" % label)
        return 0
    # 这个包里的每个文件，单体版包里都有同样版本的，就算一致。单体版多带几个系统的原生库不算差异
    changed = sorted(lib for lib in libs if lib in boot and not libs[lib] <= boot[lib])
    different = [lib for lib in changed if lib not in ANNOTATION_ONLY]
    annotations = [lib for lib in changed if lib in ANNOTATION_ONLY]
    only_here = sorted(lib for lib in libs if lib not in boot and not renamed_in_boot(lib, libs[lib], boot))

    print("== %s：%d 个库，其中 %d 个单体版的包里也有" % (label, len(libs), len(libs) - len(only_here)))
    if different:
        print("  版本和单体版不同（%d 个）：" % len(different))
        for lib in different:
            print("    %s  单体版 %s  这里 %s" % (lib, show(boot[lib]), show(libs[lib])))
    else:
        print("  版本和单体版一致")
    if annotations:
        print("  只有注解的库版本不同，不影响运行：" + "，".join(
            "%s %s（单体版 %s）" % (lib, show(libs[lib]), show(boot[lib])) for lib in annotations))
    if only_here:
        print("  只在这个包里（%d 个）：%s" % (len(only_here), " ".join("%s-%s" % (lib, show(libs[lib])) for lib in only_here)))
    return len(different)


def main():
    parser = argparse.ArgumentParser(description="比较各服务的包和单体版的包里第三方库的版本")
    parser.add_argument("--boot-jar", default=DEFAULT_BOOT_JAR, help="单体版打出的可执行包，默认 ../mugsun-boot/mugsun-boot-server/target/mugsun-boot.jar")
    parser.add_argument("jars", nargs="*", metavar="服务的包", help="要比较的包，默认是网关和所有服务的包")
    args = parser.parse_args()

    boot_jar = os.path.abspath(args.boot_jar)
    if not os.path.isfile(boot_jar):
        die("找不到单体版的包 %s。先在 mugsun-boot 里打包，或者用 --boot-jar 指定位置" % boot_jar)
    boot = read_libs(boot_jar)
    if not boot:
        die("%s 里没有 %s，它不是单体版的可执行包" % (boot_jar, LIB_DIR))

    jars = [os.path.abspath(jar) for jar in args.jars] or find_service_jars()
    if not jars:
        die("没找到服务的包。先在仓库根目录执行 ./mvnw -B package")
    for jar in jars:
        if not os.path.isfile(jar):
            die("找不到 %s" % jar)

    print("单体版的包：%s（%d 个库）" % (boot_jar, len(boot)))
    total = sum(compare(jar, boot) for jar in jars)
    print("结果：%s" % ("一致" if total == 0 else "有 %d 处版本和单体版不同" % total))
    sys.exit(1 if total else 0)


if __name__ == "__main__":
    main()
