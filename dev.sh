#!/usr/bin/env bash
# 本地开发：一条命令把微服务版整套起起来。只给本机开发用，生产部署见 docs/deployment.md。
#
#   ./dev.sh up [服务...]        起依赖、打包、启动服务。不写服务名就是全部
#   ./dev.sh down                停掉全部服务和依赖。数据库里的数据留着
#   ./dev.sh status              看依赖和每个服务起没起来
#   ./dev.sh logs <服务>         跟着看一个服务的日志，Ctrl-C 退出
#   ./dev.sh restart <服务...>   重启服务。代码改过会先重新打包
#   ./dev.sh stop <服务...>      停掉服务，比如要换到 IDE 里调试它
#   ./dev.sh deps                只起依赖：Nacos、PostgreSQL、Redis。全部服务都在 IDE 里启动时用
#   ./dev.sh build               重新打包
#   ./dev.sh core                重新安装 mugsun-core
#   ./dev.sh reset               停掉全部，并清空本地开发库
#
# 服务名：system auth gis track ai desk log report develop job resource flow gateway admin
#
# 需要 JDK 21 和 Docker。别的都有默认值，不用配：
# - Nacos、PostgreSQL、Redis 用 deploy/dev/docker-compose.yml 起，端口只绑本机。端口上已经有一个在运行的，就直接用它。
# - mugsun-core 的开发版不在 Maven Central 上，没装过就自动下载源码装到本机的 Maven 仓库，
#   用的提交和 deploy/docker/Dockerfile 里的 MUGSUN_CORE_REF 相同。
# - 服务之间互相调用用的密钥首次执行时随机生成，写在 config/application-local.yml 里（不进仓库）。
# - 管理员账号 admin，首次建库时的初始口令是 123456，首次登录会要求修改。
#   这个默认口令只在带 dev 启动时有效：生产环境不设 MUGSUN_INIT_PASSWORD 会拒绝启动。
#
# 可以改的环境变量：
#   MUGSUN_JAVA_OPTS      每个服务的 JVM 参数。默认的一组是为本机开发选的（启动快、占内存少），见下面 JAVA_OPTS 那一段
#   MUGSUN_SERVICE_IP     服务注册到 Nacos 的地址，默认 127.0.0.1
#   MUGSUN_NACOS_PORT     Nacos 的端口，默认 8848
#   MUGSUN_DB_PORT        PostgreSQL 的端口，默认 5432
#   MUGSUN_REDIS_PORT     Redis 的端口，默认 6379
#   MUGSUN_DEV_WAIT       等一批服务启动最多等多少秒，默认 300
#   MUGSUN_CORE_REPO      mugsun-core 的仓库地址，默认 https://github.com/mugsun/mugsun-core.git
#
# 进程号和日志放在 .dev/ 下（不进仓库）。兼容 macOS 自带的 bash 3.2。

set -u

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
cd "${ROOT}" || exit 1

DEV_DIR="${ROOT}/.dev"
RUN_DIR="${DEV_DIR}/run"
LOG_DIR="${DEV_DIR}/logs"
LOCAL_CONFIG="config/application-local.yml"

NACOS_PORT="${MUGSUN_NACOS_PORT:-8848}"
DB_PORT="${MUGSUN_DB_PORT:-5432}"
REDIS_PORT="${MUGSUN_REDIS_PORT:-6379}"
# 每个服务的 JVM 参数。本机要同时跑 14 个 Java 进程，默认这一组图的是启动快、占内存少：
#   -Xms32m -Xmx512m          堆从 32 MB 起步，用多少要多少，最多 512 MB
#   -XX:+UseSerialGC          用最简单的垃圾回收器，它自己占的内存最少
#   -XX:TieredStopAtLevel=1   只做第一层的即时编译：启动快、占内存少，代价是长时间运行时的最高速度低一些
#   -XX:MinHeapFreeRatio=10 -XX:MaxHeapFreeRatio=30
#                             垃圾回收以后，堆里空着的部分超过三成就把堆缩小，不一直占着
# 实测 14 个进程全起：只写 -Xmx512m 时要 90～100 秒、占 6.0 GB；用这一组是 62 秒、占 4.7 GB。
# 这一组只适合本机开发，生产不要照搬
JAVA_OPTS="${MUGSUN_JAVA_OPTS:--Xms32m -Xmx512m -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -XX:MinHeapFreeRatio=10 -XX:MaxHeapFreeRatio=30}"
WAIT_SECONDS="${MUGSUN_DEV_WAIT:-300}"
CORE_REPO="${MUGSUN_CORE_REPO:-https://github.com/mugsun/mugsun-core.git}"

