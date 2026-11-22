# AGENTS.md — mugsun-cloud

本文件写给在本仓工作的开发者和 AI 助手。内容以本仓代码为准；与代码不一致时以代码为准，并请顺手修正本文件。

## 仓库是什么

mugsun 后端的微服务版。业务代码来自 [mugsun-boot](https://github.com/mugsun/mugsun-boot)，架构是网关加若干服务，Nacos 做注册中心。

依赖 [mugsun-core](https://github.com/mugsun/mugsun-core) 的 `0.2.0-SNAPSHOT`，Maven Central 上没有，先在 `../mugsun-core` 执行 `mvn -B install -DskipTests`。

| 模块 | 端口 | 作用 |
| --- | --- | --- |
| `mugsun-cloud-config` | 无 | 配置中心：带进 Nacos Config 组件，另有读整份配置的 `NacosConfigSource`。不带网页栈，网关和公共层都可以依赖 |
| `mugsun-cloud-common` | 无 | 各服务都带的公共层，Servlet 栈 |
| `mugsun-cloud-gateway` | 8080 | 网关，WebFlux 栈，不依赖公共层 |
| `mugsun-cloud-service/mugsun-cloud-system` | 8101 | system 服务，对应 `mugsun-boot-core` |
| `mugsun-cloud-service/mugsun-cloud-gis` | 8102 | gis 服务，对应 `mugsun-boot-gis`，可以不部署 |
| `mugsun-cloud-service/mugsun-cloud-track` | 8103 | track 服务，对应 `mugsun-boot-track`，可以不部署 |
| `mugsun-cloud-service/mugsun-cloud-ai` | 8104 | ai 服务，对应 `mugsun-boot-ai`，可以不部署 |
| `mugsun-cloud-ops/mugsun-cloud-log` | 8105 | 日志服务：六类日志的查询和处理接口，从 `mugsun-boot-core` 里分出来的，Servlet 栈，依赖公共层 |
| `mugsun-cloud-ops/mugsun-cloud-admin` | 8200 | 监控中心，Spring Boot Admin，WebFlux 栈，不依赖公共层，可以不部署 |

服务名是 `mugsun-<名字>`，如 `mugsun-system`。

`mugsun-cloud-ops` 下面放运维用的应用，一个应用一个子模块，对应 BladeX 的 `blade-ops`。监控中心不放业务代码，不依赖公共层；日志服务是从 core 里分出来的业务服务，写法和 `mugsun-cloud-service` 下面的服务一样，下面「服务的约定」对它都适用。监控中心从 Nacos 找实例，直接请求每个实例的健康检查：健康检查不在 `/actuator/health` 的应用（现在只有网关），要在注册到 Nacos 的 `metadata` 里写 `management.context-path`，不然在监控页面上一直是不在线。

各服务 `/actuator` 下面的监控接口全部打开，谁能访问由公共层的 `MonitorAccessFilter`（`com.mugsun.cloud.common.monitor`）管：内网里直接连服务端口的全部能看，不用登录；经网关来的只有 `THROUGH_GATEWAY` 里那几个，并且照旧要过同步来的 `ActuatorAuthFilter`。要让一个监控接口经网关也能访问，把它加进 `THROUGH_GATEWAY`。`ActuatorAuthFilter` 不再由容器直接调用（`MonitorAccessConfiguration`），不要把它改回去。它靠请求带不带转发头来分辨是不是经网关来的，所以网关给每个转发的请求写转发头这件事（`ClientAddressFilter`）不能去掉。

`mugsun-cloud-config` 里不要加 Servlet 或 WebFlux 的依赖，也不放业务代码。它的包 `com.mugsun.cloud.config` 不在网关和各服务扫描的范围里，要生效的配置类登记在它的 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` 里。加了新的模块目录，`deploy/docker/Dockerfile` 和 `Dockerfile.dockerignore` 里要各补一行，不然镜像构建找不到它。

网关和各服务都要有的日志打码也在这个模块（`com.mugsun.cloud.config.logging`）：`LogSecretsMasking` 在日志写出去之前把 `LogSecrets` 认得的几种凭证换成 `***`，登记在 `META-INF/spring.factories`，没有开关。它只是兜底，代码里照样不要往日志里记凭证。要多认一种写法时改 `LogSecrets`，在 `LogSecretsTest` 里补用例。测试里要断言「日志里没有某个值」时，不要只用会被打码的写法（比如 `token=` 后面的值）：那个值就算被记了也查不出来，要另带一个不会被打码的值，参考 `GatewayAccessLogTest`。

## 两类代码

| 目录 | 包名 | 规则 |
| --- | --- | --- |
| `src/boot/` | `com.mugsun.boot` | 由 `scripts/sync_from_boot.py` 从 mugsun-boot 原样复制，不要手改 |
| `src/main/` | `com.mugsun.cloud` | 微服务版自己的代码 |

改动该写在哪边：

- 业务逻辑、库表、接口：在 mugsun-boot 改，再同步过来。那边的分层、租户、数据权限、Flyway 等约定见 mugsun-boot 的 `AGENTS.md`。
- 网关、注册发现、服务之间的调用、各服务的启动类和配置：在这里改。
- 微服务版需要业务代码配合调整时（比如把一个类拆开才分得了层），也在 mugsun-boot 改，并保持单体版的行为不变。

同步的用法和划分规则见 [docs/sync-from-boot.md](./docs/sync-from-boot.md)。那里列了几处脚本管不到、要手工跟着 mugsun-boot 改的地方。

两边代码不一样的地方列在 [docs/boot-vs-cloud.md](./docs/boot-vs-cloud.md)：同一个接口两边各有一个实现的、业务上做法不同的、只有这边才有的类。这一页由 `scripts/boot_cloud_diff.py` 生成，不要手改。在 `src/main` 里新写、删掉了类，或者改了类注释的第一句，都要重新生成。新写的类实现了同步来的接口时，先在 `scripts/boot-cloud-diff.json` 的 `seams` 里写明为什么另写、怎么保证和单体版一样、哪个测试守着；新写的类改变了业务的做法时，在 `differs` 里加一条。

## 配置中心的约定

网关和各服务启动时用 Nacos Config 从 Nacos 读配置项，运行中跟着改动刷新。怎么用、实测过什么，见 [docs/deployment.md](./docs/deployment.md) 第 17 节。

- 读哪几份写在每个应用自己的 `application.yml` 里：第一段导入 `mugsun.yaml` 和 `<服务名>.yaml`，最后三段按 `dev`、`test`、`prod` 各导入 `mugsun-<环境>.yaml` 和 `<服务名>-<环境>.yaml`，都带 `optional:`。新加服务时照抄、换掉服务名，`ConfigCenterImportsTest` 会查每个应用都写齐了。
- 这几段导入不能挪进共用的 `mugsun-cloud-defaults.yml`：挪过去以后，服务自己的第二段和 `application-<profile>.yml` 里写了的项，Nacos 的值盖不住。
- 连 Nacos 用的设置（地址、账号、命名空间、分组、开关 `MUGSUN_CONFIG_CENTER`），各服务的在 `mugsun-cloud-defaults.yml`，网关的在它自己的 `application.yml`，两处要一致。`preference` 保持 `remote`，`management.health.refresh.enabled` 保持 `false`，原因写在那两个文件的注释里。
- 不起 Nacos 的 `@SpringBootTest` 要同时写 `spring.cloud.nacos.discovery.enabled=false` 和 `spring.cloud.nacos.config.enabled=false`。只关前一个，测试启动时会去连本机的 8848 端口读配置：本机正好开着 Nacos 的话，读到什么就用什么。
- 谁盖过谁（实测）：命令行参数、名字和配置项对得上的环境变量比 Nacos 优先，变量传进了进程、值是空的也算；配置文件里用 `${变量}` 引用、名字对不上的环境变量（如 `JDBC_URL`）会被 Nacos 里的同一项盖过。给配置项起环境变量名、往编排里传变量之前，先想清楚要不要让配置中心改得了它。
- 整份的配置（动态路由、灰度规则）不走这里，仍然只由 `NacosConfigSource` 读，见下面「网关的约定」。
- 「改了以后什么时候生效」以第 17 节的表为准，表里每一行都是实测过的。让一个配置项改成马上生效时，先在真的 Nacos 上测过，再改那张表。
- 接入 Nacos 的账号只能读配置，不能改。能改这几份配置的人等于能改每个服务的任何配置项，包括连哪个数据库：给这个账号加权限、改 `deploy/docker/nacos-init.py` 里的权限，属于敏感改动。

## 网关的约定

代码在 `mugsun-cloud-gateway/src/main/java/com/mugsun/cloud/gateway`。

- `application.yml` 里的路由不改写路径，对外的接口路径和单体版一致。路由按顺序匹配，范围小的写在前面，`mugsun-system` 兜底。
- 过滤器要拒绝请求时，返回 `Mono.error(new GatewayRejectedException(状态码, 说明))`，由 `GatewayErrorHandler` 写响应；要带响应头（比如 `Retry-After`）时用带 `HttpHeaders` 的那个构造方法。不要在过滤器里自己写响应：排在前面的过滤器还要改响应头，响应已经写出去的话它们会抛异常。
- 不要用 `DedupeResponseHeader` 这类在响应写出之后才改响应头的过滤器，它会把 WebSocket 连接弄断。要处理后端返回的响应头，写 `HttpHeadersFilter`，参考 `DownstreamCorsHeadersFilter`。
- `X-Forwarded-*` 只由 `ClientAddressFilter` 写。网关自带的处理会在转发前删掉这类请求头，所以它必须是排在最后的 `HttpHeadersFilter`，写成普通的全局过滤器不生效。
- `server.forward-headers-strategy` 必须是 `none`，启动时有检查。客户端地址一律用 `ClientViewResolver` 算，算法和 mugsun-boot 的 `ClientIpResolver` 一致。有一处有意不一样：可信代理有一条写错，网关拒绝启动，mugsun-boot 是记一条警告后忽略那一条。忽略以后代理不被采信，所有请求的客户端地址都成了代理的地址，网关上按客户端地址做的限制全按这一个地址算。
- 跨域只由网关应答，规则在 `application.yml` 的 `globalcors`，要和 mugsun-boot 的 `CorsConfig` 一致。
- 记日志只记路径，不记查询串。WebSocket 的登录令牌在查询串里。
- 每个请求的编号由 `TraceIdFilter` 定，规则要和 mugsun-boot 的 `TraceIdFilter` 一致。过滤器记警告日志时用 `TraceIds.of(exchange)` 带上编号。
- 每个请求一行的访问日志由 `AccessLogFilter` 记，别的过滤器不要再给每个请求各记一行。字段有增减时同步改 `docs/deployment.md` 第 10 节的字段表。
- 按客户端地址限制转发（黑名单、白名单、不对外开放的路径）由 `ClientAccessFilter` 管。名单这类配置写错时要拒绝启动，不要忽略写错的条目：地址名单用 `IpRanges.parse` 读，`IpCidr.parseList` 会忽略写错的条目，这里不用它。
- 把一段文字认成地址或地址段，一律经 `IpRanges`（`bytes`、`range`、`parse`），不要直接调 `IpCidr.parse`、`parseList`、`toBytes`，也不要调 `InetAddress.getByName`。`10.0.0.300` 这种像地址又不是地址的写法，JDK 会当成主机名去查 DNS；在什么名字都解析得出地址的网络里（比如出网经过 fake-ip 代理），它会变成一个不相干的地址。`IpCidr.contains`、`anyContains` 只传 `ClientView` 算出来的地址。测试里的 `FakeIpDns` 让这类名字都解析得出地址，查没查 DNS 测得出来。
- 限流由 `RateLimitFilter` 管，规则和计数在 `ratelimit` 包。计数放在网关实例自己的内存里（Bucket4j 的令牌桶，放在有上限的 Caffeine 缓存里），是有意不用 Redis 的：拒绝一个请求不该再产生一次网络读写，网关也因此不用拿 Redis 的口令。不要换成 Sentinel 或网关自带的 `RequestRateLimiter`；按用户、按租户一个不差的限额在服务里做。
- 按什么给客户端分桶只在 `ClientView.bucketKey` 一处定（IPv4 单个地址，IPv6 按 /64），限流和灰度的按比例分流共用。被拒绝的请求不要逐条记警告：访问日志里已经有一行，警告由 `RejectionReport` 限着条数记。限额的写法、默认值有变化时同步改 `docs/deployment.md` 第 10 节「限流」。
- 按路径拦请求时，用 `RequestPaths.of(exchange)` 把客户端发来的路径和路由改写以后的路径都查一遍，比较用 `PathGuard` 或 `PathPrefixes`。不要自己拿 `startsWith` 比：把路径编码、改大小写、加分号参数就绕过去了。这类过滤器的顺序要排在 `RouteToRequestUrlFilter` 后面。
- 动态路由（从 Nacos 读、不重启就能改的那一份）只经 `DynamicRouteLoader.load` 换上：整份检查，全部合格才一次换掉，有一条不合格就保留原来的。报错会进日志，只写路由的 id 和配置项的名字，不带配置的值。
- 动态路由能用的断言和过滤器是 `DynamicRouteLoader` 里两张固定的表，不做成配置项。往表里加东西之前确认三件事：它改不了 `ClientViewResolver` 要看的请求头；改写以后的路径仍然会被内部接口拦截和地址名单查到；它不靠路由刷新的事件登记状态。改这两张表等于改网关对外放行什么，属于敏感改动。
- 读 Nacos 里整份的配置（动态路由、灰度规则）只由 `NacosConfigSource` 做（在 `mugsun-cloud-config` 模块里），几份配置共用一条到 Nacos 的连接，改动靠监听送来。再要读一份时加一个 `NacosConfigSource.Watch` 的 Bean，不要另开连接。不要加定时重读：Nacos 连不上又没有本地快照时，`getConfig` 也返回 null，和配置被删掉分不出来，定时重读会在 Nacos 出故障时把路由和规则清掉。
- 接收这些配置的一方（`DynamicRouteLoader.load`、`LaneRuleBook.load`），每收到一份和上一次不一样的全文都要记一行日志，以 `Watch.what()` 开头：换上了、清空了、不合格没有用，或者算出来和现在的一样不用换。运维靠日志里有没有新的这一行判断改动送到没有，少记一种他就会去查权限。同一份全文接连送来两次（启动时读一次，监听又送一次）只记一次。
- 灰度发布的代码在 `lane` 包。请求的灰度标记只由 `LaneFilter` 按 `LaneRuleBook` 里的规则定，放在 exchange 的属性 `LaneNames.LANE_ATTR` 里；传给服务的 `X-Mugsun-Lane` 只由 `LaneHeaderFilter` 写，客户端带来的一律删掉。挑实例只在 `LaneInstanceSupplier` 一处做：没有标记的请求只给正式实例；有标记的先给同标记的实例，没有就给正式实例。这两条一直生效，开关 `mugsun.gateway.lanes` 只管读不读 Nacos 里的规则。改这几处等于改网关的转发头和转发去向，属于敏感改动。
- 灰度规则只经 `LaneRuleBook.load` 换上：整份检查，有一处不合格就保留原来的。报错和日志里只写标记的名字和是第几条，不带配置里的地址和域名。写法有疑问的一律算错，不悄悄忽略，也不猜：不认识的配置项、没加引号被 YAML 读成数字或真假值的名字（`0105` 会读成 69）都拒收。悄悄忽略的结果是该进灰度的请求没进，而且没有任何提示。
- 按比例分流的算法在 `LaneRules.bucket`：标记的名字和客户端的分桶键一起算 SHA-256，取前 8 个字节对一万取余。不要改它：改了以后，正在灰度里的客户端会整个换一批。`LaneRulesTest` 里钉着几个算好的数，算法动了它会不通过。
- 灰度标记的名字规则、请求头和元数据的键，网关的 `LaneNames` 和公共层的 `Lanes` 里各有一份（网关不依赖公共层），要一起改，`LaneNamesTest.sameConventionsAsTheServices` 会对两边。规则里的地址段和别的名单一样经 `IpRanges.range` 读。
- 网关自己的配置项都在 `GatewayGuardProperties`（前缀 `mugsun.gateway`），只有一层，不要分组（不要加嵌套的对象）。给配置项给值的环境变量，名字就是配置项的名字换成大写加下划线：`mugsun.gateway.rate-limit-paths` 对应 `MUGSUN_GATEWAY_RATE_LIMIT_PATHS`。Spring Boot 会按名字把环境变量对到配置项上；分了组以后，和组同名的变量会被拿去给整组赋值，变量在、值是空的时候网关启动不了。变量留空等于没配，按默认值：不是文字的配置项，setter 的参数用 `Long`、`Boolean`，不用 `long`、`boolean`。
- 配置项从环境变量来的情况，要用真正的环境变量测（测试里的 `FakeEnvironment`）。写在 `@SpringBootTest(properties = …)` 里的值不算环境变量，上面那种问题测不出来。`GatewayBlankSettingsTest` 把所有开关留空启动网关，`GatewayEnvironmentSettingsTest` 给所有开关配上值；加了开关，在后一个里补上它的值和效果。
- 网关不解析登录令牌，登录校验和权限判断在服务里做。
- 需要 mugsun-boot 里的类时，在 `sync-rules.json` 的 `extras` 里单独拷贝，不要让网关依赖公共层。

网关的行为都要有测试。`GatewayForwardingTest` 测网关直接面向客户端的情况，`GatewayBehindProxyTest` 测前面有可信代理的情况，`GatewayAccessLogTest` 测请求编号和访问日志，`GatewayClientAccessTest` 测按客户端地址限制转发，`GatewayRateLimitTest` 测限流，`GatewayDynamicRoutesTest` 测动态路由，`GatewayLaneTest` 测灰度发布。它们都用 `GatewayTestBase` 里的假后端，不需要 Nacos、数据库和 Redis。

- 测试里访问网关用 `GatewayTestBase.client()`，不继承它的用 `GatewayClients.onPort(端口)`，不要自己 `WebTestClient.bindToServer()`。后者只断言状态码和响应头时不读响应体，没读的那部分占着的缓冲区没有人释放；`GatewayClients` 收到响应就把响应体整个读完。
- `BufferLeakGuard` 对网关的每个测试类都生效：有缓冲区没释放就被回收时，测试不通过。它报错时先分清是哪一种：测试里有响应没读完，还是网关的代码读了请求体、响应体没有释放。后一种在线上是内存只涨不落，要改网关的代码，不要为了让测试通过去动这道检查。

## 服务的约定

- 启动类上的注解和 mugsun-boot 的 `MugsunApplication` 保持一致，只多扫描 `com.mugsun.cloud` 下自己的包。有意不一样的地方写在启动类的注释里。
- `application.yml` 分几段：第一段只写 `spring.config.import`，先引入 `mugsun-boot-defaults.yml` 再引入 `mugsun-cloud-defaults.yml`，最后是配置中心的两份；第二段写端口和服务名；最后三段是分环境的配置中心导入，见上面「配置中心的约定」。各服务都一样的配置写进 `mugsun-cloud-defaults.yml`。
- 从 boot 同步来的 `application-prod.yml` 等 profile 文件，优先级比各服务的 `application.yml` 和 `mugsun-cloud-defaults.yml` 都高。要把一个配置项固定住，先查 profile 文件里有没有设它：设了的话写配置固定不住，要用代码。
- `/inner` 开头的路径留给服务之间调用的内部接口，网关不对外转发。对外的接口不要用这个前缀。写法见下面的「服务之间的调用」。
- 服务之间要传消息时，先看能不能走共用的主库和 Redis，不行再加内部接口。track 发告警站内信就是写主库的消息表，实时推送发到 Redis，由 system 投递给在线用户。所以 `mugsun.websocket.sender-type` 固定为 `redis`。删租户时清它的 AI 密钥也是 system 直接删主库里的表（`CloudAiSecretPurger`），不调 ai 服务。
- 主库的迁移和初始数据只由 system 执行。其它服务的 `spring.flyway.enabled` 是 `false`；只该执行一次的启动任务写进 `sync-rules.json` 的 `systemClasses`，留在 system。埋点库是另一个库，由 track 自己迁移。
- 有些状态以库里的为准，各进程又在内存里各存一份，只由 system 的管理接口修改：数据权限规则、接口权限规则、租户数据源，还有缓存的租户和套餐。每个服务的每个实例（包括 system）都跑公共层的 `SharedStateSyncTask`：保存的那个实例由 `SharedStateChangeAspect` 把 Redis 里的版本换成新值，各实例每秒读一次，变了就从库里重新加载，另有每 30 秒一次的加载兜底。租户和套餐不是重新加载，是把缓存清掉，由 `TenantCacheSync` 记着本实例缓存过哪些；mugsun-boot 给 `TenantValidator` 加了公开方法时 `TenantCacheSyncTest` 会失败，提醒看新方法会不会往缓存里放东西。新增这类状态时两处都要补：在 `SharedStateSyncTask` 里加上重新加载，在 `SharedStateChangeAspect` 的切点里加上保存时调用的那个方法。
- system 可以部署多个实例，写 system 的代码时按多实例考虑：状态放库里或 Redis 里，只放在内存里的要有办法让别的实例跟上。启动时「先查有没有，没有就改表或插入」的代码，要在公共层的 `StartupLock` 持锁期间执行：写成 `CommandLineRunner`、`ApplicationRunner`，或者监听 `ApplicationReadyEvent`，不要写在 `@PostConstruct` 里，那时还没有占到锁。主库的迁移在占这把锁之前执行，由公共层的 `MigrationLock` 用数据库里的锁另外排队。
- 可选模块的服务可以不部署。system 用 `CloudGisModuleStatus`、`CloudTrackModuleStatus`、`CloudAiModuleStatus` 这样的类查注册中心，决定菜单显不显示；可选服务自己要在模块没装配上时拒绝启动，参考 `GisModuleRequired`、`TrackModuleRequired`、`AiModuleRequired`。
- mugsun-boot 的可选模块里有些类要和 system 的代码在同一个进程里才起作用，拆成服务后要在 system 里补上对应的东西。现有两处：
  - 删租户时清凭据的 `TenantCredentialPurger`。AI 模块的实现在 ai 服务里，system 用 `CloudAiSecretPurger` 顶上。各可选服务的 `TenantPurgerGuardTest` 会在 mugsun-boot 新增这类实现时失败，提醒在 system 里补上。
  - 审批流的 AI 预审节点。它的监听器在 ai 服务里，而审批流在 system 里跑，这种节点目前用不了，system 用 `AiApprovalNodeGate` 拦下要走到它的操作。
- 流程引擎只认一个全局监听器。system 里是 `AiApprovalNodeGate`，它包着 mugsun-boot 的 `MugsunFlowListener`：先检查，再把通知原样转过去。要在全局监听器里加东西就加在它里面。引擎取全局监听器失败时不报错，所以启动时有检查：引擎拿到的不是它，system 拒绝启动。
- 流程引擎的操作不在事务里。要拦一次流程操作，得在它写库之前拦；等任务建出来再抛异常，数据已经留在库里。这类改动只有单元测试不够，要起服务实际走一遍流程，再查库里留下了什么。
- 每个服务都要有的防护放公共层。拆新服务时，逐个看留在 system 的过滤器、拦截器和配置类：新服务带了它们保护或配置的功能，就让新服务也带上。例子：带文件存储的服务会在自己的端口上开放 `/file/**`，所以校验签名的 `LocalFileAccessFilter` 在公共层。
- 进不了公共层的类（它依赖的组件不是每个服务都带），用 `sync-rules.json` 的 `extras` 单独拷给需要的服务，不要在 `src/main` 里照着重写一份。例子：文件存储的配置类 `FileStorageTikaConfig` 单独拷给了 track。
- PowerJob 执行器只在 system 上启动：同一个应用名下的执行器，调度中心派任务时不加区分。带了 PowerJob 依赖的其它服务在启动类上排除 `PowerJobAutoConfiguration`，参考 `TrackApplication`。
- 从 core 里分出来的服务（现在是日志服务 `mugsun-log`）：哪些类归它，写在 `sync-rules.json` 的 `core.services` 里，都是接口类。这些接口的路径在网关里本来归兜底的 `mugsun-system`，要在网关的路由里逐个写在它前面；日志服务的 `LogRoutesTest` 查路由和接口类对得上，再分服务时照着写一个。日志不经过日志服务写入：产生日志的服务自己写主库。所以新加一类日志时，写库的类放公共层（或者留在产生它的那个服务里），查询的接口类分给日志服务。
- 加新服务的步骤见 [docs/sync-from-boot.md](./docs/sync-from-boot.md) 的「拆出新服务时」。

## 服务之间的调用

代码在 `mugsun-cloud-common/src/main/java/com/mugsun/cloud/common/inner`。部署要求见 [docs/deployment.md](./docs/deployment.md) 的第 4 节。

- 提供接口：实现 `InnerEndpoint` 并注册成 Bean，路径登记在 `InnerPaths`。不要写成 Controller：`/inner` 下有 Controller 时服务拒绝启动。
- 发起调用：用 `InnerClient`，对方的服务名从 `ServiceNames` 取。对方要去调大模型的用 `callSlow`。`reachable` 用来在调用之前判断对方在不在。
- 带过去的身份只有租户，没有用户，也不转发用户的登录令牌。所以只有在单体版里本来就只看租户、不看用户的调用才能这样转；用户有没有权限做这件事，由发起调用的一方在调用之前判断。接口的实现里不能用 `StpUtil` 和 `@DataScope`。
- 请求和响应都用共享密钥签名，协议在 `InnerProtocol`，校验在 `InnerRequestFilter`。改这两个类等于改鉴权，属于敏感改动。
- 对方没有部署时，效果要和单体版没装那个模块时一样；对方在但调用失败时，要和单体版里那个模块出错时一样。现有的三处可以参考：ai 的 `RemoteInsightPort`、track 的 `RemoteTrackAskLlmGateway`、system 的 `RemoteGenAiDraftEnhancer`。
- 调用方只看得到注册中心里有没有对方的实例，分不出它是没有部署，还是部署了、此刻不在（正在重启，或者挂了）。这时给用户的话要在两种情况下都是真的，不要说「没有部署」。
- 给用户看的提示里不带地址、服务名和状态码，具体原因只记日志。日志里不记密钥和签名。
- 对方连得上、但是不回话或者忙不过来时有三道保护，都在公共层，默认开着：调用方的熔断（`InnerBreakers`，按实例、接口、租户分开记），被调用方每个接口同时处理的上限（`InnerGate`，整个接口一个上限、同一个租户一个上限，满了回「忙」），按调用方的等待时间给查询设时限（`InnerQueryTimeout`）。效果和配置项见 [docs/deployment.md](./docs/deployment.md) 第 4 节的「对方出了问题时的三道保护」。写接口和发起调用时要配合的是下面几条。
- 内部接口处理期间一直占着一个名额，一个接口默认只有 6 个（`mugsun.cloud.inner.max-concurrent`），其中同一个租户最多占 5 个（`max-concurrent-per-tenant`，不配时按总数留出四分之一给别的租户）。接口里只查库和计算，不要在里面等别的服务、等锁、睡眠。要等大模型这类慢东西的，在那个服务的 `application.yml` 里把上限配大并写明原因，参考 ai 服务；配大之前先确认等的时候不占数据库连接（不在事务里）。
- 接口的实现经 MyBatis（Mapper、`Db`）查库，这样的语句自动带时限：调用方不等了，语句被取消。用 `JdbcTemplate` 或者直接用 JDBC 发的语句不带，调用方不等了它还在跑；接口里另开线程去查的也不带。现有的一处是 track 的统计接口（查询写在 mugsun-boot 里），记在 README 的「目前的限制」里。
- 业务上的「不行」在接口里抛 `ServiceException`：调用方收到的是同一句话，不算调用失败。别的异常会变成 500，调用方按失败记，同一个租户接连 3 次就熔断 10 秒。所以「参数不对」「模块没启用」这类正常的拒绝不要用别的异常表示。
- 调用方要分清 `InnerCallException` 的几种原因：`absent()` 为真是对方不在；`BUSY`（对方满了）和 `BREAKER_OPEN`（熔断中）这两种，对方肯定没有执行。给用户的话，`BUSY` 说「正忙，请稍后再试」，其余的失败说「暂时不可用」，参考 `RemoteInsightPort`。不要在调用方再套一层重试：`InnerClient` 已经会换实例，熔断中再试只是多报一次失败，其它的失败对方可能已经执行过了。
- 调用方报来的等待时间（`X-Mugsun-Inner-Wait`）不参与签名，只是提示：验签通过以后才读，并且限制在 1 秒到 `long-read-timeout` 之间。不要拿它做别的判断；也不要把它加进签名，加了以后新旧版本的服务在滚动发布期间互相调不通。「忙」的回答是签了名的，调用方不采信没签名的「忙」。
- 灰度标记（`X-Mugsun-Lane`）和等待时间一样不参与签名，只是提示：`InnerRequestFilter` 验签通过以后才读，处理完清掉。它只影响这次处理里再去调别的服务时挑哪个实例，不要拿它做权限或数据上的判断，也不要把它加进签名。当前请求的标记放在 `LaneContext`（日志的 MDC 里也有，键是 `lane`）：经网关来的请求由公共层的 `LaneFilter` 放进去，内部调用由 `InnerRequestFilter` 放进去，别处不要写它。
- `InnerClient` 发起调用时带的是当前请求的灰度标记；没有请求可跟时（定时任务、自己起的线程）带这个实例自己的标记（`InstanceLane`，来自 `MUGSUN_LANE`）。挑实例的两条规则在 `Lanes.candidates`，和网关的 `LaneInstanceSupplier` 是同一个意思，要一起改：没有标记只找正式实例，有标记先找同标记的、没有再找正式实例。另开线程处理请求时标记不会跟过去，要跟就自己传。
- 内部接口要有测试，都不需要 Nacos 和 Redis：验签的规则、等待时间在 `InnerRequestFilterTest`，同时处理的上限和名额怎么在租户之间分在 `InnerGateTest`，熔断在 `InnerBreakersTest`，查询时限在 `InnerQueryTimeoutTest`，调用方怎么处理各种回答在 `InnerClientTest`，两头接起来的情况在 `InnerLoopbackTest`。这几道保护在真实的故障里管不管用，单元测试看不出来：改了它们以后，要起整套服务，把对方冻住、把表锁住、让请求一起来（一个租户的，和几个租户一起的），实际测一遍等多久、占多少连接。

## 部署文件的约定

`deploy/docker` 是镜像和一条命令起全套的编排，说明见 [docs/deployment.md](./docs/deployment.md) 的第 16 节。`deploy/dev` 只给本机开发用。

- 口令和密钥不写进编排文件、镜像、容器的环境变量和启动参数，也不打进日志。它们由 `gen-secrets.sh` 在首次启动时生成，按使用方分卷，每个容器只挂自己要读的那一个。新增一个密钥时先想清楚谁要读它，写进对应的文件。例外只有监控中心的登录账号：照 BladeX 的做法写在配置里，带公开的默认值，经环境变量传进容器。
- 映射到宿主机的只有管理后台和监控中心两个端口，默认都只绑 `127.0.0.1`。不要给网关、各服务、Nacos、数据库和 Redis 加端口映射。
- 要被别的容器找到的容器用固定地址，连接配置里写地址，不写容器名。容器没在运行时，Docker 会把它的名字交给宿主机的 DNS 去查，请求和口令会发到查到的地址；Nginx 还只在启动时查一次。四个业务服务不用固定地址，它们经 Nacos 被找到。
- system 的实例数由 `MUGSUN_SYSTEM_REPLICAS` 定，默认一个。不要给 system 写 `container_name` 和固定地址，否则起不了第二个实例。自检默认起两个 system 实例，写检查时一个服务按可能有几个容器处理。
- 常驻的服务都写 `restart: unless-stopped` 和 `stop_grace_period`。不写停止时等多久的话，有的环境里 Docker 只等 1 秒就强杀。
- 镜像和源码都要钉死：基础镜像和第三方镜像写摘要，从 GitHub 取的源码写 40 位提交号。和 mugsun-boot 共用的那几个，同步时跟着改，见 [docs/sync-from-boot.md](./docs/sync-from-boot.md)。PostgreSQL 的镜像是例外，和单体版一样按标签取。
- `deploy/docker/boot/` 是从 mugsun-boot 同步来的，不要手改。
- 网关和各服务的镜像默认按 `prod` 配置运行。不要为了让编排好起而换成 `dev`。
- 网关的开关（网关的 `application.yml` 里 `MUGSUN_GATEWAY_` 开头的变量）要在 `docker-compose.yml` 的 gateway 服务里逐个传进去，写成 `${名字:-}`：默认值只写在网关一处，没配的传进去是空值，网关把空值当成没配。代价是这些开关在这份编排里从配置中心改不了：变量传进了进程，哪怕是空的，Nacos 里的同一项也不起作用（实测，见 `docs/deployment.md` 第 17 节）。编排只传写在 `environment` 里的变量，漏传的开关写进 `.env` 也不生效，而且没有任何提示。网关加了开关，还要在 `smoke.sh` 开头给它定一个值：自检会查网关认的每个开关都照这个值传进了容器。有意不传的写进 `smoke.sh` 的 `not_passed`，并在 `docs/deployment.md` 的「这份编排没有做的」里写明原因。
- 改了 `deploy/docker` 下的文件，跑一遍 `deploy/docker/smoke.sh`。它从零起一套独立的环境，逐项检查上面这些约定、每个实例都到配置中心读了配置并且收得到改动、两个 system 实例之间的一致，再把 Nacos 的容器删掉重建（存过的配置要还在）、把全部容器停掉打乱顺序重新启动，看能不能自己恢复。新加的约定要在里面补上检查。
- 自检的最后一步（`smoke-sweep.py`）把四个服务接口文档里的每个接口都经管理后台和网关请求一遍：要登录的不带登录令牌，必须返回 401；每个请求转给了谁看网关的访问日志，必须是文档里有这个接口的服务。同步进了新接口、改了路径前缀或网关的路由表以后跑一遍。报「不登录就进得去」是接口没有被登录校验拦住；报「没有转给有这个接口的服务」是这个路径不在它所在服务的路由范围里，改路径前缀或路由表。哪些接口不用登录，它读的是 `LoginPublicPaths`，不要在脚本里另抄一份名单。
- 不用登录的接口钉在 `deploy/docker/public-endpoints.txt` 里，自检的最后一步拿它和实际核对，多一个、少一个都不通过。报「比清单多出」时不要直接照着报错补清单：多出一个没登录就能访问的接口是公开接口的改动，先确认它该开放、没登录的人拿不到不该拿的东西，再加进去，加在说明它为什么开放的那一组下面。`/open`、`/v1`、`/file`、`/track` 这几段是按前缀免登录的，在它们下面加接口就会遇到。清单只用来核对，接口要不要登录仍然只由 `LoginPublicPaths` 决定。
- 没有任何角色的账号进得去的接口钉在 `deploy/docker/roleless-endpoints.txt` 里。自检建一个没分配角色的账号，登录以后在最后一步把要登录的接口再请求一遍，返回的不是 403 就算进得去，和清单多一个、少一个都不通过。报「比清单多出」时不要直接照着报错补清单：`/system` 下的写接口（POST、PUT、DELETE）没写权限码时另有一道兜底，默认拒绝；读接口和别的前缀下的接口没有，忘了写权限码就会出现在这里。先确认这个接口该给任何登录的人用、给出的只是这个人自己的东西；该要权限的去补权限码（接口在 `src/boot/` 里的，到 mugsun-boot 里补，再同步过来），确认可以的再加进清单，加在说明它为什么可以的那一组下面。新加的接口会结束登录时（比如又一个退出接口），这一步请求到它，后面的接口都会变成没登录：把它写进 `smoke-sweep.py` 的 `ENDS_LOGIN`，那里的接口不请求，按进得去算。这个账号的登录令牌只放在一个临时的卷里，不打印，自检结束时删掉；`KEEP=1` 留现场时它跟现场一起留着，下次自检开头删掉。
- 接口文档默认不提供，自检会查：经管理后台取不到，直连每个服务也取不到。自检最后一步要从文档里取接口清单，用的是一份不进仓库的临时编排片段，打开以后重新创建四个业务服务；要在默认配置上做的检查写在这一步前面。

## 构建和测试

```bash
(cd ../mugsun-core && mvn -B install -DskipTests)    # core 0.2.0-SNAPSHOT 不在 Central
./mvnw -B verify                                     # 编译全部模块，跑本仓自己的测试
python3 scripts/sync_from_boot.py --locked --check   # src/boot 没有被手改
python3 scripts/boot_cloud_diff.py --check           # 和单体版不一样的地方都记了，docs/boot-vs-cloud.md 是最新的
python3 scripts/compare_libs.py                      # 第三方库的版本和单体版的包一致，改了 pom 或同步之后跑
deploy/docker/smoke.sh                               # 从零起整套容器逐项检查，改了 deploy/docker、网关的路由或同步进新接口之后跑
```

- 只构建或只测一个模块时带 `-am`，把它依赖的本仓模块一起构建，比如 `./mvnw -B -pl mugsun-cloud-gateway -am test`；只跑个别测试类时再加 `-Dsurefire.failIfNoSpecifiedTests=false`。不带 `-am` 时 Maven 到本机仓库去找 `mugsun-cloud-config`、`mugsun-cloud-common` 的包，找不到就报错。
- 这里没有业务测试，业务改动在 mugsun-boot 里测。
- 业务代码用到的第三方库，版本以单体版打出的包为准，用 `scripts/compare_libs.py` 检查。不要引入会改这些库版本的版本清单（BOM）：`spring-cloud-alibaba-dependencies` 会改 guava 等库，所以父 pom 没有引入它，只单独写了 Nacos 组件的版本。版本不一致时的处理见 [docs/sync-from-boot.md](./docs/sync-from-boot.md) 的「第三方库的版本」。
- 本地联调的启动步骤见 [README](./README.md)。`java -jar` 启动服务时要显式传 `--spring.profiles.active=...`，没配 profile 按生产处理。
- 重型任务（构建、测试、启动服务）一律经仓库父目录 `mugsun/` 下的 `_locks/heavy.sh` 排队，用法和 mugsun-boot 相同：
  ```bash
  ../_locks/heavy.sh <名字> <做什么> -- <命令...>
  ```
- 启动的服务和 Nacos 用完即停，不要挂着。
- shell 脚本要能在 macOS 自带的 bash 3.2 上跑，变量一律写成 `${名字}`。

## 提交和推送

约定和 mugsun-boot 相同，细则见那边的 `AGENTS.md` 和 `.github/COMMIT_CONVENTION.md`。要点：

- Conventional Commits，主题中文，不超过 50 字，不用 emoji。一个提交只做一件事。
- 直接在 `main` 上提交和推送，不另建分支。推之前 `./mvnw -B verify`、`sync_from_boot.py --locked --check` 和 `boot_cloud_diff.py --check` 都要通过。
- scope 用模块名：`gateway`、`common`、`config`、`system`、`gis`、`track`、`ai`、`sync`、`deploy`、`build`。
- 同步 mugsun-boot 代码单独做一个提交，类型用 `chore(sync)`，正文写 mugsun-boot 的提交号。
- `CHANGELOG.md` 写在 `## [Unreleased]` 下，每行不超过 80 字。
- 提交身份和 mugsun-boot 用同一个，只在仓库级配置里设，不改全局配置。
- 推送写全 refspec：`git push origin <完整 SHA>:refs/heads/main`。不 force push，不用 `git stash`。
- 鉴权、租户、密钥、新依赖、公开接口、CI 配置属于敏感改动，需要安全审查后再进 main。网关的转发头、内部接口拦截和跨域规则，服务之间调用的签名校验，也算在内。部署文件里的基础镜像、密钥的生成和存放、Nacos 的鉴权、对外的端口，同样算。
- 改动 `.github/` 下任何文件的提交，必须先有安全官签字，才能进 main。
