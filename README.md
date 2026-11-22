# mugsun-cloud

[更新日志](./CHANGELOG.md) | [从 mugsun-boot 同步代码](./docs/sync-from-boot.md) | [部署注意事项](./docs/deployment.md) | [和单体版不一样的地方](./docs/boot-vs-cloud.md)

mugsun 后端的微服务版。业务代码和单体版 [mugsun-boot](https://github.com/mugsun/mugsun-boot) 是同一份，这个仓库把它拆成几个服务，前面加一个网关。

接口路径和返回格式与单体版相同，管理后台 [mugsun-pc](https://github.com/mugsun/mugsun-pc) 照原样连网关的 8080 端口。

目前有四个业务服务：system 是单体版 `mugsun-boot-core` 的全部功能，gis、track、ai 分别是单体版的 GIS、埋点分析和 AI 模块。和单体版不一样的地方见[目前的限制](#目前的限制)。

## 组成

| 模块 | 端口 | 作用 |
| --- | --- | --- |
| `mugsun-cloud-gateway` | 8080 | 网关，对外唯一的入口，基于 Spring Cloud Gateway |
| `mugsun-cloud-auth` | 8100 | 登录服务：登录、注册、找回密码、验证码、第三方账号登录、开放授权（OAuth2）、在线用户，手机端的登录，从 system 里分出来的，接口路径不变。对应 BladeX 最外层的 `blade-auth` |
| `mugsun-cloud-service/mugsun-cloud-system` | 8101 | system 服务：账号权限、租户、工作流等，对应单体版的 `mugsun-boot-core` |
| `mugsun-cloud-service/mugsun-cloud-gis` | 8102 | gis 服务：`/system/gis/**` 下的全部接口，对应单体版的 `mugsun-boot-gis`。可以不部署 |
| `mugsun-cloud-service/mugsun-cloud-track` | 8103 | track 服务：埋点上报的 `/track/**` 和管理后台的 `/system/track/**`，对应单体版的 `mugsun-boot-track`。可以不部署 |
| `mugsun-cloud-service/mugsun-cloud-ai` | 8104 | ai 服务：管理后台的 `/system/ai/**` 和 OpenAI 兼容接口 `/v1/**`，对应单体版的 `mugsun-boot-ai`。可以不部署 |
| `mugsun-cloud-service/mugsun-cloud-desk` | 8111 | 工作台服务：通知公告和登录后首页的工作台（概览、快捷入口），手机端消息中心里的公告，从 system 里分出来的，接口路径不变 |
| `mugsun-cloud-ops/mugsun-cloud-log` | 8105 | 日志服务：操作、登录、通用、数据变更、访问、错误六类日志的查询和处理接口，从 system 里分出来的，接口路径不变 |
| `mugsun-cloud-ops/mugsun-cloud-report` | 8106 | 报表服务：报表的定义和预览接口，从 system 里分出来的，接口路径不变 |
| `mugsun-cloud-ops/mugsun-cloud-develop` | 8107 | 代码生成服务：代码生成、数据建模和在线表单，从 system 里分出来的，接口路径不变 |
| `mugsun-cloud-ops/mugsun-cloud-job` | 8108 | 定时任务服务：定时任务和调度中心的管理接口、PowerJob 执行器、按保留期清理日志，从 system 里分出来的 |
| `mugsun-cloud-ops/mugsun-cloud-resource` | 8109 | 文件和短信服务：文件上传下载、存储配置、头像、富文本图片、短信配置和发送，从 system 里分出来的 |
| `mugsun-cloud-ops/mugsun-cloud-flow` | 8110 | 审批流服务：流程设计、发起、待办、办理，手机端的待办，从 system 里分出来的 |
| `mugsun-cloud-ops/mugsun-cloud-admin` | 8200 | 监控中心：一个页面看到网关和各服务的每个实例在不在线，基于 Spring Boot Admin。可以不部署 |
| `mugsun-cloud-common` | 无 | 各服务都带的公共层：登录态、租户、数据权限、缓存、数据源、服务之间的调用等 |
| `mugsun-cloud-config` | 无 | 配置中心：启动时从 Nacos 读配置的组件，和读路由、灰度规则这类单份配置的公用代码。网关和公共层都依赖它 |

服务注册和发现、配置中心都用 Nacos。数据库（PostgreSQL）和 Redis 的用法与单体版相同，主库和埋点库的库表也是同一套。

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

哪些类进公共层、哪些留在 system、哪些分给别的服务（日志服务、报表服务这些）、哪个模块对应哪个服务，规则写在 `scripts/sync-rules.json`，说明见 [docs/sync-from-boot.md](./docs/sync-from-boot.md)。

## 用 Docker 起全套

只需要 Docker（Compose v2.17 以上），不用装 JDK 和 Node。一条命令起 PostgreSQL、Redis、Nacos、网关、四个业务服务、从 system 里分出来的几个服务（日志、报表等）、监控中心和管理后台：

```bash
git clone https://github.com/mugsun/mugsun-cloud.git && cd mugsun-cloud
docker compose -f deploy/docker/docker-compose.yml up -d --build
```

起来以后浏览器打开 <http://localhost:8188>，账号 `admin`。口令是首次启动时随机生成的，不写进日志，用下面的命令看：

```bash
docker compose -f deploy/docker/docker-compose.yml run --rm init show
```

- 镜像都在 Docker 里从源码构建，第一次要下载 Maven 和前端的依赖。镜像构建好以后，启动整套实测约 40 秒。
- 整套跑起来实测约占 3.5 GB 内存。从头构建管理后台的前端更占内存，Docker 至少分 8 GB：实测用到 7.3 GB，分 6 GB 时构建到一半被杀掉。Docker 的内存不宽裕时，先单独执行 `docker compose -f deploy/docker/docker-compose.yml build pc`，再执行上面的 `up`。
- 全部口令和密钥首次启动时随机生成，各服务按生产配置运行。映射到宿主机的只有两个端口，默认都只绑本机：管理后台 `127.0.0.1:8188`、监控中心 `127.0.0.1:8200`。监控中心的登录账号是例外，带公开的默认值，见[部署文档](./docs/deployment.md)第 18 节。
- 端口、网段和项目名都和单体版那套 Docker 编排错开。两套同时运行没有实测过。
- 清空重来用 `docker compose -f deploy/docker/docker-compose.yml down -v`，数据和密钥一起删。

能改的变量、密钥放在哪、重启时的表现、怎么只用镜像不用这份编排，见[部署注意事项](./docs/deployment.md)的第 16 节。

## 本地启动

开发时直接用 JDK 启动各服务。需要 JDK 21 和 Docker。mugsun-core、mugsun-boot 和本仓库放在同一个目录下。

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

生成一把服务之间互相调用用的密钥，下面四个业务服务都用这一个值：

```bash
openssl rand -hex 32
```

然后各开一个终端，依次启动 system、登录服务、gis、track、ai 和网关。system 要最先启动并等它启动完成：主库的库表由它来建，加密数据用的那几把密钥也由它首次生成。登录服务不启动就登录不了；可选的 gis、track、ai 可以不启动。

```bash
export MUGSUN_SERVICE_IP=127.0.0.1
export MUGSUN_INNER_SECRET='<上面生成的值>'
export MUGSUN_INIT_PASSWORD='<admin 的初始口令>'
java -jar mugsun-cloud-service/mugsun-cloud-system/target/mugsun-cloud-system.jar --spring.profiles.active=dev
```

```bash
export MUGSUN_SERVICE_IP=127.0.0.1
export MUGSUN_INNER_SECRET='<上面生成的值>'
java -jar mugsun-cloud-auth/target/mugsun-cloud-auth.jar --spring.profiles.active=dev
```

```bash
export MUGSUN_SERVICE_IP=127.0.0.1
export MUGSUN_INNER_SECRET='<上面生成的值>'
java -jar mugsun-cloud-service/mugsun-cloud-gis/target/mugsun-cloud-gis.jar --spring.profiles.active=dev
```

```bash
export MUGSUN_SERVICE_IP=127.0.0.1
export MUGSUN_INNER_SECRET='<上面生成的值>'
java -jar mugsun-cloud-service/mugsun-cloud-track/target/mugsun-cloud-track.jar --spring.profiles.active=dev
```

```bash
export MUGSUN_SERVICE_IP=127.0.0.1
export MUGSUN_INNER_SECRET='<上面生成的值>'
java -jar mugsun-cloud-service/mugsun-cloud-ai/target/mugsun-cloud-ai.jar --spring.profiles.active=dev
```

```bash
export MUGSUN_SERVICE_IP=127.0.0.1
export MUGSUN_INNER_SECRET='<上面生成的值>'
java -jar mugsun-cloud-service/mugsun-cloud-desk/target/mugsun-cloud-desk.jar --spring.profiles.active=dev
```

```bash
export MUGSUN_SERVICE_IP=127.0.0.1
export MUGSUN_INNER_SECRET='<上面生成的值>'
java -jar mugsun-cloud-ops/mugsun-cloud-log/target/mugsun-cloud-log.jar --spring.profiles.active=dev
```

```bash
export MUGSUN_SERVICE_IP=127.0.0.1
export MUGSUN_INNER_SECRET='<上面生成的值>'
java -jar mugsun-cloud-ops/mugsun-cloud-report/target/mugsun-cloud-report.jar --spring.profiles.active=dev
```

```bash
export MUGSUN_SERVICE_IP=127.0.0.1
export MUGSUN_INNER_SECRET='<上面生成的值>'
java -jar mugsun-cloud-ops/mugsun-cloud-develop/target/mugsun-cloud-develop.jar --spring.profiles.active=dev
```

```bash
export MUGSUN_SERVICE_IP=127.0.0.1
export MUGSUN_INNER_SECRET='<上面生成的值>'
java -jar mugsun-cloud-ops/mugsun-cloud-job/target/mugsun-cloud-job.jar --spring.profiles.active=dev
```

```bash
export MUGSUN_SERVICE_IP=127.0.0.1
export MUGSUN_INNER_SECRET='<上面生成的值>'
java -jar mugsun-cloud-ops/mugsun-cloud-resource/target/mugsun-cloud-resource.jar --spring.profiles.active=dev
```

```bash
export MUGSUN_SERVICE_IP=127.0.0.1
export MUGSUN_INNER_SECRET='<上面生成的值>'
java -jar mugsun-cloud-ops/mugsun-cloud-flow/target/mugsun-cloud-flow.jar --spring.profiles.active=dev
```

```bash
export MUGSUN_SERVICE_IP=127.0.0.1
java -jar mugsun-cloud-gateway/target/mugsun-cloud-gateway.jar
```

```bash
export MUGSUN_SERVICE_IP=127.0.0.1
java -jar mugsun-cloud-ops/mugsun-cloud-admin/target/mugsun-cloud-admin.jar
```

`curl http://localhost:8080/ping` 有响应就是网关和 system 都通了。gis、track 和 ai 服务可以不启动，见[可选服务可以不部署](#可选服务可以不部署)。日志服务（端口 8105）不启动时，管理后台的六个日志页面打不开（网关返回 503），各服务照常记日志，见[部署文档](./docs/deployment.md)第 19 节。从 system 里分出来的其它服务（报表等）不启动时，对应的页面打不开，见第 20 节。

最后一条命令启动的是监控中心，也可以不启动。启动后浏览器打开 <http://localhost:8200>，能看到各实例在不在线；登录账号见[部署文档](./docs/deployment.md)第 18 节。

system 的接口文档在 <http://localhost:8080/swagger-ui/index.html>。其它服务的接口文档在它们自己的端口上，路径相同。

| 要看什么 | 命令 |
| --- | --- |
| 网关自己是否正常 | `curl http://localhost:8080/_gateway/health` |
| 哪些服务注册上了 | `deploy/dev/nacos.sh status` |
| 停掉 Nacos | `deploy/dev/nacos.sh down` |

几点说明：

- `MUGSUN_SERVICE_IP` 是服务注册到 Nacos 的地址。不设置时自动取一块网卡的地址，开着 VPN 或有多块网卡时可能取错，网关就连不上服务。本机开发建议写成 `127.0.0.1`。
- `MUGSUN_INIT_PASSWORD` 只在首次建库时用来创建 `admin`，和单体版一样。
- `MUGSUN_INNER_SECRET` 是服务之间互相调用用的密钥，四个业务服务要一样，网关不用。不设置时服务照常启动，只是跨服务的功能用不了，见[服务之间的调用](#服务之间的调用)。
- 各业务服务都要显式带 `--spring.profiles.active=dev`。没配 profile 按生产处理，缺密钥会拒绝启动。
- 埋点库 `mugsun_track` 由 track 服务迁移。单体版的 compose 已经把这个库建好；用自己的 PostgreSQL 时，库不存在 track 会自己建，数据库账号要有 `CREATEDB` 权限。
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
| `MUGSUN_CONFIG_CENTER` | 写 `false` 关掉配置中心：网关和各服务启动时不到 Nacos 读配置 | 空，即开着 |
| `MUGSUN_SERVICE_IP` | 服务注册到 Nacos 的地址 | 自动取网卡地址 |
| `MUGSUN_OPENAPI_TRUSTED_PROXIES` | 网关前面的反向代理的地址段。配给网关，不再配给后端服务 | 空，即网关直接面向客户端 |
| `MUGSUN_INNER_SECRET` | 服务之间互相调用用的密钥，至少 32 个字符。各业务服务配成同一个值，网关不用配 | 空，即不开通服务之间的调用 |
| `MUGSUN_GATEWAY_ACCESS_LOG` | 网关的访问日志记哪些请求：`all` 每个都记，`problems` 只记 5xx、转发出错和慢请求，`none` 不记 | `all` |
| `MUGSUN_GATEWAY_SLOW_MILLIS` | 网关从收到请求到开始回应达到多少毫秒算慢请求；`0` 表示不按耗时区分 | `3000` |
| `MUGSUN_GATEWAY_DENY_IPS` | 网关的黑名单：这些地址段发来的请求不转发，返回 403 | 空，即不限制 |
| `MUGSUN_GATEWAY_ALLOW_IPS` | 网关的白名单：填了以后只转发这些地址段发来的请求 | 空，即不限制 |
| `MUGSUN_GATEWAY_RESTRICTED_PATHS` | 不对外开放的路径前缀，例如 `/actuator`，多个用逗号分隔 | 空，即没有 |
| `MUGSUN_GATEWAY_RESTRICTED_ALLOW_IPS` | 能访问上面这些路径的地址段 | 空，即谁都不能经网关访问这些路径 |
| `MUGSUN_GATEWAY_RATE_LIMIT` | 网关限流的默认限额：每个客户端地址一段时间里最多发多少个请求，写成 `次数/时长`，例如 `600/60s` | 空，即不限流 |
| `MUGSUN_GATEWAY_RATE_LIMIT_PATHS` | 给个别路径单独定限额，例如 `/auth/login=20/60s`，多个用逗号分隔；右边写 `unlimited` 表示这个路径不限流 | 空，即没有 |
| `MUGSUN_GATEWAY_RATE_LIMIT_EXEMPT_IPS` | 不限流的地址段 | 空，即没有 |
| `MUGSUN_GATEWAY_DYNAMIC_ROUTES` | 写 `true` 让网关另外从 Nacos 的配置 `mugsun-gateway-routes.yaml` 里读路由，改了不用重启网关 | `false` |
| `MUGSUN_GATEWAY_LANES` | 写 `true` 让网关从 Nacos 的配置 `mugsun-gateway-lanes.yaml` 里读灰度规则：哪些请求发给灰度实例。改了不用重启网关 | `false` |
| `MUGSUN_LANE` | 配给一个服务的某个实例，值是灰度标记，例如 `canary`。配了的是灰度实例，只接带着这个标记的请求 | 空，即正式实例 |

配置项也可以放在 Nacos 里（配置中心）：网关和各服务启动时到 Nacos 读 `mugsun.yaml`（大家共用）和 `<服务名>.yaml`（比如 `mugsun-system.yaml`），带着 `dev`、`test`、`prod` 启动时再读 `mugsun-<环境>.yaml` 和 `<服务名>-<环境>.yaml`。这几份都可以不建。里面的配置项盖过包里的配置文件；命令行参数、名字和配置项对得上的环境变量（比如上面网关的那些开关）又盖过它们。包里的配置文件用 `${变量}` 引用、名字对不上的环境变量（比如 `JDBC_URL`）会被 Nacos 里的同一项盖过。谁盖过谁的细节、改了以后什么时候生效、写错了会怎样、Nacos 的账号要什么权限，见[部署注意事项](./docs/deployment.md)第 17 节。

上线前先看[部署注意事项](./docs/deployment.md)，里面是微服务版和单体版不一样、配错会出安全问题的地方。

## 可选服务可以不部署

gis、track 和 ai 三个服务可以不部署。

| 服务 | 部署了 | 没部署 |
| --- | --- | --- |
| gis | 管理后台显示 GIS 菜单，`/system/gis/**` 由网关转给它 | 菜单里没有 GIS，登录信息里 `gisEnabled` 为 `false`，`/system/gis/**` 返回 503 |
| track | 管理后台显示埋点分析菜单，`/track/**` 和 `/system/track/**` 由网关转给它 | 菜单里没有埋点分析，登录信息里 `trackEnabled` 为 `false`，这两类路径返回 503 |
| ai | 管理后台显示 AI 菜单，`/system/ai/**` 和 `/v1/**` 由网关转给它 | 菜单里没有 AI，登录信息里 `aiEnabled` 为 `false`，这两类路径返回 503 |

system（管菜单）和登录服务（管登录信息）按注册中心里有没有对应服务的实例来判断。服务启动后几秒内菜单出现；停掉后过一分钟菜单才消失，这样重启服务时菜单不会闪。

单体版的开关照样可用：

- 系统参数 `gis.module.enabled`、`ai.module.enabled` 设成 `false`，运行时关掉 GIS 或 AI，不用停服务。埋点没有对应的系统参数。
- 给 system 和登录服务都设置 `MUGSUN_GIS_ENABLED=false`、`MUGSUN_TRACK_ENABLED=false` 或 `MUGSUN_AI_ENABLED=false`，两边就当这个模块不存在：菜单隐藏，登录信息里对应的开关为 `false`。只设一边的话两处会对不上。
- gis、track 和 ai 服务自己不接受这几个值，带着 `false` 会拒绝启动：不需要某个模块就不要部署它的服务。

## 服务之间的调用

有三个功能要一个服务去调另一个服务：

| 功能 | 谁调谁 | 对方没部署时 |
| --- | --- | --- |
| AI 问图层、AI 解读埋点数据 | ai 向 gis、track 取库里的实数 | 提示对应的服务现在不在线、查不了 |
| 埋点分析的「问数据」用大模型解析提问 | track 调 ai | 只按规则解析提问，和单体版没装 AI 模块时一样 |
| 代码生成的建模草稿用大模型出 | develop 调 ai | 按规则解析描述，和单体版没装 AI 模块时一样 |

对方部署了、但此刻一个实例都没有（正在重启，或者挂了）的时候也是上面这一列：发起调用的服务只看得到注册中心里有没有它的实例，分不出是哪一种。

这些调用走各服务 `/inner` 开头的内部接口，直接连对方的端口，不经网关。请求和响应都用 `MUGSUN_INNER_SECRET` 签名。带过去的身份只有租户，不带用户的登录令牌；用户有没有权限用这个功能，由发起调用的服务先判断。

没配 `MUGSUN_INNER_SECRET` 时服务照常启动，只是不接受也不发起内部调用：AI 问图层、解读埋点数据会提示内部调用没有开通，另外两个功能改用规则。配置要求和排查办法见[部署注意事项](./docs/deployment.md)的第 4 节。

对方连得上、但是不回话或者忙不过来的时候，有三道保护，默认都开着：

- 熔断：接连失败 3 次以后，10 秒内不再调它，直接给出提示，用户不用每次都等到超时；之后放一个请求去试，对方好了自动恢复。按实例、接口、租户分开记，一个租户自己的故障不连累别的租户。
- 同时处理的个数有上限：每个内部接口同时最多处理 6 个请求（ai 是 64 个），其中同一个租户最多占 5 个（ai 是 48 个），再来的排队 2 秒，还轮不到就回「忙」，页面提示服务正忙。一个接口的查询卡住时，占不完这个服务的数据库连接，它别的接口照常用；一个租户一下子发来很多慢请求时，占不满这个接口，留着的名额别的租户来了马上能用。
- 查询有时限：调用方不等了，对方为这次调用发出的查询也跟着停下，不再占着数据库连接。

实测的效果、能调的配置项和日志里的话，见同一节的「对方出了问题时的三道保护」。

## 网关做了什么

- 按路径把请求转给对应的服务，路径不改写。`/system/gis/**` 转给 gis，`/track/**` 和 `/system/track/**` 转给 track，`/system/ai/**` 和 `/v1/**` 转给 ai，其余全部转给 system。WebSocket 也经网关转发。
- 算出客户端的真实地址写进 `X-Forwarded-For` 等请求头。客户端自己带来的这类请求头一律删掉，只有来自可信代理的才采信。
- 拦下以 `/inner` 开头的路径。这个前缀留给服务之间互相调用的内部接口，不对外。
- 统一应答跨域请求，规则和单体版一致。
- 服务连不上或没有可用实例时，按统一的返回格式给出 502、503 或 504。一个服务有几个实例时，连不上其中一个就换一个实例再转发一次。
- 给每个请求定一个请求编号，放在 `X-Trace-Id` 请求头里传给服务，响应头里也有。服务的日志带着同一个编号，排查时拿它把网关和服务的日志对上。
- 每个请求记一行访问日志：客户端地址、路径、状态码、耗时、转给了哪个实例。不记查询串。各字段的含义见[部署注意事项](./docs/deployment.md)第 10 节「网关的访问日志」。
- 可以按客户端地址限制访问：黑名单、白名单，以及只对指定地址开放的路径（比如 `/actuator`）。默认不限制，配法见[部署注意事项](./docs/deployment.md)第 10 节「按客户端地址限制访问」。
- 可以限流：按客户端地址数请求，超过限额的不转发，返回 429 和 `Retry-After`。登录、发短信这类路径可以单独定更严的限额。计数在每个网关实例自己的内存里，网关不用连 Redis；起了几个网关就各数各的。默认不限流，配法和要注意的地方见[部署注意事项](./docs/deployment.md)第 10 节「限流」。
- 可以不重启就改路由：打开以后，网关另外从 Nacos 的一份配置里读路由，改了马上生效；写错了整份不采用，继续按原来的转发。默认不开。能写什么、接入 Nacos 的账号要加什么权限，见[部署注意事项](./docs/deployment.md)第 10 节「改路由不用重启网关」。
- 可以灰度发布：新版本的实例配上灰度标记，和老版本一起跑，网关按 Nacos 里的规则分一部分请求给它：按客户端地址点名、按访问的域名、按比例。服务之间的调用跟着同一个标记走。规则改了马上生效，写错了整份不采用；删掉规则，所有请求回到老版本。默认没有规则。配法、实测的效果和要注意的地方见[部署注意事项](./docs/deployment.md)第 10 节「灰度发布」。

登录校验和权限判断仍然在服务里做，和单体版是同一套代码，网关不解析令牌。

## 目前的限制

- 审批流里的 AI 预审节点在这个版本里用不了。流程要走到这种节点时，发起、审批通过、退回、撤回都会被拒绝，流程留在原来的位置；带这种节点的流程要先在流程设计里去掉它再用。从单体版迁过来之前先看[部署注意事项](./docs/deployment.md)的第 15 节。
- 服务之间的调用只签名，不加密，各服务共用一把密钥。服务端口不能让网关和其它服务以外的机器访问到。
- 内部接口留给别的租户的名额是合用的，不是每个租户各有一份：默认一个接口 6 个名额，一个租户占着 5 个、第二个租户占着剩下的 1 个时，第三个租户在这个接口上会得到「服务正忙」。要多留几个可以调配置，见[部署注意事项](./docs/deployment.md)第 4 节。
- 内部调用的查询时限只管经 MyBatis 发出的查询。给 AI 解读埋点数据用的统计是用 `JdbcTemplate` 查的，不受它管，卡住时要等查询自己结束。
- PowerJob 执行器只在定时任务服务上启动。埋点的三个 PowerJob 处理器在这个版本里用不了，替代办法见[部署注意事项](./docs/deployment.md)。
- 只读演示模式（`demo` profile）没有在微服务版里验证过。
- 在 system 里改了数据权限规则、接口权限、租户数据源，或者停用了租户、改了套餐，处理保存的那个实例立即生效，其它实例约 1 秒后生效。见[部署注意事项](./docs/deployment.md)的第 7 节。
- system 可以部署多个实例，要注意的事项在同一节。gis、track 和 ai 部署多个实例没有验证过。
- 灰度发布只能按客户端地址、访问的域名和比例分，不能按租户或用户分：网关不解析登录令牌。前端页面不在灰度的范围里。灰度实例是这个服务多出来的一个实例，上一条对它一样适用。
- mugsun-boot 的测试没有同步过来，这里只有微服务版自己那部分代码的测试。业务逻辑的测试在 mugsun-boot 里跑。
- 配置中心里改的配置，运行中的实例都收得到，但马上生效的只有日志级别这一类。网关的开关和数据库连接要重启才生效，其它配置项没有逐项测过，按要重启对待。存进去的配置没有校验：值写错了运行中看不出来，重启时服务起不来。启动时 Nacos 连不上、本地又没有缓存时，服务会用包里的配置起来，不报错。见[部署注意事项](./docs/deployment.md)第 17 节。
- Docker 编排是单机的：每个服务默认一个实例（system 可以用 `MUGSUN_SYSTEM_REPLICAS` 起多个），Nacos 是单机模式，数据库和 Redis 没有主备。
- 镜像没有发布到镜像仓库，要用就从源码构建。没有 CI。

## 相关仓库

| 仓库 | 内容 |
| --- | --- |
| [mugsun-boot](https://github.com/mugsun/mugsun-boot) | 后端单体版，业务代码在那边维护 |
| [mugsun-core](https://github.com/mugsun/mugsun-core) | 后端基础库 |
| [mugsun-pc](https://github.com/mugsun/mugsun-pc) | 管理后台 |

## 许可

[MIT](./LICENSE)。第三方组件的声明见 [NOTICE](./NOTICE)。
