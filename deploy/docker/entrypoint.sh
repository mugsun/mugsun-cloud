#!/bin/sh
# 容器入口：有密钥文件就先读进环境变量，再以 java 为 1 号进程启动。
#
# 口令和密钥有两种给法，可以混用：
#   - 直接用环境变量给，比如 Kubernetes 的 Secret；
#   - 写在一个文件里，每行「export 名字=值」，用 MUGSUN_ENV_FILE 写明文件的绝对路径。
#     文件里的值会盖掉同名的环境变量。这样口令不出现在容器的配置里，docker inspect 看不到；
#     同目录的 docker-compose.yml 用的是这种。
# 这个文件会被当作 shell 脚本执行，只能指向自己生成、别人改不了的文件。
set -eu
if [ -n "${MUGSUN_ENV_FILE:-}" ]; then
	case "${MUGSUN_ENV_FILE}" in
		/*) ;;
		*)
			echo "!! MUGSUN_ENV_FILE 要写绝对路径：${MUGSUN_ENV_FILE}" >&2
			exit 1
			;;
	esac
	if [ ! -r "${MUGSUN_ENV_FILE}" ]; then
		echo "!! MUGSUN_ENV_FILE 指的文件读不到：${MUGSUN_ENV_FILE}" >&2
		exit 1
	fi
	# shellcheck disable=SC1090
	. "${MUGSUN_ENV_FILE}"
fi
# JAVA_OPTS 里有多个参数，要按空格拆开，所以不加引号
# shellcheck disable=SC2086
exec java ${JAVA_OPTS:-} -jar /app/app.jar "$@"