# 名字 模块目录 端口 健康检查地址 带不带 dev。顺序就是启动顺序：system 最先，库表由它来建。
# 网关和监控中心没有分环境的配置，不带 dev。新加服务时在这里补一行
SERVICE_TABLE="system mugsun-cloud-service/mugsun-cloud-system 8101 /ping dev
auth mugsun-cloud-auth 8100 /ping dev
gis mugsun-cloud-service/mugsun-cloud-gis 8102 /ping dev
track mugsun-cloud-service/mugsun-cloud-track 8103 /ping dev
ai mugsun-cloud-service/mugsun-cloud-ai 8104 /ping dev
desk mugsun-cloud-service/mugsun-cloud-desk 8111 /ping dev
log mugsun-cloud-ops/mugsun-cloud-log 8105 /ping dev
report mugsun-cloud-ops/mugsun-cloud-report 8106 /ping dev
develop mugsun-cloud-ops/mugsun-cloud-develop 8107 /ping dev
job mugsun-cloud-ops/mugsun-cloud-job 8108 /ping dev
resource mugsun-cloud-ops/mugsun-cloud-resource 8109 /ping dev
flow mugsun-cloud-ops/mugsun-cloud-flow 8110 /ping dev
gateway mugsun-cloud-gateway 8080 /_gateway/health -
admin mugsun-cloud-ops/mugsun-cloud-admin 8200 /actuator/health -"

ALL_SERVICES="$(echo "${SERVICE_TABLE}" | awk '{ printf "%s ", $1 }')"

say() {
	printf '%s\n' "$*"
}

die() {
	printf '!! %s\n' "$*" >&2
	exit 1
}

usage() {
	sed -n '2,15p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
}

# 服务表里某个服务的第几列
field() {
	echo "${SERVICE_TABLE}" | awk -v n="$1" -v c="$2" '$1 == n { print $c }'
}

check_name() {
	[ -n "$(field "$1" 3)" ] || die "没有叫 $1 的服务。服务名：${ALL_SERVICES}"
}

jar_of() {
	local dir
	dir="$(field "$1" 2)"
	echo "${dir}/target/${dir##*/}.jar"
}

health_url() {
	echo "http://127.0.0.1:$(field "$1" 3)$(field "$1" 4)"
}

port_open() {
	(exec 3<> "/dev/tcp/127.0.0.1/$1") 2> /dev/null
}

http_ok() {
	curl -fsS -o /dev/null -m 3 "$1" 2> /dev/null
}

compose() {
	(cd "${ROOT}/deploy/dev" && docker compose "$@")
}

need_java() {
	local version
	command -v java > /dev/null 2>&1 || die "没有找到 java。需要 JDK 21"
	version="$(java -XshowSettings:properties -version 2>&1 | awk -F'= ' '/java.specification.version/ { print $2 }')"
	version="${version#1.}"
	case "${version}" in
		'' | *[!0-9]*) die "看不出 JDK 的版本。需要 JDK 21" ;;
	esac
	[ "${version}" -ge 21 ] || die "需要 JDK 21，现在是 ${version}"
}

need_curl() {
	command -v curl > /dev/null 2>&1 || die "没有找到 curl"
}

need_docker() {
	command -v docker > /dev/null 2>&1 || die "没有找到 docker。Nacos、PostgreSQL 和 Redis 是用 Docker 起的"
	docker compose version > /dev/null 2>&1 || die "docker compose 用不了，需要 Compose v2"
	docker info > /dev/null 2>&1 || die "Docker 没有启动。先打开 Docker 再执行"
}

# ---------- 依赖：Nacos、PostgreSQL、Redis ----------

container_running() {
	[ -n "$(compose ps -q --status running "$1" 2> /dev/null)" ]
}

