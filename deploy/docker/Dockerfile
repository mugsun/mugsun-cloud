# syntax=docker/dockerfile:1@sha256:4edf897a3ffa55b89f906fc8cc78afdb3f1834cc9c7083565e611a8a7d5fe99e
# 网关、四个业务服务和监控中心的镜像：一个文件，六个构建目标 gateway / system / gis / track / ai / admin。
# 构建上下文是仓库根目录，例：
#   docker build -f deploy/docker/Dockerfile --target system -t mugsun-cloud-system .
# 整套一起起，用同目录的 docker-compose.yml。
#
# mugsun-core 在镜像里先构建安装：依赖的 0.2.0-SNAPSHOT 不在 Maven Central 上。源码取 GitHub 上的 MUGSUN_CORE_REF，
# 钉到 40 位提交号，和 mugsun-boot 同一个提交里 docker/full/boot.Dockerfile 钉的一致，同步 mugsun-boot 时跟着改。
# 本地联调可以换成本机的源码：
#   docker build --build-context mugsun-core=../mugsun-core -f deploy/docker/Dockerfile --target system .
# 带 -Pkingbase,dameng：镜像里带人大金仓和达梦的驱动，换库只改 JDBC_URL，不用重新构建，和单体版的镜像一致。
# 基础镜像固定摘要（多架构），和 mugsun-boot 用的是同一组；升级时标签和摘要一起改。
# 镜像里不放任何密钥：密钥在运行时用环境变量给，或者写在 MUGSUN_ENV_FILE 指的文件里，见 entrypoint.sh。
FROM scratch AS mugsun-core
ARG MUGSUN_CORE_REF=50c5843684c2f297f6aa37193fcc4e0c48ff12c6
ADD https://github.com/mugsun/mugsun-core.git#${MUGSUN_CORE_REF} /

FROM maven:3.9-eclipse-temurin-21@sha256:99e61abcff91a9b1333463bd8451fb18495d6eba9250ac66a338b518f8278320 AS build
WORKDIR /core
COPY --from=mugsun-core / ./
# core 装进镜像层里的独立仓库 /core-m2：不只放在缓存挂载里，缓存被清掉以后也能用
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -q install -DskipTests && mkdir -p /core-m2/com && cp -R /root/.m2/repository/com/mugsun /core-m2/com/
WORKDIR /build
COPY pom.xml ./
COPY mugsun-cloud-config/ mugsun-cloud-config/
COPY mugsun-cloud-common/ mugsun-cloud-common/
COPY mugsun-cloud-gateway/ mugsun-cloud-gateway/
COPY mugsun-cloud-service/ mugsun-cloud-service/
COPY mugsun-cloud-ops/ mugsun-cloud-ops/
# 六个包一次打出来，下面六个目标各取各的
RUN --mount=type=cache,target=/root/.m2 \
    mkdir -p /root/.m2/repository && cp -R /core-m2/com /root/.m2/repository/ \
    && mvn -B -q -Pkingbase,dameng package -DskipTests

FROM eclipse-temurin:21-jre-jammy@sha256:f04fb34e053148344e83317976114ec3f37e4b830ec8bdab5a2fe3cecd7d010b AS runtime
# 固定 uid/gid 10001：部署时按它给密钥文件和文件存储目录设属主
RUN groupadd -r -g 10001 mugsun && useradd -r -u 10001 -g mugsun -d /app mugsun \
    && mkdir -p /app/logs /var/lib/mugsun/files \
    && chown -R mugsun:mugsun /app /var/lib/mugsun
COPY --chmod=0555 deploy/docker/entrypoint.sh /usr/local/bin/mugsun-entrypoint
COPY --chmod=0555 deploy/docker/healthcheck.sh /usr/local/bin/mugsun-healthcheck
USER mugsun
WORKDIR /app
# 时区默认东八区。库里的时间列不带时区，按 JVM 的时区写入，所以各服务的时区要一致
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0" SPRING_PROFILES_ACTIVE=prod TZ=Asia/Shanghai
# 启动慢的时候（首次启动要建表）不算失败：先给 60 秒，之后连续 30 次检查不过才标成不健康
HEALTHCHECK --interval=10s --timeout=5s --start-period=60s --retries=30 CMD ["mugsun-healthcheck"]
ENTRYPOINT ["mugsun-entrypoint"]

FROM runtime AS gateway
COPY --from=build --chown=mugsun:mugsun /build/mugsun-cloud-gateway/target/mugsun-cloud-gateway.jar /app/app.jar
ENV MUGSUN_HEALTH_PORT=8080 MUGSUN_HEALTH_PATH=/_gateway/health
EXPOSE 8080

FROM runtime AS system
COPY --from=build --chown=mugsun:mugsun /build/mugsun-cloud-service/mugsun-cloud-system/target/mugsun-cloud-system.jar /app/app.jar
ENV MUGSUN_HEALTH_PORT=8101 MUGSUN_HEALTH_PATH=/ping
EXPOSE 8101

FROM runtime AS gis
COPY --from=build --chown=mugsun:mugsun /build/mugsun-cloud-service/mugsun-cloud-gis/target/mugsun-cloud-gis.jar /app/app.jar
ENV MUGSUN_HEALTH_PORT=8102 MUGSUN_HEALTH_PATH=/ping
EXPOSE 8102

FROM runtime AS track
COPY --from=build --chown=mugsun:mugsun /build/mugsun-cloud-service/mugsun-cloud-track/target/mugsun-cloud-track.jar /app/app.jar
ENV MUGSUN_HEALTH_PORT=8103 MUGSUN_HEALTH_PATH=/ping
EXPOSE 8103

FROM runtime AS ai
COPY --from=build --chown=mugsun:mugsun /build/mugsun-cloud-service/mugsun-cloud-ai/target/mugsun-cloud-ai.jar /app/app.jar
ENV MUGSUN_HEALTH_PORT=8104 MUGSUN_HEALTH_PATH=/ping
EXPOSE 8104

FROM runtime AS admin
COPY --from=build --chown=mugsun:mugsun /build/mugsun-cloud-ops/mugsun-cloud-admin/target/mugsun-cloud-admin.jar /app/app.jar
ENV MUGSUN_HEALTH_PORT=8200 MUGSUN_HEALTH_PATH=/actuator/health
EXPOSE 8200
