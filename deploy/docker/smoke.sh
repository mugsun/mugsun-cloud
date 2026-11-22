#!/usr/bin/env bash
# 一键全套自检：从零构建并启动（system 起两个实例）→ 逐项检查部署上的安全约定、网关的开关传进了容器并且在起作用、
# 各实例领到的机器号各不相同 → 登录、上报并查到第一条埋点、
# 服务之间互相调用、同一个请求编号在网关和服务的日志里都找得到
# → 两个 system 实例之间保持一致，从空库一起启动都没有重启过，正常停掉一个的过程中经网关的访问不能有失败
# → 重启 Nacos、再把全部容器停掉打乱顺序重新启动，看能不能自己恢复
# → 把每个接口都请求一遍：不登录进不去，不用登录的接口和清单一致，网关转给了有这个接口的服务，
# 没有任何角色的账号进得去的接口和清单一致 → 清理。
# 用独立的项目名、端口和网段（默认 mugsun-cloud-smoke、18188、172.30.90.0/24），不碰本机别的容器和卷。
# 镜像和正式那一套是同一组标签（mugsun-cloud-*:local），这里会按当前的源码重新构建它们，
# 正式那一套下次执行 up 时会换成新构建的镜像。正式那一套在运行时执行这个脚本，没有实测过。
#   deploy/docker/smoke.sh            本地或 CI
#   KEEP=1 deploy/docker/smoke.sh     检查完不清理，留着现场（走到了最后一步的话，现场里四个业务服务的接口文档是开着的；
#                                     那个没有角色的账号的登录令牌也留在交接用的卷里，可以接着拿它查）
#   MUGSUN_SYSTEM_REPLICAS=1 deploy/docker/smoke.sh    system 只起一个实例，跳过两个实例之间的检查
# 需要 Docker（Compose v2.17 以上）和 curl。内存要求见 docs/deployment.md。
set -euo pipefail
cd "$(dirname "${0}")"
export COMPOSE_PROJECT_NAME="${COMPOSE_PROJECT_NAME:-mugsun-cloud-smoke}"
export MUGSUN_HTTP_PORT="${MUGSUN_HTTP_PORT:-18188}"
export MUGSUN_NET_PREFIX="${MUGSUN_NET_PREFIX:-172.30.90}"
# system 默认起两个实例：从空库一起启动，下面每一步都带着两个实例
export MUGSUN_SYSTEM_REPLICAS="${MUGSUN_SYSTEM_REPLICAS:-2}"
# 网关的开关在自检里用固定的一组值，不跟调用方的环境和 .env 走：下面有检查靠这几个值，
# 而且正式环境配的白名单、限流会把自检自己的请求拦掉。空的也要写出来，不写的话 .env 里的值会生效。
# 留空的那几个顺便验证了「什么都不配」时网关的样子：编排把空值传进容器，网关要起得来，访问日志照默认记
export MUGSUN_GATEWAY_ACCESS_LOG="" MUGSUN_GATEWAY_SLOW_MILLIS=""
export MUGSUN_GATEWAY_DENY_IPS=203.0.113.7 MUGSUN_GATEWAY_ALLOW_IPS=""
export MUGSUN_GATEWAY_RESTRICTED_PATHS=/smoke-restricted MUGSUN_GATEWAY_RESTRICTED_ALLOW_IPS=""
export MUGSUN_GATEWAY_RATE_LIMIT="" MUGSUN_GATEWAY_RATE_LIMIT_PATHS="/smoke-limited=3/60s"
export MUGSUN_GATEWAY_RATE_LIMIT_EXEMPT_IPS=""
DC=(docker compose -f docker-compose.yml)
# 拆出新服务时：在 APP_SERVICES 里补上名字，并在下面的 serving 里加一条它的接口
APP_SERVICES="system gis track ai"
JAVA_SERVICES="gateway ${APP_SERVICES}"
PYTHON_IMAGE="python:3.12-alpine@sha256:1b668429b3511ab407d8e00648891631b0b1a4d7e15e3ca70f38ab5b91ad4ab4"
URL="http://127.0.0.1:${MUGSUN_HTTP_PORT}"
# 最后一步放临时编排片段的目录，退出时删掉
sweep_dir=""
# 交接用的卷：smoke-check.py 建一个没有任何角色的账号并登录，把登录令牌放在里面，最后一步取出来用。
# 它不在编排文件里，down -v 删不到它，开头和退出时单独删；KEEP=1 时和现场一起留着
handoff="${COMPOSE_PROJECT_NAME}_smoke-handoff"