deps_up() {
	local want=""
	need_docker
	deploy/dev/nacos.sh env || die "生成 deploy/dev/.env 失败"
	if container_running nacos; then
		say "Nacos 已经在运行"
	elif port_open "${NACOS_PORT}"; then
		say "${NACOS_PORT} 端口上已经有一个 Nacos，直接用它"
	else
		say "启动 Nacos ..."
		MUGSUN_NACOS_PORT="${NACOS_PORT}" deploy/dev/nacos.sh up || die "Nacos 没有起来，执行 deploy/dev/nacos.sh logs 看原因"
	fi
	if container_running postgres; then
		say "PostgreSQL 已经在运行"
	elif port_open "${DB_PORT}"; then
		say "${DB_PORT} 端口上已经有一个 PostgreSQL，直接用它。不想用它就设 MUGSUN_DB_PORT 换一个端口"
	else
		want="${want} postgres"
	fi
	if container_running redis; then
		say "Redis 已经在运行"
	elif port_open "${REDIS_PORT}"; then
		say "${REDIS_PORT} 端口上已经有一个 Redis，直接用它。不想用它就设 MUGSUN_REDIS_PORT 换一个端口"
	else
		want="${want} redis"
	fi
	if [ -n "${want}" ]; then
		say "启动${want} ...（第一次要下载镜像）"
		# shellcheck disable=SC2086
		compose up -d --wait ${want} || die "${want} 没有起来，执行 docker compose -f deploy/dev/docker-compose.yml logs 看原因"
	fi
}

deps_down() {
	command -v docker > /dev/null 2>&1 || return 0
	docker info > /dev/null 2>&1 || return 0
	[ -f deploy/dev/.env ] || return 0
	compose down "$@"
}

# ---------- 本机的密钥 ----------

# 各服务的配置里写的是 ${MUGSUN_INNER_SECRET:}，这个名字既可以是环境变量，也可以是配置文件里的一项。
# 写进 config/application-local.yml，脚本启动的和 IDE 里启动的服务（工作目录是仓库根目录时）读到的是同一个值
ensure_secret() {
	local secret has_content=0
	if [ -n "${MUGSUN_INNER_SECRET:-}" ]; then
		return 0
	fi
	if [ -f "${LOCAL_CONFIG}" ] && grep -q '^MUGSUN_INNER_SECRET:' "${LOCAL_CONFIG}"; then
		return 0
	fi
	secret="$(od -An -N 32 -tx1 /dev/urandom | tr -d ' \n')"
	mkdir -p "$(dirname "${LOCAL_CONFIG}")"
	if [ -s "${LOCAL_CONFIG}" ]; then
		has_content=1
	fi
	(
		umask 077
		{
			if [ "${has_content}" = 1 ]; then
				echo
			fi
			echo "# 由 ./dev.sh 写入：服务之间互相调用用的密钥，各服务从这里读到同一个值。只给本机开发用，不进仓库"
			echo "MUGSUN_INNER_SECRET: \"${secret}\""
		} >> "${LOCAL_CONFIG}"
	)
	say "已生成服务之间调用用的密钥，写在 ${LOCAL_CONFIG}"
}

# ---------- mugsun-core ----------

core_version() {
	sed -n 's:.*<mugsun\.version>\(.*\)</mugsun\.version>.*:\1:p' pom.xml | head -1
}

core_ref() {
	sed -n 's/^ARG MUGSUN_CORE_REF=//p' deploy/docker/Dockerfile | head -1
}

