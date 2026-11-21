#!/bin/sh
# 首次启动生成整套口令和密钥，按使用方分开写进各自的密钥卷。每个文件 0600，属主是读它的那个用户：
#   /secrets/service/service.env    四个业务服务（uid 10001）：数据库和 Redis 口令、国密密钥、文件签名密钥、
#                                   管理员初始口令、服务之间调用的共享密钥、接入 Nacos 的账号
#   /secrets/gateway/gateway.env    网关（uid 10001）：只有接入 Nacos 的账号
#   /secrets/postgres/db.env        PostgreSQL（uid 999）：只有数据库口令
#   /secrets/redis/redis.conf       Redis（uid 999）：只有 requirepass
#   /secrets/nacos/nacos.env        Nacos（root）：签发令牌用的密钥和服务端身份标识
#   /secrets/nacos-admin/admin.env  只有初始化容器读（root）：Nacos 管理员口令，和上面那个接入账号的口令
# 六个卷互相隔离，每个容器只挂自己要用的那一个（只读），一个容器被攻破也拿不到别的容器的密钥。
# 文件齐全时原样复用：重启、重建容器，口令和密钥都不变。
# 清空重来只能用 docker compose down -v，连数据卷一起删：只删密钥卷、留着数据卷，新口令和库里的旧口令对不上。
# 口令不打印到日志（日志可能被转发到集中日志）。查看：
#   docker compose -f deploy/docker/docker-compose.yml run --rm init show         管理后台的 admin
#   docker compose -f deploy/docker/docker-compose.yml run --rm init show nacos   Nacos 控制台的管理员
# 生成国密密钥、判断 admin 改没改过口令，用的是从 mugsun-boot 同步来的 gen-keys.sh 和 init-pw-check.py。
set -eu
SERVICE=/secrets/service/service.env
GATEWAY=/secrets/gateway/gateway.env
PG=/secrets/postgres/db.env
REDIS=/secrets/redis/redis.conf
NACOS=/secrets/nacos/nacos.env
NACOS_ADMIN=/secrets/nacos-admin/admin.env
ALL="${SERVICE} ${GATEWAY} ${PG} ${REDIS} ${NACOS} ${NACOS_ADMIN}"
APP_UID=10001
PG_UID=999
REDIS_UID=999
ROOT_UID=0
NACOS_SERVICE_USER=mugsun-svc
COMPOSE="docker compose -f deploy/docker/docker-compose.yml"

fail() {
	echo "!! ${1}" >&2
	exit 1
}

# init show：admin 改过口令以后不再打印初始口令。
# 判断依据是库里 admin 当前的口令哈希还能不能用初始口令验证通过，不另存「已改密」标记。
# 连不上库时照常显示，但提示无法确认。密钥卷里的初始口令不清掉：各服务启动时的检查还要用它。
show_admin_password() {
	init_pw="$(sed -n 's/^export MUGSUN_INIT_PASSWORD=//p' "${SERVICE}")"
	db_pw="$(sed -n 's/^export JDBC_PASSWORD=//p' "${SERVICE}")"
	hash="$(PGPASSWORD="${db_pw}" PGCONNECT_TIMEOUT=5 psql -h "${MUGSUN_DB_HOST:-postgres}" -U "${MUGSUN_DB_USER:-mugsun}" \
		-d "${MUGSUN_DB_NAME:-mugsun}" -tAqX -v ON_ERROR_STOP=1 \
		-c "SELECT password FROM sys_user WHERE username = 'admin' AND tenant_id = '000000' ORDER BY id LIMIT 1" 2>/dev/null)" || hash=
	if [ -z "${hash}" ]; then
		echo "管理员口令：${init_pw}"
		echo "（无法连接数据库确认是否已改过口令；如果已经改过，这个初始口令已失效）"
		return 0
	fi
	rc=0
	INIT_PW="${init_pw}" HASH="${hash}" python3 /init-pw-check.py || rc=$?
	case "${rc}" in
		0) echo "管理员口令：${init_pw}" ;;
		1) echo "初始密码已失效：admin 已修改过口令。忘记口令请用其他管理员账号重置，或按文档找回" ;;
		*)
			echo "管理员口令：${init_pw}"
			echo "（无法识别库里的口令哈希格式，不能确认是否已改过口令）"
			;;
	esac
}

show_nacos_admin() {
	echo "Nacos 控制台账号：nacos"
	echo "Nacos 控制台口令：$(sed -n 's/^NACOS_ADMIN_PASSWORD=//p' "${NACOS_ADMIN}")"
}

have=0
for f in ${ALL}; do
	if [ -s "${f}" ]; then
		have=$((have + 1))
	fi
done

if [ "${have}" -eq 6 ]; then
	if [ "${1:-}" = show ]; then
		case "${2:-}" in
			"") show_admin_password ;;
			nacos) show_nacos_admin ;;
			*) fail "用法：init show 或 init show nacos" ;;
		esac
	else
		echo ">> 复用已有密钥（管理员口令：${COMPOSE} run --rm init show）"
	fi
	exit 0
fi
for f in ${ALL}; do
	if [ -e "${f}" ]; then
		fail "密钥卷不完整（上次生成中途失败，或者被删掉了一部分）。请 ${COMPOSE} down -v 后重来"
	fi
done
if [ "${1:-}" = show ]; then
	fail "还没有生成密钥，先 up 一次"
fi

