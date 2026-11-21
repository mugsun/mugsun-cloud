#!/bin/bash
# 容器健康检查：请求本机的健康检查地址，应答 200 算健康。
# 端口和路径在镜像里按服务写好了（MUGSUN_HEALTH_PORT、MUGSUN_HEALTH_PATH），改了 server.port 时要跟着改。
# 用 bash 自带的 /dev/tcp 发请求，不依赖镜像里有没有 curl。
port="${MUGSUN_HEALTH_PORT:?没有设置 MUGSUN_HEALTH_PORT}"
path="${MUGSUN_HEALTH_PATH:?没有设置 MUGSUN_HEALTH_PATH}"
exec 3<>"/dev/tcp/127.0.0.1/${port}" || exit 1
printf 'GET %s HTTP/1.0\r\nHost: 127.0.0.1\r\n\r\n' "${path}" >&3
IFS= read -r status <&3 || exit 1
case "${status}" in
	HTTP/*" 200"*) exit 0 ;;
esac
exit 1