# 本机 Maven 仓库的位置。改过 settings.xml 的人不在默认位置，所以问一次 Maven，记在 .dev/ 下
local_repo() {
	local path
	if [ -f "${DEV_DIR}/m2-repo" ]; then
		cat "${DEV_DIR}/m2-repo"
		return 0
	fi
	path="$(./mvnw -q -B -ntp help:evaluate -Dexpression=settings.localRepository -DforceStdout 2> /dev/null | tail -1)"
	case "${path}" in
		/*) ;;
		*) path="${HOME}/.m2/repository" ;;
	esac
	echo "${path}" > "${DEV_DIR}/m2-repo"
	echo "${path}"
}

# 第一个参数是 force 时不管装没装过都重装
ensure_core() {
	local version ref dir marker src
	version="$(core_version)"
	case "${version}" in
		*-SNAPSHOT) ;;
		# 正式版在 Maven Central 上，Maven 自己会下载
		*) return 0 ;;
	esac
	ref="$(core_ref)"
	[ -n "${ref}" ] || die "deploy/docker/Dockerfile 里没有找到 MUGSUN_CORE_REF"
	dir="$(local_repo)/com/mugsun/mugsun-bom/${version}"
	# 本脚本装的会留下这个记号，里面是提交号。自己手工装的没有记号，脚本不去动它
	marker="${dir}/.installed-by-dev-sh"
	if [ "${1:-}" != force ] && [ -f "${dir}/mugsun-bom-${version}.pom" ]; then
		if [ ! -f "${marker}" ] || [ "$(cat "${marker}")" = "${ref}" ]; then
			return 0
		fi
		say "本机的 mugsun-core ${version} 是旧提交，换成现在要的 ${ref:0:7} ..."
	else
		say "安装 mugsun-core ${version}（提交 ${ref:0:7}）..."
	fi
	command -v git > /dev/null 2>&1 || die "没有找到 git，下载不了 mugsun-core 的源码"
	src="${DEV_DIR}/mugsun-core"
	if [ ! -d "${src}/.git" ]; then
		git init -q "${src}" || die "建不了目录 ${src}"
		git -C "${src}" remote add origin "${CORE_REPO}"
	fi
	git -C "${src}" remote set-url origin "${CORE_REPO}"
	if ! git -C "${src}" fetch -q --depth 1 origin "${ref}" || ! git -C "${src}" checkout -q --detach FETCH_HEAD; then
		die "下载 mugsun-core 失败：${CORE_REPO} 的提交 ${ref}"
	fi
	if ! ./mvnw -B -ntp -f "${src}/pom.xml" clean install -DskipTests > "${LOG_DIR}/core-install.log" 2>&1; then
		grep -E '^\[ERROR\]' "${LOG_DIR}/core-install.log" | head -20 >&2
		die "安装 mugsun-core 失败，完整日志在 .dev/logs/core-install.log"
	fi
	echo "${ref}" > "${marker}"
}

# ---------- 打包 ----------

need_build() {
	local name changed
	[ -f "${DEV_DIR}/build.stamp" ] || return 0
	for name in ${ALL_SERVICES}; do
		[ -f "$(jar_of "${name}")" ] || return 0
	done
	changed="$(find . \( -name target -o -name .dev -o -name .git -o -name node_modules \) -prune -o -type f \
		\( -path '*/src/*' -o -name pom.xml \) -newer "${DEV_DIR}/build.stamp" -print 2> /dev/null | head -1)"
	[ -n "${changed}" ]
}

# 带 clean：删掉或改名的文件（比如数据库迁移脚本）不会留在 target 里，改过版本的库也一定换进包里
do_build() {
	if [ -f "${DEV_DIR}/build.stamp" ]; then
		say "重新打包 ...（日志在 .dev/logs/build.log）"
	else
		say "打包 ...（第一次要下载依赖，可能要十来分钟；日志在 .dev/logs/build.log）"
	fi
	: > "${DEV_DIR}/build.stamp.new"
	if ! ./mvnw -B -ntp clean package -DskipTests > "${LOG_DIR}/build.log" 2>&1; then
		grep -E '^\[ERROR\]' "${LOG_DIR}/build.log" | head -20 >&2
		die "打包失败，完整日志在 .dev/logs/build.log。报 com.mugsun 的类或方法找不到时，执行 ./dev.sh core 重装 mugsun-core 再试"
	fi
	mv "${DEV_DIR}/build.stamp.new" "${DEV_DIR}/build.stamp"
}

# 上次打包以后代码改过才重新打包。正在运行的服务不受影响，重启后才用新包
build_if_needed() {
	ensure_core
	if need_build; then
		do_build
	else
		say "代码没有变，不用重新打包"
	fi
}

# ---------- 启动和停止 ----------

# 本脚本启动的、还活着的进程号；没有就什么都不输出。进程号会被系统重用，所以还要核对命令行里是这个服务的包
pid_of() {
	local file pid jar
	file="${RUN_DIR}/$1.pid"
	[ -f "${file}" ] || return 0
	pid="$(cat "${file}")"
	case "${pid}" in
		'' | *[!0-9]*) return 0 ;;
	esac
	jar="$(jar_of "$1")"
	if ps -p "${pid}" -o command= 2> /dev/null | grep -q -- "${jar##*/}"; then
		echo "${pid}"
	fi
}