umask 077
# set -e 管不到命令替换里管道前段的失败（openssl 挂了只会得到空串），所以生成后逐个核对长度
rnd() {
	openssl rand -base64 96 | tr -dc 'A-Za-z0-9' | cut -c1-"${1}"
}
need_len() {
	if [ "${#2}" -ne "${3}" ]; then
		fail "${1} 生成失败（长度 ${#2}，应为 ${3}）"
	fi
}

# 国密密钥两对、SM4 密钥、接口密钥、管理员初始口令、文件签名密钥：和单体版用同一个脚本生成
keys="$(/gen-keys.sh --all)" || fail "gen-keys.sh 失败"
key() {
	printf '%s\n' "${keys}" | sed -n "s/^${1}=//p"
}
need_len MUGSUN_SM4_KEY "$(key MUGSUN_SM4_KEY)" 32
need_len MUGSUN_API_KEY "$(key MUGSUN_API_KEY)" 32
need_len MUGSUN_SM2_PRIVATE_KEY "$(key MUGSUN_SM2_PRIVATE_KEY)" 64
need_len MUGSUN_SM2_PUBLIC_KEY "$(key MUGSUN_SM2_PUBLIC_KEY)" 130
need_len MUGSUN_SIGN_PRIVATE_KEY "$(key MUGSUN_SIGN_PRIVATE_KEY)" 64
need_len MUGSUN_SIGN_PUBLIC_KEY "$(key MUGSUN_SIGN_PUBLIC_KEY)" 130
need_len MUGSUN_INIT_PASSWORD "$(key MUGSUN_INIT_PASSWORD)" 19
need_len MUGSUN_FILE_SIGN_SECRET "$(key MUGSUN_FILE_SIGN_SECRET)" 48

# 口令只用字母和数字：要拼进 SQL、Redis 配置和 shell 的 export，不带特殊字符就不用转义
db_pw="$(rnd 32)"
need_len "数据库口令" "${db_pw}" 32
redis_pw="$(rnd 32)"
need_len "Redis 口令" "${redis_pw}" 32
# 服务之间调用的共享密钥：四个业务服务用同一个，网关不用
inner_secret="$(openssl rand -hex 32)"
need_len "服务间调用的密钥" "${inner_secret}" 64
# Nacos 签发令牌用的密钥要求是 Base64，原文不少于 32 字节
nacos_token="$(openssl rand -base64 48 | tr -d '\n')"
need_len "Nacos 令牌密钥" "${nacos_token}" 64
nacos_id_key="mugsun-$(rnd 12)"
need_len "Nacos 身份标识名" "${nacos_id_key}" 19
nacos_id_value="$(rnd 48)"
need_len "Nacos 身份标识值" "${nacos_id_value}" 48
nacos_admin_pw="$(rnd 32)"
need_len "Nacos 管理员口令" "${nacos_admin_pw}" 32
nacos_service_pw="$(rnd 32)"
need_len "Nacos 接入账号口令" "${nacos_service_pw}" 32

# 先写临时文件，全部写成以后再改名落位，避免留下半套密钥
printf 'requirepass %s\nappendonly yes\n' "${redis_pw}" > "${REDIS}.tmp"
printf 'export MUGSUN_DB_PASSWORD=%s\n' "${db_pw}" > "${PG}.tmp"
{
	echo "export JDBC_PASSWORD=${db_pw}"
	echo "export TRACK_JDBC_PASSWORD=${db_pw}"
	echo "export REDIS_PASSWORD=${redis_pw}"
	printf '%s\n' "${keys}" | sed 's/^/export /'
	echo "export MUGSUN_INNER_SECRET=${inner_secret}"
	echo "export MUGSUN_NACOS_USERNAME=${NACOS_SERVICE_USER}"
	echo "export MUGSUN_NACOS_PASSWORD=${nacos_service_pw}"
} > "${SERVICE}.tmp"
{
	echo "export MUGSUN_NACOS_USERNAME=${NACOS_SERVICE_USER}"
	echo "export MUGSUN_NACOS_PASSWORD=${nacos_service_pw}"
} > "${GATEWAY}.tmp"
{
	echo "export NACOS_AUTH_TOKEN=${nacos_token}"
	echo "export NACOS_AUTH_IDENTITY_KEY=${nacos_id_key}"
	echo "export NACOS_AUTH_IDENTITY_VALUE=${nacos_id_value}"
} > "${NACOS}.tmp"
{
	echo "NACOS_ADMIN_PASSWORD=${nacos_admin_pw}"
	echo "NACOS_SERVICE_USERNAME=${NACOS_SERVICE_USER}"
	echo "NACOS_SERVICE_PASSWORD=${nacos_service_pw}"
} > "${NACOS_ADMIN}.tmp"

# 文件 0600、目录 0700，属主是读它的那个用户
own() {
	chmod 600 "${1}.tmp"
	chown "${2}:${2}" "${1}.tmp" "$(dirname "${1}")"
	chmod 700 "$(dirname "${1}")"
}
own "${SERVICE}" "${APP_UID}"
own "${GATEWAY}" "${APP_UID}"
own "${PG}" "${PG_UID}"
own "${REDIS}" "${REDIS_UID}"
own "${NACOS}" "${ROOT_UID}"
own "${NACOS_ADMIN}" "${ROOT_UID}"
for f in ${ALL}; do
	mv "${f}.tmp" "${f}"
done

cat <<MSG
============================================================
 mugsun-cloud 首次启动：已生成管理员口令和全部密钥（按使用方分卷保存，0600）
   管理员账号：admin
   管理员口令不写入日志，请执行：
     ${COMPOSE} run --rm init show
   登录后请立即修改口令。
============================================================
MSG