fail() {
	echo "FAIL ${*}" >&2
	exit 1
}
# 最后一步要挂进容器的四个文件，开头就查：缺了的话不要等前面几分钟都跑完才报错。这时还没动过任何容器
public_paths="$(cd ../.. && pwd)/mugsun-cloud-common/src/boot/java/com/mugsun/boot/security/LoginPublicPaths.java"
for needed in "${public_paths}" "${PWD}/smoke-sweep.py" "${PWD}/public-endpoints.txt" "${PWD}/roleless-endpoints.txt"; do
	[ -f "${needed}" ] || fail "找不到最后一步要用的文件：${needed}"
done
# 没通过时先把各容器的状态和最后的日志打出来，再清理
finish() {
	code=$?
	if [ "${code}" -ne 0 ]; then
		echo ">> 没有通过，下面是各容器的状态和最后的日志" >&2
		"${DC[@]}" ps -a >&2 || true
		"${DC[@]}" logs --no-color --tail 80 >&2 || true
	fi
	if [ "${KEEP:-0}" != 1 ]; then
		"${DC[@]}" down -v --remove-orphans >/dev/null 2>&1 || true
		docker volume rm -f "${handoff}" >/dev/null 2>&1 || true
	fi
	[ -z "${sweep_dir}" ] || rm -rf "${sweep_dir}"
	exit "${code}"
}
trap finish EXIT