start_one() {
	local name="$1" port jar args=""
	port="$(field "${name}" 3)"
	jar="$(jar_of "${name}")"
	if [ -n "$(pid_of "${name}")" ]; then
		return 0
	fi
	if port_open "${port}"; then
		say "  ${name}：${port} 端口上已经有进程在听，不再启动。是你在 IDE 里启动的 ${name} 就不用管"
		return 0
	fi
	[ -f "${jar}" ] || die "没有找到 ${jar}，先执行 ./dev.sh build"
	if [ "$(field "${name}" 5)" = dev ]; then
		args="--spring.profiles.active=dev"
	fi
	# 上一次的日志留一份（.log.1）：启动失败后再试一次，前一次的报错还查得到
	if [ -s "${LOG_DIR}/${name}.log" ]; then
		mv -f "${LOG_DIR}/${name}.log" "${LOG_DIR}/${name}.log.1"
	fi
	# JAVA_OPTS 里可以有多个参数，要按空格拆开，所以不加引号
	# shellcheck disable=SC2086
	nohup java ${JAVA_OPTS} -jar "${jar}" ${args} < /dev/null > "${LOG_DIR}/${name}.log" 2>&1 &
	echo $! > "${RUN_DIR}/${name}.pid"
}

# 从一个服务的日志里挑出说明原因的几行：Spring Boot 的失败说明（Description / Action 和它们下面的正文）、
# ERROR 行和紧跟着的异常、各层 Caused by。最里面的原因在最后，所以行数多时留后面的
show_failure() {
	local log="${LOG_DIR}/$1.log"
	[ -f "${log}" ] || return 0
	awk '
		/APPLICATION FAILED TO START/ { print; next }
		/^Description:/ || /^Action:/ { print; block = 1; seen = 0; next }
		block == 1 {
			if (NF == 0) { if (seen > 0) block = 0; next }
			print "  " $0
			seen++
			if (seen >= 4) block = 0
			next
		}
		/ ERROR / { print; want = 1; next }
		want > 0 && NF > 0 {
			want = 0
			if ($0 !~ /^[ \t]+at / && $0 !~ /^[0-9][0-9][0-9][0-9]-/) { print; next }
		}
		/^Caused by: / { print }
	' "${log}" | tail -n 12 | cut -c1-300
	say "   完整日志：.dev/logs/$1.log"
}

# 等一批服务的健康检查通过。有没起来的返回 1，并打印日志里的报错
wait_healthy() {
	local pending="$*" next failed="" name started now port
	started="$(date +%s)"
	while [ -n "${pending}" ]; do
		next=""
		now="$(date +%s)"
		for name in ${pending}; do
			if http_ok "$(health_url "${name}")"; then
				say "  ${name} 起来了（$((now - started)) 秒）"
			elif [ -z "$(pid_of "${name}")" ]; then
				# 没有本脚本启动的进程：要么刚启动就退出了，要么端口上本来就有别的程序。
				# 后一种等 20 秒：是 IDE 里刚启动的这个服务，健康检查很快会通；一直不通就是别的程序占着端口
				if ! port_open "$(field "${name}" 3)" || [ $((now - started)) -ge 20 ]; then
					failed="${failed} ${name}"
				else
					next="${next} ${name}"
				fi
			elif [ $((now - started)) -ge "${WAIT_SECONDS}" ]; then
				failed="${failed} ${name}"
			else
				next="${next} ${name}"
			fi
		done
		pending="${next}"
		if [ -n "${pending}" ]; then
			sleep 2
		fi
	done
	[ -z "${failed}" ] && return 0
	for name in ${failed}; do
		say ""
		port="$(field "${name}" 3)"
		if [ -n "$(pid_of "${name}")" ]; then
			say "!! ${name} 等了 ${WAIT_SECONDS} 秒健康检查还没通过：$(health_url "${name}")"
			show_failure "${name}"
		elif port_open "${port}"; then
			say "!! ${name} 没有启动：${port} 端口被别的程序占着，它不是 ${name}（$(health_url "${name}") 不通）。先停掉占用端口的程序"
		else
			say "!! ${name} 启动失败，进程已经退出"
			show_failure "${name}"
		fi
	done
	return 1
}

