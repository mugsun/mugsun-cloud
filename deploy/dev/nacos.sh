#!/usr/bin/env bash
# 本地开发用的 Nacos（注册中心）。
#   ./nacos.sh up       启动；首次启动时生成 .env（随机密钥，不进仓库）
#   ./nacos.sh status   看容器状态和已注册的服务实例
#   ./nacos.sh logs     看日志
#   ./nacos.sh down     停止并删除容器，注册数据一并清空
# 换端口：MUGSUN_NACOS_PORT=18848 ./nacos.sh up，服务启动时带 MUGSUN_NACOS_ADDR=127.0.0.1:18848。
set -eu

cd "$(dirname "$0")"

PORT="${MUGSUN_NACOS_PORT:-8848}"
export MUGSUN_NACOS_PORT="${PORT}"
# 客户端按「主端口 + 1000」找 gRPC 端口，所以不单独配
export MUGSUN_NACOS_GRPC_PORT="$((PORT + 1000))"
BASE="http://127.0.0.1:${PORT}/nacos"
SERVICES="mugsun-gateway mugsun-system mugsun-gis mugsun-track mugsun-ai mugsun-log"

random_hex() {
	od -An -N "${1}" -tx1 /dev/urandom | tr -d ' \n'
}

write_env() {
	if [ -f .env ]; then
		return
	fi
	(
		umask 077
		{
			echo "# 由 nacos.sh 生成，只给本机的开发环境用。不要提交，也不要拿到别的环境用"
			echo "NACOS_AUTH_TOKEN=$(head -c 48 /dev/urandom | base64 | tr -d '\n')"
			echo "NACOS_AUTH_IDENTITY_KEY=dev-$(random_hex 6)"
			echo "NACOS_AUTH_IDENTITY_VALUE=$(random_hex 24)"
		} > .env
	)
	echo "已生成 $(pwd)/.env"
}

instances_of() {
	curl -fsS -m 3 "${BASE}/v3/client/ns/instance/list?serviceName=${1}"
}

wait_ready() {
	i=0
	while [ "${i}" -lt 60 ]; do
		if instances_of mugsun-system > /dev/null 2>&1; then
			echo "Nacos 已就绪：127.0.0.1:${PORT}"
			return 0
		fi
		i=$((i + 1))
		sleep 2
	done
	echo "等了 120 秒 Nacos 还没就绪，执行 ./nacos.sh logs 看原因" >&2
	return 1
}

case "${1:-}" in
	up)
		write_env
		docker compose up -d
		wait_ready
		;;
	status)
		docker compose ps
		for name in ${SERVICES}; do
			found="$(instances_of "${name}" 2>/dev/null | grep -oE '"ip":"[^"]+","port":[0-9]+' | sed -E 's/"ip":"([^"]+)","port":([0-9]+)/\1:\2/' | tr '\n' ' ' || true)"
			echo "${name}: ${found:-没有实例}"
		done
		;;
	logs)
		docker compose logs --tail "${2:-100}" nacos
		;;
	down)
		docker compose down
		;;
	*)
		echo "用法：./nacos.sh up | status | logs | down" >&2
		exit 64
		;;
esac
