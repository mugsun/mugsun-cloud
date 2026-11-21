# syntax=docker/dockerfile:1@sha256:4edf897a3ffa55b89f906fc8cc78afdb3f1834cc9c7083565e611a8a7d5fe99e
# 初始化用的小镜像，构建上下文是本目录（deploy/docker）：
#   - gen-secrets.sh：首次启动生成全部口令和密钥；
#   - nacos-init.py：给 Nacos 设管理员口令，建网关和各服务用的接入账号。
# boot/ 下的文件从 mugsun-boot 同步，和单体版用的是同一份。
FROM alpine:3.20@sha256:d9e853e87e55526f6b2917df91a2115c36dd7c696a35be12163d44e6e2a4b6bc
# openssl 生成密钥；psql 和 bcrypt 给 init show 用，判断管理员改没改过口令；nacos-init.py 也用这个 python3。
# 包的版本固定，和 mugsun-boot 的 docker/full/init.Dockerfile 一致。Alpine 的仓库删掉旧版本后构建会失败，到时按 apk list 更新
RUN apk add --no-cache openssl=3.3.7-r0 postgresql16-client=16.14-r0 python3=3.12.13-r0 py3-bcrypt=4.1.3-r0
COPY --chmod=0555 boot/gen-keys.sh /gen-keys.sh
COPY --chmod=0444 boot/init-pw-check.py /init-pw-check.py
COPY --chmod=0555 gen-secrets.sh /gen-secrets.sh
COPY --chmod=0444 nacos-init.py /nacos-init.py
ENTRYPOINT ["/gen-secrets.sh"]