stop_all_of() {
	local name pid alive i=0
	for name in "$@"; do
		pid="$(pid_of "${name}")"
		if [ -n "${pid}" ]; then
			kill "${pid}" 2> /dev/null
		fi
	done
	# 服务退出前要先从 Nacos 注销、等手上的请求做完，正常要十几秒
	while [ "${i}" -lt 45 ]; do
		alive=""
		for name in "$@"; do
			if [ -n "$(pid_of "${name}")" ]; then
				alive="${alive} ${name}"
			fi
		done
		[ -z "${alive}" ] && break
		sleep 1
		i=$((i + 1))
	done
	for name in "$@"; do
		pid="$(pid_of "${name}")"
		if [ -n "${pid}" ]; then
			kill -9 "${pid}" 2> /dev/null
			say "  ${name} 等了 45 秒没有退出，已经强制结束"
		fi
		rm -f "${RUN_DIR}/${name}.pid"
	done
}

running_services() {
	local name
	for name in ${ALL_SERVICES}; do
		if [ -n "$(pid_of "${name}")" ]; then
			printf '%s ' "${name}"
		fi
	done
}

prepare_dirs() {
	mkdir -p "${RUN_DIR}" "${LOG_DIR}" || die "建不了目录 ${DEV_DIR}"
}

export_service_env() {
	export MUGSUN_SERVICE_IP="${MUGSUN_SERVICE_IP:-127.0.0.1}"
	export MUGSUN_NACOS_ADDR="${MUGSUN_NACOS_ADDR:-127.0.0.1:${NACOS_PORT}}"
}

# ---------- 命令 ----------

cmd_up() {
	local names="" rest="" name build=1 began tries=0
	for name in "$@"; do
		case "${name}" in
			--no-build) build=0 ;;
			-*) die "不认识的选项 ${name}" ;;
			*)
				check_name "${name}"
				names="${names} ${name}"
				;;
		esac
	done
	[ -n "${names}" ] || names="${ALL_SERVICES}"
	began="$(date +%s)"
	need_java
	need_curl
	prepare_dirs
	deps_up
	ensure_secret
	if [ "${build}" = 1 ]; then
		build_if_needed
	fi
	export_service_env
	say "启动服务 ...（日志在 .dev/logs/）"
	case " ${names} " in
		*" system "*)
			# 主库的库表由 system 来建，加密数据用的几把密钥也由它首次生成，所以等它起来再启动别的
			start_one system
			wait_healthy system || exit 1
			;;
		*)
			if ! http_ok "$(health_url system)"; then
				say "  注意：system 没有在运行。第一次启动要先起它，库表由它来建"
			fi
			;;
	esac
	for name in ${names}; do
		if [ "${name}" != system ]; then
			rest="${rest} ${name}"
			start_one "${name}"
		fi
	done
	if [ -n "${rest}" ]; then
		# shellcheck disable=SC2086
		wait_healthy ${rest} || exit 1
	fi
	case " ${names} " in
		*" gateway "*)
			# 网关从 Nacos 拿到各服务的地址要几秒，所以经网关再请求一次 system，通了才算整套可用
			if http_ok "$(health_url system)"; then
				until http_ok "http://127.0.0.1:8080/ping"; do
					tries=$((tries + 1))
					if [ "${tries}" -ge 30 ]; then
						die "网关和 system 都起来了，但经网关访问 system 不通：curl http://localhost:8080/ping。看 .dev/logs/gateway.log"
					fi
					sleep 2
				done
			fi
			;;
	esac
	say ""
	say "都起来了，用时 $(($(date +%s) - began)) 秒。"
	say ""
	case " ${names} " in
		*" gateway "*)
			say "  网关（对外的入口）  http://localhost:8080      试一下：curl http://localhost:8080/ping"
			say "  接口文档            http://localhost:8080/swagger-ui/index.html"
			;;
	esac
	case " ${names} " in
		*" admin "*)
			say "  监控中心            http://localhost:8200      账号和口令默认都是 mugsun"
			;;
	esac
	say ""
	say "管理后台在另一个仓库 mugsun-pc：执行 pnpm install && pnpm dev，浏览器打开 http://localhost:3006。"
	say "账号 admin，首次建库时的初始口令是 123456，首次登录会要求修改。"
	say ""
	say "  看状态  ./dev.sh status"
	say "  看日志  ./dev.sh logs system"
	say "  全停掉  ./dev.sh down"
}