# 一个服务的各个容器，每行一个。system 可能有两个，别的服务都是一个
cid() {
	"${DC[@]}" ps -aq "${1}"
}
# 一个服务的各个容器的地址，每行一个
ip_of() {
	# shellcheck disable=SC2046
	docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' $(cid "${1}")
}
# 五个服务是不是都在 Nacos 里登记了（用接入账号查），system 登记的实例数对不对
registered() {
	listed=$("${DC[@]}" run --rm -T nacos-init status 2>/dev/null) || return 1
	for svc in ${JAVA_SERVICES}; do
		case "${listed}" in
			*"mugsun-${svc}: "[0-9]*) ;;
			*) return 1 ;;
		esac
	done
	# 输出里每个服务一行：服务名和冒号后面是各个实例，用空格隔开
	system_line=$(grep '^mugsun-system: ' <<<"${listed}") || return 1
	# shellcheck disable=SC2206
	system_found=(${system_line#*: })
	[ "${#system_found[@]}" -eq "${MUGSUN_SYSTEM_REPLICAS}" ]
}
wait_registered() {
	waited=0
	until registered; do
		[ "${waited}" -lt "${1}" ] || fail "等了 ${1} 秒，Nacos 里还没有登记齐五个服务（system 要有 ${MUGSUN_SYSTEM_REPLICAS} 个实例）"
		sleep 3
		waited=$((waited + 3))
	done
}
http_code() {
	curl -s -o /dev/null -m 10 -w '%{http_code}' "${URL}${1}"
}
# 经管理后台和网关，四个服务是不是都在应答。没登录时地图、埋点、AI 的接口返回 401，说明请求到了对应的服务；
# 网关找不到实例时是 503，管理后台连不上网关时是 502
serving() {
	[ "$(http_code /api/ping)" = 200 ] && [ "$(http_code /api/system/gis/layer/page)" = 401 ] \
		&& [ "$(http_code /api/system/track/app/page)" = 401 ] && [ "$(http_code /api/system/ai/model/page)" = 401 ]
}
# 用法：wait_serving <最多等几秒> <什么时候>
wait_serving() {
	waited=0
	until serving; do
		[ "${waited}" -lt "${1}" ] || fail "${2}等了 ${1} 秒，经网关的接口还没有恢复"
		sleep 3
		waited=$((waited + 3))
	done
}

"${DC[@]}" down -v --remove-orphans >/dev/null 2>&1 || true
docker volume rm -f "${handoff}" >/dev/null 2>&1 || true
start=$(date +%s)
# 管理后台的前端构建很吃内存，和 Maven 打包分开做：两个一起跑，内存小的 Docker 上会有一个被杀掉
"${DC[@]}" build pc
"${DC[@]}" build
built=$(date +%s)
# ready 是一次性的提示容器，它退出以后 --wait 会误报失败，所以这里写明常驻的服务；
# init 和 nacos-init 也是一次性的，但有别的服务等它们成功退出，--wait 能正确对待
# shellcheck disable=SC2086
"${DC[@]}" up -d --wait --wait-timeout "${WAIT_TIMEOUT:-900}" postgres redis nacos ${JAVA_SERVICES} pc
up=$(date +%s)
echo ">> 整套已就绪（构建 $((built - start)) 秒，启动 $((up - built)) 秒）"

# ---- 管理员口令：init show 能看到，日志和容器配置里都没有 ----
# 先存进变量再匹配：开着 pipefail 时，grep -q 提前退出会让前面的命令收到 SIGPIPE 而被误判为失败
admin_line=$("${DC[@]}" run --rm -T init show)
admin_pw=${admin_line#*管理员口令：}
[ "${#admin_pw}" -eq 19 ] || fail "init show 没有给出管理员口令"
init_log=$("${DC[@]}" logs --no-color init 2>&1)
[[ "${init_log}" == *"init show"* ]] || fail "首次启动的日志里没有查看口令的提示"
all_logs=$("${DC[@]}" logs --no-color 2>&1)
[[ "${all_logs}" != *"${admin_pw}"* ]] || fail "管理员口令被打进了容器日志"
# shellcheck disable=SC2207
ids=($("${DC[@]}" ps -aq))
configs=$(docker inspect "${ids[@]}")
[[ "${configs}" != *"${admin_pw}"* ]] || fail "管理员口令出现在容器配置里"
# 各种口令和密钥都从密钥卷里的文件读，不该出现在容器的环境变量里（docker inspect 看得到）
envs=$(docker inspect -f '{{range .Config.Env}}{{println .}}{{end}}' "${ids[@]}")
for name in JDBC_PASSWORD TRACK_JDBC_PASSWORD REDIS_PASSWORD MUGSUN_SM4_KEY MUGSUN_API_KEY MUGSUN_SM2_PRIVATE_KEY \
	MUGSUN_SIGN_PRIVATE_KEY MUGSUN_INIT_PASSWORD MUGSUN_FILE_SIGN_SECRET MUGSUN_INNER_SECRET MUGSUN_NACOS_PASSWORD \
	MUGSUN_DB_PASSWORD POSTGRES_PASSWORD NACOS_AUTH_TOKEN NACOS_AUTH_IDENTITY_KEY NACOS_AUTH_IDENTITY_VALUE; do
	[[ $'\n'"${envs}" != *$'\n'"${name}="* ]] || fail "${name} 出现在容器的环境变量里"
done

# ---- 对外只有管理后台一个端口，而且只绑回环地址 ----
home=$(curl -fsS "${URL}/")
[[ "${home}" == *"<html"* || "${home}" == *"<!DOCTYPE"* || "${home}" == *"<!doctype"* ]] || fail "管理后台首页打不开"
bind=$("${DC[@]}" port pc 8080)
[[ "${bind}" == 127.0.0.1:* ]] || fail "管理后台的端口没有只绑 127.0.0.1：${bind}"
for svc in postgres redis nacos ${JAVA_SERVICES}; do
	for id in $(cid "${svc}"); do
		published=$(docker port "${id}")
		[ -z "${published}" ] || fail "${svc} 的端口被映射到了宿主机：${published}"
	done
done

# ---- 管理后台：非 root、带 CSP、用对端地址覆盖 X-Forwarded-For（网关采信的就是它传来的这个头） ----
[ "$("${DC[@]}" exec -T pc id -u)" != 0 ] || fail "管理后台容器以 root 运行"
hdr=$(curl -fsSI "${URL}/")
[[ "${hdr}" == *[Cc]ontent-[Ss]ecurity-[Pp]olicy* ]] || fail "管理后台的响应没有 CSP 头"
ngx=$("${DC[@]}" exec -T pc nginx -T 2>/dev/null)
# shellcheck disable=SC2016
[[ "${ngx}" == *'X-Forwarded-For $remote_addr'* ]] || fail "管理后台的 Nginx 没有用 \$remote_addr 覆盖 X-Forwarded-For"
[[ "${ngx}" != *proxy_add_x_forwarded_for* ]] || fail "管理后台的 Nginx 还在追加客户端自带的 X-Forwarded-For"

# ---- 网络：要被别的容器找到的五个容器用固定地址，四个业务服务只在动态段里分配 ----
net="${MUGSUN_NET_PREFIX}"
for pair in pc:10 nacos:11 gateway:12 postgres:13 redis:14; do
	svc=${pair%%:*}
	want="${net}.${pair##*:}"
	[ "$(ip_of "${svc}")" = "${want}" ] || fail "${svc} 的地址不是 ${want}：$(ip_of "${svc}")"
done
iprange=$(docker network inspect -f '{{range .IPAM.Config}}{{.IPRange}}{{end}}' "${COMPOSE_PROJECT_NAME}_default")
[ "${iprange}" = "${net}.128/25" ] || fail "网络的 ip_range 不是 ${net}.128/25：${iprange}"
for svc in ${APP_SERVICES}; do
	for addr in $(ip_of "${svc}"); do
		[ "${addr##*.}" -ge 128 ] || fail "${svc} 的地址不在动态段里：${addr}"
	done
done
# 网关只采信管理后台这一个地址传来的客户端地址
trusted=$("${DC[@]}" exec -T gateway printenv MUGSUN_OPENAPI_TRUSTED_PROXIES)
[ "${trusted}" = "${net}.10/32" ] || fail "网关采信的代理不是管理后台这一个地址：${trusted}"
# 管理后台按固定地址把请求转给网关。写名字的话 Nginx 只在启动时解析一次，那时网关没在运行就会解析到别处去
[[ "${ngx}" == *"proxy_pass http://${net}.12:8080/;"* ]] || fail "管理后台没有按固定地址把请求转给网关"

# ---- 网关的开关：网关认的每一个，编排都传进了容器；传进去的在起作用 ----
# 编排只把写在 environment 里的变量传进容器，漏传的开关写进 .env 也不生效，而且没有任何提示。
# 故意不传的写在 not_passed 里：动态路由和灰度规则都要网关接入 Nacos 的账号能读配置，这份编排没有给这个权限
not_passed="MUGSUN_GATEWAY_DYNAMIC_ROUTES MUGSUN_GATEWAY_LANES"
# 只认写成 ${MUGSUN_GATEWAY_...} 的：注释里也会提到这个前缀
declared=$(grep -oE '\$\{MUGSUN_GATEWAY_[A-Z0-9_]+' ../../mugsun-cloud-gateway/src/main/resources/application.yml \
	| cut -c3- | sort -u) \
	|| fail "网关的 application.yml 里没有找到 MUGSUN_GATEWAY_ 开头的开关"
gateway_env=$("${DC[@]}" exec -T gateway env)
passed=0
for name in ${declared}; do
	[[ " ${not_passed} " != *" ${name} "* ]] || continue
	want=$(printenv "${name}") || fail "自检没有给网关的开关 ${name} 定值：在这个脚本开头补上"
	[[ $'\n'"${gateway_env}"$'\n' == *$'\n'"${name}=${want}"$'\n'* ]] \
		|| fail "网关的开关 ${name} 没有照自检定的值传进网关容器：在 docker-compose.yml 的 gateway 服务里补上"
	passed=$((passed + 1))
done
# 不对外开放的路径：自检把 /smoke-restricted 设成了不对外开放，没有配能访问的地址，谁经网关访问都是 403；
# 前缀按整段比较，旁边的 /smoke-restrictedx 不受影响。管理后台的 Nginx 去掉 /api 以后再转给网关
code=$(http_code /api/smoke-restricted/x)
[ "${code}" = 403 ] || fail "网关没有拦住不对外开放的路径，返回了 ${code}"
code=$(http_code /api/smoke-restrictedx)
[ "${code}" != 403 ] || fail "不对外开放的路径把旁边的 /smoke-restrictedx 也拦了"
# 限流：自检给 /smoke-limited 单独定了 60 秒 3 个。前 3 个网关放行（后面有没有这个接口不要紧），第 4 个返回 429
for n in 1 2 3; do
	code=$(http_code /api/smoke-limited)
	[ "${code}" != 429 ] || fail "限额是 60 秒 3 个，第 ${n} 个请求就被限流了"
done
code=$(http_code /api/smoke-limited)
[ "${code}" = 429 ] || fail "限额是 60 秒 3 个，第 4 个请求没有被限流，返回了 ${code}"
# 访问日志：被限流的那个请求有一行。里面的 ip 是网关认定的客户端地址，按地址的名单和限流认的就是它。
# 它应该是管理后台的 Nginx 看到的对端地址；是管理后台自己的地址就说明网关没有采信它传来的 X-Forwarded-For
seen=""
for _ in 1 2 3 4 5; do
	line=$("${DC[@]}" logs --no-color gateway 2>&1 | grep ' ACCESS ' | grep ' uri=/smoke-limited status=429 ' | tail -n 1 || true)
	if [[ "${line}" =~ \ ip=([^ ]+)\ method= ]]; then
		seen="${BASH_REMATCH[1]}"
		break
	fi
	sleep 1
done
[ -n "${seen}" ] || fail "网关的访问日志里没有被限流的那个请求"
[ "${seen}" != "${net}.10" ] || fail "网关把管理后台的地址当成了客户端地址：没有采信它传来的 X-Forwarded-For"
echo ">> 网关的 ${passed} 个开关都传进了容器，不对外开放的路径和限流在起作用；从宿主机的端口进来的请求，网关认定的客户端地址是 ${seen}"

# ---- 进程和密钥 ----
for svc in ${JAVA_SERVICES}; do
	for id in $(cid "${svc}"); do
		[ "$(docker exec "${id}" id -u)" = 10001 ] || fail "${svc} 不是以 uid 10001 运行"
	done
done
[ "$("${DC[@]}" exec -T redis id -u redis)" = 999 ] || fail "redis 用户的 uid 不是 999"
[ "$("${DC[@]}" exec -T postgres id -u postgres)" = 999 ] || fail "postgres 用户的 uid 不是 999"
cmdline=$("${DC[@]}" exec -T redis sh -c 'tr "\0" " " < /proc/1/cmdline')
[[ "${cmdline}" != *requirepass* ]] || fail "Redis 口令出现在进程参数里"
# 密钥按使用方分卷：每个容器只看得到自己的那一个文件，0600，属主是读它的用户
secret_of() {
	for id in $(cid "${1}"); do
		out=$(docker exec "${id}" sh -c 'ls -A /secrets; stat -c "%a %u" /secrets/'"${2}")
		[ "${out}" = "$(printf '%s\n600 %s' "${2}" "${3}")" ] || fail "${1} 的 /secrets 不符合预期：${out}"
	done
}
for svc in ${APP_SERVICES}; do
	secret_of "${svc}" service.env 10001
done
secret_of gateway gateway.env 10001
secret_of postgres db.env 999
secret_of redis redis.conf 999
secret_of nacos nacos.env 0
# 网关的密钥文件里只有接入 Nacos 的账号，没有数据库、Redis 的口令和任何业务密钥
names=$("${DC[@]}" exec -T gateway sed -n 's/^export \([A-Z0-9_]*\)=.*/\1/p' /secrets/gateway.env)
[ "${names}" = "$(printf 'MUGSUN_NACOS_USERNAME\nMUGSUN_NACOS_PASSWORD')" ] || fail "网关的密钥文件里不只是 Nacos 账号：${names}"
# Nacos 管理员口令所在的卷只给一次性的初始化容器用，常驻容器都不挂
for svc in postgres redis nacos pc ${JAVA_SERVICES}; do
	# shellcheck disable=SC2046
	mounts=$(docker inspect -f '{{range .Mounts}}{{println .Name}}{{end}}' $(cid "${svc}"))
	[[ "${mounts}" != *secrets-nacos-admin* ]] || fail "${svc} 挂了 Nacos 管理员口令所在的卷"
done

# ---- Nacos：五个服务都用接入账号登记上了 ----
wait_registered 60

# ---- 机器号：四个业务服务的每个实例都领到了一个，各不相同 ----
# 程序生成的主键里带着机器号：两个实例的机器号相同，同一毫秒往同一张表里写的两条主键就可能相同
# （docs/deployment.md 第 7 节）。实例领到号的时候在日志里报一次
machine_numbers=""
for svc in ${APP_SERVICES}; do
	for c in $(cid "${svc}"); do
		line=$(docker logs "${c}" 2>&1 | grep '本实例的机器号：' | tail -n 1 || true)
		[[ "${line}" =~ 本实例的机器号：([0-9]+) ]] || fail "${svc} 的实例没有领到机器号：日志里没有「本实例的机器号」"
		machine_numbers+=" ${BASH_REMATCH[1]}"
	done
done
# shellcheck disable=SC2086
[ -z "$(printf '%s\n' ${machine_numbers} | sort | uniq -d)" ] || fail "有实例领到了相同的机器号：${machine_numbers# }"
echo ">> 四个业务服务的每个实例各领到一个机器号，各不相同：${machine_numbers# }"

# ---- 登录、上报埋点、服务之间互相调用：在编排网络里的一次性容器里执行 ----
# Python 依赖的版本和 sha256 都写死在清单里（--require-hashes）。
# system 各实例的地址交给它：有两个实例时，它直接访问每个实例，检查两个实例之间是不是一致
# shellcheck disable=SC2207
system_addrs=($(ip_of system))
[ "${#system_addrs[@]}" -eq "${MUGSUN_SYSTEM_REPLICAS}" ] || fail "system 的容器不是 ${MUGSUN_SYSTEM_REPLICAS} 个：${system_addrs[*]}"
# 它最后建一个没有任何角色的账号并登录，登录令牌放进交接用的卷，留给最后一步
docker volume create "${handoff}" >/dev/null
# 它调「AI 问图层」「AI 问埋点」时带上这个请求编号，下面到日志里找它
trace_id="smoke-$(date +%s)-$$"
docker run --rm --network "${COMPOSE_PROJECT_NAME}_default" \
	-e SYSTEM_ADDRS="${system_addrs[*]}" -e TRACE_ID="${trace_id}" \
	-v "${COMPOSE_PROJECT_NAME}_secrets-service:/secrets/service:ro" -v "${handoff}:/handoff" \
	-v "${PWD}/boot/smoke.py:/smoke.py:ro" -v "${PWD}/boot/smoke-requirements.txt:/req.txt:ro" \
	-v "${PWD}/smoke-check.py:/smoke-check.py:ro" \
	"${PYTHON_IMAGE}" \
	sh -c "pip install -q --disable-pip-version-check --root-user-action=ignore --no-deps --only-binary=:all: --require-hashes -r /req.txt && python /smoke-check.py"
checked=$(date +%s)
# 上面改过了管理员口令：init show 不能再给出初始口令
after_line=$("${DC[@]}" run --rm -T init show)
[[ "${after_line}" == *"初始密码已失效"* ]] || fail "改口令以后 init show 没有提示初始密码已失效"
[[ "${after_line}" != *"${admin_pw}"* ]] || fail "改口令以后 init show 还在打印初始口令"

# ---- 请求编号：拿着它能在网关和服务的日志里找到同一次请求 ----
# 网关的访问日志里要有上面那个编号；AI 服务转去调地图、埋点服务时带着它，那两个服务每次内部调用记一行日志，
# 这一行在级别后面的方括号里也要是它（docs/deployment.md「请求编号」）。编号没有传过去，或者服务的日志格式里没有带上它，
# 这里都会找不到。容器的日志晚一点才读得到，没找到就等一秒再看。
# 用法：trace_logged <服务> <这一行要匹配的正则>
trace_logged() {
	local svc_logs
	for _ in 1 2 3 4 5 6 7 8 9 10; do
		svc_logs=$("${DC[@]}" logs --no-color "${1}" 2>&1 || true)
		if grep -q -E -- "${2}" <<<"${svc_logs}"; then
			return 0
		fi
		sleep 1
	done
	return 1
}
trace_logged gateway " ACCESS .* trace=${trace_id}( |\$)" || fail "网关的访问日志里没有请求编号 ${trace_id}"
for svc in gis track; do
	trace_logged "${svc}" " INFO \[${trace_id}\] .*内部调用 /inner/${svc}/insight/snapshot " \
		|| fail "${svc} 的日志里没有带着请求编号 ${trace_id} 的内部调用记录：编号没有从 AI 服务传过来，或者日志的格式里没有带上它"
done
echo ">> 自检带的请求编号在网关、地图、埋点三处的日志里都找得到"

# ---- 时区（默认 Asia/Shanghai，MUGSUN_TZ 可改） ----
# 库里的时间列不带时区，按后端 JVM 的时区写入、页面原样显示，所以常驻容器的时区偏移要一致；
# 刚才登录过，最新一条登录日志里应用写的 login_time 和数据库 now() 写的 create_time 都得是这个时区刚才的钟点
tz=$("${DC[@]}" exec -T system printenv TZ || true)
[ -n "${tz}" ] || fail "system 容器没有设置 TZ"
offset=$("${DC[@]}" exec -T system date +%z)
for svc in postgres redis gateway gis track ai pc; do
	got=$("${DC[@]}" exec -T "${svc}" date +%z)
	[ "${got}" = "${offset}" ] || fail "${svc} 容器的时区偏移是 ${got}，system 是 ${offset}（${tz}）"
done
# Nacos 的镜像里没有系统的时区数据，容器里的 date 只会报 UTC；它的 Java 进程按 TZ 定时区（日志里的钟点跟着它走），所以核对 TZ
nacos_tz=$("${DC[@]}" exec -T nacos printenv TZ || true)
[ "${nacos_tz}" = "${tz}" ] || fail "nacos 容器的 TZ 是 ${nacos_tz:-空}，system 是 ${tz}"
lag=$("${DC[@]}" exec -T postgres psql -U postgres -d mugsun -At -F ' ' -c \
	"SELECT round(extract(epoch FROM (now() AT TIME ZONE '${tz}') - login_time)), round(extract(epoch FROM (now() AT TIME ZONE '${tz}') - create_time)) FROM sys_login_log ORDER BY id DESC LIMIT 1")
read -r lag_login lag_create <<<"${lag}" || true
for v in "${lag_login}" "${lag_create}"; do
	{ [[ "${v}" =~ ^-?[0-9]+$ ]] && [ "${v}" -ge -60 ] && [ "${v}" -le 600 ]; } \
		|| fail "最新一条登录日志的时间不是 ${tz} 的当前时间（login_time、create_time 落后的秒数：${lag:-查不到记录}）"
done

# ---- system 的实例从空库一起启动，到这里一次都没有重启过 ----
# 启动时的数据库迁移和「改表、写初始数据」都是排着队一个一个做的（docs/deployment.md 第 7 节）。没排好队时，
# 后到的实例启动失败、被 Docker 重新拉起来，最后也能就绪，前面的检查都看不出来，要看重启次数。
# 放在这里查：有一种启动失败是实例先显示就绪、过十来秒才退出，刚启动完就查会漏掉
for c in $(cid system); do
	restarts=$(docker inspect -f '{{.RestartCount}}' "${c}")
	if [ "${restarts}" != 0 ]; then
		name=$(docker inspect -f '{{.Name}}' "${c}")
		cause=$(docker logs "${c}" 2>&1 | grep 'Caused by: ' | tail -n 1 | cut -c1-300 || true)
		fail "system 的实例 ${name#/} 从空库启动时失败过，被重新拉起了 ${restarts} 次。日志里最后一条原因：${cause:-没找到}"
	fi
done
echo ">> system 的 ${MUGSUN_SYSTEM_REPLICAS} 个实例从空库一起启动，都没有重启过"

# ---- 正常停掉一个 system 实例：停的过程中经网关连续访问，一次失败都不能有；再起回来，重新登记上 ----
# 实例先从 Nacos 注销，照常应答 8 秒才开始停；网关把实例列表缓存 5 秒，到它开始停的时候已经不往它转发了
# （docs/deployment.md 第 10 节）。停一个实例约 9 秒，这里从发出停止命令起连续访问 15 秒。/api/ping 由 system 应答
if [ "${MUGSUN_SYSTEM_REPLICAS}" -gt 1 ]; then
	# shellcheck disable=SC2207
	system_ids=($(cid system))
	stopped=$(date +%s)
	docker stop "${system_ids[0]}" >/dev/null &
	stopper=$!
	asked=0
	while [ "$(date +%s)" -lt "$((stopped + 15))" ]; do
		code=$(http_code /api/ping)
		asked=$((asked + 1))
		[ "${code}" = 200 ] || fail "正常停掉一个 system 实例的过程中，经网关访问 system 的第 ${asked} 次返回了 ${code}（发出停止命令后 $(($(date +%s) - stopped)) 秒）"
	done
	wait "${stopper}"
	[ "$(docker inspect -f '{{.State.ExitCode}}' "${system_ids[0]}")" != 137 ] || fail "system 实例停止时没有来得及自己退出，被强杀了"
	docker start "${system_ids[0]}" >/dev/null
	wait_registered 120
	echo ">> 正常停掉一个 system 实例的 15 秒里经网关访问 ${asked} 次，全部成功；起回来以后，从发出停止命令算起 $(($(date +%s) - stopped)) 秒重新登记上"
fi

# ---- Nacos 重启以后能自己恢复 ----
# 重启后马上再执行一次 up：初始化脚本会在 Nacos 刚起来的时候重跑，要能通过；
# 各服务登记的实例在 Nacos 里只存在内存中，重启就没了，要能自己重新登记，网关要能重新找到它们
restarted=$(date +%s)
"${DC[@]}" restart nacos >/dev/null 2>&1
# shellcheck disable=SC2086
"${DC[@]}" up -d --wait --wait-timeout 300 nacos ${JAVA_SERVICES} pc >/dev/null 2>&1 || fail "Nacos 重启以后再执行 up 没有成功"
wait_registered 90
wait_serving 60 "Nacos 重启以后"
echo ">> Nacos 重启以后 $(($(date +%s) - restarted)) 秒，五个服务重新登记上，经网关的接口恢复"

# ---- 整台机器重启以后能自己恢复 ----
# 机器或者 Docker 重启以后，各容器靠 restart 策略自己起来，先后顺序不定，一次性的初始化容器不会再跑。
# 这里把常驻容器全部停掉，故意先起管理后台和五个服务，15 秒以后才起数据库、Redis 和 Nacos：
# 连不上依赖的服务会退出，由 Docker 重新拉起；依赖起来以后整套要能自己恢复，不用人去重启哪个容器。
# 直接用 docker stop 和 docker start：compose 的 start 会按依赖关系排好先后
front=""
for svc in pc ${JAVA_SERVICES}; do
	front="${front} $(cid "${svc}")"
done
back=""
for svc in postgres redis nacos; do
	back="${back} $(cid "${svc}")"
done
rebooted=$(date +%s)
# shellcheck disable=SC2086
docker stop ${front} ${back} >/dev/null
# 停止时每个进程都要来得及自己收尾退出。退出码 137 表示等到了时间、被强杀的
for svc in postgres redis nacos pc ${JAVA_SERVICES}; do
	for id in $(cid "${svc}"); do
		[ "$(docker inspect -f '{{.State.ExitCode}}' "${id}")" != 137 ] || fail "${svc} 停止时没有来得及自己退出，被强杀了"
	done
done
# shellcheck disable=SC2086
docker start ${front} >/dev/null
sleep 15
# shellcheck disable=SC2086
docker start ${back} >/dev/null
wait_serving 180 "全部容器重新启动以后"
# 经网关的接口有一个 system 实例就能恢复。两个实例各自由 Docker 拉起，先后可能差几十秒，这里等到两个都登记上
wait_registered 120
echo ">> 全部容器停掉、打乱顺序重新启动以后 $(($(date +%s) - rebooted)) 秒，五个服务重新登记上，经网关的接口恢复"

# ---- 把每个接口都请求一遍：不登录进不去，网关转给了有这个接口的服务 ----
# 接口清单从各服务自己的接口文档里取。文档默认不提供（前面检查过），这里用一份临时的编排片段把它打开、
# 重新创建四个业务服务；片段不放进仓库，免得被当成正式环境的开关用。前面的检查都要跑在默认配置上，所以这一步放在最后。
# 分三步（smoke-sweep.py）：先在编排网络里把每个接口请求一遍，要登录的不带登录令牌，必须返回 401，
# 不用登录的接口必须和 public-endpoints.txt 里的一模一样；再拿网关的访问日志核对，每个请求都转给了文档里有这个接口的服务；
# 最后带着前面那个没有任何角色的账号的登录令牌，把要登录的接口再请求一遍，进得去的必须和 roleless-endpoints.txt 里的一模一样
swept=$(date +%s)
sweep_dir=$(mktemp -d)
{
	echo "services:"
	for svc in ${APP_SERVICES}; do
		printf '  %s:\n    environment:\n      SPRINGDOC_API_DOCS_ENABLED: "true"\n' "${svc}"
	done
} >"${sweep_dir}/docs-on.yml"
# shellcheck disable=SC2086
"${DC[@]}" -f "${sweep_dir}/docs-on.yml" up -d --wait --wait-timeout 300 ${APP_SERVICES} >/dev/null 2>&1 \
	|| fail "打开接口文档、重新创建四个业务服务没有成功"
wait_registered 120
wait_serving 60 "打开接口文档、重新创建四个业务服务以后"
asked=$(docker run --rm --network "${COMPOSE_PROJECT_NAME}_default" -e APP_SERVICES="${APP_SERVICES}" \
	-v "${PWD}/smoke-sweep.py:/smoke-sweep.py:ro" -v "${public_paths}:/LoginPublicPaths.java:ro" \
	-v "${PWD}/public-endpoints.txt:/public-endpoints.txt:ro" \
	"${PYTHON_IMAGE}" python /smoke-sweep.py ask)
grep '^OK ' <<<"${asked}" || true
# 网关处理完一个请求才记访问日志，容器的日志又晚一点才读得到：等结尾的标记请求出现在日志里再核对
end_mark=$(sed -n 's/^END //p' <<<"${asked}")
access=""
for _ in 1 2 3 4 5; do
	access=$("${DC[@]}" logs --no-color gateway 2>&1 | grep ' ACCESS ' || true)
	[[ "${access}" != *" uri=${end_mark} "* ]] || break
	sleep 1
done
printf '%s\n%s\n' "${asked}" "${access}" | docker run --rm -i -v "${PWD}/smoke-sweep.py:/smoke-sweep.py:ro" \
	"${PYTHON_IMAGE}" python /smoke-sweep.py routed
docker run --rm --network "${COMPOSE_PROJECT_NAME}_default" -e APP_SERVICES="${APP_SERVICES}" \
	-v "${PWD}/smoke-sweep.py:/smoke-sweep.py:ro" -v "${public_paths}:/LoginPublicPaths.java:ro" \
	-v "${PWD}/roleless-endpoints.txt:/roleless-endpoints.txt:ro" -v "${handoff}:/handoff:ro" \
	"${PYTHON_IMAGE}" python /smoke-sweep.py roleless
echo ">> 打开接口文档、重新创建四个业务服务，把每个接口请求了一遍，用了 $(($(date +%s) - swept)) 秒"

end=$(date +%s)
echo ">> PASS 全部检查通过，共 $((end - start)) 秒。其中构建 $((built - start)) 秒；从零到登录、看到第一条埋点、服务之间互相调用 $((checked - start)) 秒"
