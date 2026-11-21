# mugsun-cloud

[更新日志](./CHANGELOG.md) | [从 mugsun-boot 同步代码](./docs/sync-from-boot.md) | [部署注意事项](./docs/deployment.md)

mugsun 后端的微服务版。业务代码和单体版 [mugsun-boot](https://github.com/mugsun/mugsun-boot) 是同一份，这个仓库把它拆成几个服务，前面加一个网关。

接口路径和返回格式与单体版相同，管理后台 [mugsun-pc](https://github.com/mugsun/mugsun-pc) 照原样连网关的 8080 端口。

目前有两个业务服务：system 是单体版 `mugsun-boot-core` 的全部功能，gis 是单体版的 GIS 模块。埋点分析和 AI 还没有拆过来，见[目前的限制](#目前的限制)。

## 组成

| 模块 | 端口 | 作用 |
| --- | --- | --- |
| `mugsun-cloud-gateway` | 8080 | 网关，对外唯一的入口，基于 Spring Cloud Gateway |
| `mugsun-cloud-service/mugsun-cloud-system` | 8101 | system 服务：账号权限、租户、工作流等，对应单体版的 `mugsun-boot-core` |
| `mugsun-cloud-service/mugsun-cloud-gis` | 8102 | gis 服务：`/system/gis/**` 下的全部接口，对应单体版的 `mugsun-boot-gis`。可以不部署 |
| `mugsun-cloud-common` | 无 | 各服务都带的公共层：登录态、租户、数据权限、缓存、数据源等 |

后面要拆出的服务和预留的端口：track 8103、ai 8104。

服务注册和发现用 Nacos。数据库（PostgreSQL）和 Redis 的用法与单体版相同，主库的库表也是同一套。

版本：JDK 21、Spring Boot 3.5.13、Spring Cloud 2025.0.3、Spring Cloud Alibaba 2025.0.0.0。

## 代码从哪来

每个模块里有两个源码目录。

| 目录 | 内容 | 怎么改 |
| --- | --- | --- |
| `src/boot/` | 从 mugsun-boot 的一个确定提交原样复制的业务代码，包名仍是 `com.mugsun.boot` | 不要手改。改 mugsun-boot，再重新同步 |
| `src/main/` | 微服务版自己的代码，包名 `com.mugsun.cloud` | 直接在这里写 |

同步用 `scripts/sync_from_boot.py`，当前同步到的提交记在 `boot-sync.lock.json`。

```bash
python3 scripts/sync_from_boot.py            # 从 ../mugsun-boot 的 HEAD 同步
python3 scripts/sync_from_boot.py --check    # 只比较，有差异时退出码为 1
```

哪些类进公共层、哪些留在 system、哪个模块对应哪个服务，规则写在 `scripts/sync-rules.json`，说明见 [docs/sync-from-boot.md](./docs/sync-from-boot.md)。

## 本地启动

需要 JDK 21 和 Docker。mugsun-core、mugsun-boot 和本仓库放在同一个目录下。

```bash
# 1. mugsun-core 的 0.2.0-SNAPSHOT 不在 Maven Central，先装到本地
git clone https://github.com/mugsun/mugsun-core.git
(cd mugsun-core && mvn -B clean install -DskipTests)

# 2. 数据库和 Redis 用单体版的 compose：PostgreSQL 5432、Redis 6379
git clone https://github.com/mugsun/mugsun-boot.git
(cd mugsun-boot && docker compose up -d)

# 3. 注册中心和打包
git clone https://github.com/mugsun/mugsun-cloud.git && cd mugsun-cloud
deploy/dev/nacos.sh up
./mvnw -B package -DskipTests
```

然后开三个终端，依次启动 system、gis 和网关。system 要最先启动并等它启动完成：库表由它来建，密钥也由它首次生成。

```bash
export MUGSUN_SERVICE_IP=127.0.0.1
export MUGSUN_INIT_PASSWORD='<admin 的初始口令>'
java -jar mugsun-cloud-service/mugsun-cloud-system/target/mugsun-cloud-system.jar --spring.profiles.active=dev
```

```bash
export MUGSUN_SERVICE_IP=127.0.0.1
java -jar mugsun-cloud-service/mugsun-cloud-gis/target/mugsun-cloud-gis.jar --spring.profiles.active=dev
```

```bash
export MUGSUN_SERVICE_IP=127.0.0.1
java -jar mugsun-cloud-gateway/target/mugsun-cloud-gateway.jar
```

`curl http://localhost:8080/ping` 有响应就是网关和 system 都通了。gis 服务可以不启动，见 [gis 服务可以不部署](#gis-服务可以不部署)。接口文档在 <http://localhost:8080/swagger-ui/index.html>。

| 要看什么 | 命令 |
| --- | --- |
| 网关自己是否正常 | `curl http://localhost:8080/_gateway/health` |
| 哪些服务注册上了 | `deploy/dev/nacos.sh status` |
| 停掉 Nacos | `deploy/dev/nacos.sh down` |

几点说明：

- `MUGSUN_SERVICE_IP` 是服务注册到 Nacos 的地址。不设置时自动取一块网卡的地址，开着 VPN 或有多块网卡时可能取错，网关就连不上服务。本机开发建议写成 `127.0.0.1`。
- `MUGSUN_INIT_PASSWORD` 只在首次建库时用来创建 `admin`，和单体版一样。
- system 和 gis 服务要显式带 `--spring.profiles.active=dev`。没配 profile 按生产处理，缺密钥会拒绝启动。
- 网关默认端口和单体版一样是 8080，同一台机器上两个不能都用默认端口。
- 本地的个性化配置写在启动目录下的 `config/application-local.yml`，这个文件不进仓库。模板在 mugsun-boot 的 `config/application-local.yml.example`。

## 配置

数据库、Redis、密钥等变量和单体版相同，见 mugsun-boot 的[配置文档](https://docs.mugsun.com/boot/configuration)。微服务版多出下面几项。

| 变量 | 作用 | 默认值 |
| --- | --- | --- |
| `MUGSUN_NACOS_ADDR` | Nacos 地址 | `127.0.0.1:8848` |
| `MUGSUN_NACOS_USERNAME` `MUGSUN_NACOS_PASSWORD` | Nacos 开了鉴权时的账号 | 空 |
| `MUGSUN_NACOS_NAMESPACE` | Nacos 命名空间 | 空，即 public |
| `MUGSUN_NACOS_GROUP` | Nacos 分组 | `DEFAULT_GROUP` |
| `MUGSUN_SERVICE_IP` | 服务注册到 Nacos 的地址 | 自动取网卡地址 |
| `MUGSUN_OPENAPI_TRUSTED_PROXIES` | 网关前面的反向代理的地址段。配给网关，不再配给后端服务 | 空，即网关直接面向客户端 |

上线前先看[部署注意事项](./docs/deployment.md)，里面是微服务版和单体版不一样、配错会出安全问题的地方。

## gis 服务可以不部署

| 情况 | 表现 |
| --- | --- |
| 部署了 gis 服务 | 管理后台显示 GIS 菜单，`/system/gis/**` 由网关转给 gis 服务 |
| 没部署 | 菜单里没有 GIS，登录信息里 `gisEnabled` 为 `false`，访问 `/system/gis/**` 返回 503 |

system 按注册中心里有没有 gis 服务的实例来判断。gis 服务启动后几秒内菜单出现；停掉后过一分钟菜单才消失，这样重启 gis 服务时菜单不会闪。

单体版的两个开关照样可用：

- 系统参数 `gis.module.enabled` 设成 `false`，运行时关掉 GIS，不用停服务。
- 给 system 设置 `MUGSUN_GIS_ENABLED=false`，system 就当 GIS 不存在。gis 服务自己不接受这个值，会拒绝启动：不需要 GIS 就不要部署它。

## 网关做了什么

- 按路径把请求转给对应的服务，路径不改写。`/system/gis/**` 转给 gis，其余全部转给 system。WebSocket 也经网关转发。
- 算出客户端的真实地址写进 `X-Forwarded-For` 等请求头。客户端自己带来的这类请求头一律删掉，只有来自可信代理的才采信。
- 拦下以 `/inner` 开头的路径。这个前缀留给服务之间互相调用的内部接口，不对外。
- 统一应答跨域请求，规则和单体版一致。
- 服务连不上或没有可用实例时，按统一的返回格式给出 502、503 或 504。

登录校验和权限判断仍然在服务里做，和单体版是同一套代码，网关不解析令牌。

## 目前的限制

- 埋点分析和 AI 还没有拆过来，这两个模块的接口在这个版本里不存在。
- 还没有服务之间的调用和内部接口的鉴权。gis 服务用不到，等拆埋点分析时再加。服务端口不能让网关以外的机器访问到。
- 在 system 里改了数据权限规则、接口权限或租户数据源，其它服务最长 30 秒后生效，见[部署注意事项](./docs/deployment.md)。
- system 服务目前按单实例部署：上面这几样在一个 system 实例上改了，其它 system 实例不会跟着生效。
- mugsun-boot 的测试没有同步过来，这里只有微服务版自己那部分代码的测试。业务逻辑的测试在 mugsun-boot 里跑。
- 配置在各服务的包里和环境变量里，没有接 Nacos 配置中心。
- 没有 Docker 镜像和 CI。

## 相关仓库

| 仓库 | 内容 |
| --- | --- |
| [mugsun-boot](https://github.com/mugsun/mugsun-boot) | 后端单体版，业务代码在那边维护 |
| [mugsun-core](https://github.com/mugsun/mugsun-core) | 后端基础库 |
| [mugsun-pc](https://github.com/mugsun/mugsun-pc) | 管理后台 |

## 许可

[MIT](./LICENSE)。第三方组件的声明见 [NOTICE](./NOTICE)。