cmd_stop() {
	local name
	[ $# -gt 0 ] || die "要写服务名，比如 ./dev.sh stop system。全部停掉用 ./dev.sh down"
	for name in "$@"; do
		check_name "${name}"
	done
	stop_all_of "$@"
}

cmd_restart() {
	local name
	[ $# -gt 0 ] || die "要写服务名，比如 ./dev.sh restart system"
	for name in "$@"; do
		check_name "${name}"
	done
	need_java
	need_curl
	prepare_dirs
	build_if_needed
	stop_all_of "$@"
	export_service_env
	for name in "$@"; do
		start_one "${name}"
	done
	wait_healthy "$@" || exit 1
}

cmd_down() {
	local running
	running="$(running_services)"
	if [ -n "${running}" ]; then
		say "停掉服务：${running}"
		# shellcheck disable=SC2086
		stop_all_of ${running}
	fi
	deps_down "$@"
}

cmd_reset() {
	local answer
	if [ "${1:-}" != --yes ]; then
		printf '会停掉全部服务，并清空本地开发库（PostgreSQL 的数据卷）。输入 yes 继续：'
		read -r answer
		[ "${answer}" = yes ] || die "没有执行"
	fi
	need_docker
	cmd_down --volumes
}

cmd_status() {
	local name port pid state
	say "依赖"
	for name in "Nacos ${NACOS_PORT}" "PostgreSQL ${DB_PORT}" "Redis ${REDIS_PORT}"; do
		port="${name##* }"
		if port_open "${port}"; then
			state="在运行"
		else
			state="没有启动"
		fi
		printf '  %-11s %-6s %s\n' "${name% *}" "${port}" "${state}"
	done
	say "服务"
	for name in ${ALL_SERVICES}; do
		port="$(field "${name}" 3)"
		pid="$(pid_of "${name}")"
		if http_ok "$(health_url "${name}")"; then
			if [ -n "${pid}" ]; then
				state="在运行（进程 ${pid}）"
			else
				state="在运行（不是本脚本启动的）"
			fi
		elif [ -n "${pid}" ]; then
			state="进程 ${pid} 在，健康检查没通过（还在启动，或者出了问题）"
		elif port_open "${port}"; then
			state="端口被别的进程占着"
		else
			state="没有启动"
		fi
		printf '  %-11s %-6s %s\n' "${name}" "${port}" "${state}"
	done
}

cmd_logs() {
	[ $# -eq 1 ] || die "要写一个服务名，比如 ./dev.sh logs system"
	check_name "$1"
	[ -f "${LOG_DIR}/$1.log" ] || die "$1 还没有日志：它没有用本脚本启动过"
	exec tail -n 100 -f "${LOG_DIR}/$1.log"
}

command="${1:-}"
if [ $# -gt 0 ]; then
	shift
fi
case "${command}" in
	up) cmd_up "$@" ;;
	down) cmd_down ;;
	status) cmd_status ;;
	logs) cmd_logs "$@" ;;
	restart) cmd_restart "$@" ;;
	stop) cmd_stop "$@" ;;
	deps)
		prepare_dirs
		deps_up
		ensure_secret
		;;
	build)
		need_java
		prepare_dirs
		ensure_core
		do_build
		;;
	core)
		need_java
		prepare_dirs
		ensure_core force
		;;
	reset) cmd_reset "$@" ;;
	help | -h | --help)
		usage
		;;
	*)
		usage >&2
		exit 64
		;;
esac
