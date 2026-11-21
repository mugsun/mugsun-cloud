# AGENTS.md — mugsun-cloud

本文件写给在本仓工作的开发者和 AI 助手。内容以本仓代码为准；与代码不一致时以代码为准，并请顺手修正本文件。

## 仓库是什么

mugsun 后端的微服务版。业务代码来自 [mugsun-boot](https://github.com/mugsun/mugsun-boot)，架构是网关加若干服务，Nacos 做注册中心。

依赖 [mugsun-core](https://github.com/mugsun/mugsun-core) 的 `0.2.0-SNAPSHOT`，Maven Central 上没有，先在 `../mugsun-core` 执行 `mvn -B install -DskipTests`。

| 模块 | 端口 | 作用 |
| --- | --- | --- |
| `mugsun-cloud-common` | 无 | 各服务都带的公共层，Servlet 栈 |
| `mugsun-cloud-gateway` | 8080 | 网关，WebFlux 栈，不依赖公共层 |
| `mugsun-cloud-service/mugsun-cloud-system` | 8101 | system 服务，对应 `mugsun-boot-core` |
| `mugsun-cloud-service/mugsun-cloud-gis` | 8102 | gis 服务，对应 `mugsun-boot-gis`，可以不部署 |
| `mugsun-cloud-service/mugsun-cloud-track` | 8103 | track 服务，对应 `mugsun-boot-track`，可以不部署 |
| `mugsun-cloud-service/mugsun-cloud-ai` | 8104 | ai 服务，对应 `mugsun-boot-ai`，可以不部署 |

服务名是 `mugsun-<名字>`，如 `mugsun-system`。

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

## 网关的约定

代码在 `mugsun-cloud-gateway/src/main/java/com/mugsun/cloud/gateway`。

- 路径不改写，对外的接口路径和单体版一致。路由按顺序匹配，范围小的写在前面，`mugsun-system` 兜底。
- 过滤器要拒绝请求时，返回 `Mono.error(new GatewayRejectedException(状态码, 说明))`，由 `GatewayErrorHandler` 写响应。不要在过滤器里自己写响应：排在前面的过滤器还要改响应头，响应已经写出去的话它们会抛异常。
- 不要用 `DedupeResponseHeader` 这类在响应写出之后才改响应头的过滤器，它会把 WebSocket 连接弄断。要处理后端返回的响应头，写 `HttpHeadersFilter`，参考 `DownstreamCorsHeadersFilter`。
- `X-Forwarded-*` 只由 `ClientAddressFilter` 写。网关自带的处理会在转发前删掉这类请求头，所以它必须是排在最后的 `HttpHeadersFilter`，写成普通的全局过滤器不生效。
- `server.forward-headers-strategy` 必须是 `none`，启动时有检查。客户端地址一律用 `ClientViewResolver` 算，算法和 mugsun-boot 的 `ClientIpResolver` 一致。
- 跨域只由网关应答，规则在 `application.yml` 的 `globalcors`，要和 mugsun-boot 的 `CorsConfig` 一致。
- 记日志只记路径，不记查询串。WebSocket 的登录令牌在查询串里。
- 每个请求的编号由 `TraceIdFilter` 定，规则要和 mugsun-boot 的 `TraceIdFilter` 一致。过滤器记警告日志时用 `TraceIds.of(exchange)` 带上编号。
- 每个请求一行的访问日志由 `AccessLogFilter` 记，别的过滤器不要再给每个请求各记一行。字段有增减时同步改 `docs/deployment.md` 第 10 节的字段表。
- 按客户端地址限制转发（黑名单、白名单、不对外开放的路径）由 `ClientAccessFilter` 管。名单这类配置写错时要拒绝启动，不要忽略写错的条目：`IpCidr.parseList` 会忽略，所以这里不用它。
- 按路径拦请求时，用 `RequestPaths.of(exchange)` 把客户端发来的路径和路由改写以后的路径都查一遍，比较用 `PathGuard` 或 `PathPrefixes`。不要自己拿 `startsWith` 比：把路径编码、改大小写、加分号参数就绕过去了。这类过滤器的顺序要排在 `RouteToRequestUrlFilter` 后面。
- 网关不解析登录令牌，登录校验和权限判断在服务里做。
- 需要 mugsun-boot 里的类时，在 `sync-rules.json` 的 `extras` 里单独拷贝，不要让网关依赖公共层。

网关的行为都要有测试。`GatewayForwardingTest` 测网关直接面向客户端的情况，`GatewayBehindProxyTest` 测前面有可信代理的情况，`GatewayAccessLogTest` 测请求编号和访问日志，`GatewayClientAccessTest` 测按客户端地址限制转发。它们都用 `GatewayTestBase` 里的假后端，不需要 Nacos、数据库和 Redis。

## 服务的约定

- 启动类上的注解和 mugsun-boot 的 `MugsunApplication` 保持一致，只多扫描 `com.mugsun.cloud` 下自己的包。有意不一样的地方写在启动类的注释里。
- `application.yml` 分两段：第一段只写 `spring.config.import`，先引入 `mugsun-boot-defaults.yml` 再引入 `mugsun-cloud-defaults.yml`；第二段写端口和服务名。各服务都一样的配置写进 `mugsun-cloud-defaults.yml`。
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
- 加新服务的步骤见 [docs/sync-from-boot.md](./docs/sync-from-boot.md) 的「拆出新服务时」。

## 服务之间的调用

代码在 `mugsun-cloud-common/src/main/java/com/mugsun/cloud/common/inner`。部署要求见 [docs/deployment.md](./docs/deployment.md) 的第 4 节。

- 提供接口：实现 `InnerEndpoint` 并注册成 Bean，路径登记在 `InnerPaths`。不要写成 Controller：`/inner` 下有 Controller 时服务拒绝启动。
- 发起调用：用 `InnerClient`，对方的服务名从 `ServiceNames` 取。对方要去调大模型的用 `callSlow`。`reachable` 用来在调用之前判断对方在不在。
- 带过去的身份只有租户，没有用户，也不转发用户的登录令牌。所以只有在单体版里本来就只看租户、不看用户的调用才能这样转；用户有没有权限做这件事，由发起调用的一方在调用之前判断。接口的实现里不能用 `StpUtil` 和 `@DataScope`。
- 请求和响应都用共享密钥签名，协议在 `InnerProtocol`，校验在 `InnerRequestFilter`。改这两个类等于改鉴权，属于敏感改动。
- 对方没有部署时，效果要和单体版没装那个模块时一样；对方在但调用失败时，要和单体版里那个模块出错时一样。现有的三处可以参考：ai 的 `RemoteInsightPort`、track 的 `RemoteTrackAskLlmGateway`、system 的 `RemoteGenAiDraftEnhancer`。
- 给用户看的提示里不带地址、服务名和状态码，具体原因只记日志。日志里不记密钥和签名。
- 内部接口要有测试：验签的规则在 `InnerRequestFilterTest`，两头接起来的情况在 `InnerLoopbackTest`，都不需要 Nacos 和 Redis。

## 部署文件的约定

`deploy/docker` 是镜像和一条命令起全套的编排，说明见 [docs/deployment.md](./docs/deployment.md) 的第 16 节。`deploy/dev` 只给本机开发用。

- 口令和密钥不写进编排文件、镜像、容器的环境变量和启动参数，也不打进日志。它们由 `gen-secrets.sh` 在首次启动时生成，按使用方分卷，每个容器只挂自己要读的那一个。新增一个密钥时先想清楚谁要读它，写进对应的文件。
- 映射到宿主机的只有管理后台一个端口，默认只绑 `127.0.0.1`。不要给网关、各服务、Nacos、数据库和 Redis 加端口映射。
- 要被别的容器找到的容器用固定地址，连接配置里写地址，不写容器名。容器没在运行时，Docker 会把它的名字交给宿主机的 DNS 去查，请求和口令会发到查到的地址；Nginx 还只在启动时查一次。四个业务服务不用固定地址，它们经 Nacos 被找到。
- system 的实例数由 `MUGSUN_SYSTEM_REPLICAS` 定，默认一个。不要给 system 写 `container_name` 和固定地址，否则起不了第二个实例。自检默认起两个 system 实例，写检查时一个服务按可能有几个容器处理。
- 常驻的服务都写 `restart: unless-stopped` 和 `stop_grace_period`。不写停止时等多久的话，有的环境里 Docker 只等 1 秒就强杀。
- 镜像和源码都要钉死：基础镜像和第三方镜像写摘要，从 GitHub 取的源码写 40 位提交号。和 mugsun-boot 共用的那几个，同步时跟着改，见 [docs/sync-from-boot.md](./docs/sync-from-boot.md)。PostgreSQL 的镜像是例外，和单体版一样按标签取。
- `deploy/docker/boot/` 是从 mugsun-boot 同步来的，不要手改。
- 网关和各服务的镜像默认按 `prod` 配置运行。不要为了让编排好起而换成 `dev`。
- 改了 `deploy/docker` 下的文件，跑一遍 `deploy/docker/smoke.sh`。它从零起一套独立的环境，逐项检查上面这些约定和两个 system 实例之间的一致，再重启 Nacos、把全部容器停掉打乱顺序重新启动，看能不能自己恢复。新加的约定要在里面补上检查。

## 构建和测试

```bash
(cd ../mugsun-core && mvn -B install -DskipTests)    # core 0.2.0-SNAPSHOT 不在 Central
./mvnw -B verify                                     # 编译全部模块，跑本仓自己的测试
python3 scripts/sync_from_boot.py --locked --check   # src/boot 没有被手改
python3 scripts/compare_libs.py                      # 第三方库的版本和单体版的包一致，改了 pom 或同步之后跑
deploy/docker/smoke.sh                               # 从零起整套容器逐项检查，改了 deploy/docker 之后跑
```

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
- 直接在 `main` 上提交和推送，不另建分支。推之前 `./mvnw -B verify` 和 `sync_from_boot.py --locked --check` 都要通过。
- scope 用模块名：`gateway`、`common`、`system`、`gis`、`track`、`ai`、`sync`、`deploy`、`build`。
- 同步 mugsun-boot 代码单独做一个提交，类型用 `chore(sync)`，正文写 mugsun-boot 的提交号。
- `CHANGELOG.md` 写在 `## [Unreleased]` 下，每行不超过 80 字。
- 提交身份和 mugsun-boot 用同一个，只在仓库级配置里设，不改全局配置。
- 推送写全 refspec：`git push origin <完整 SHA>:refs/heads/main`。不 force push，不用 `git stash`。
- 鉴权、租户、密钥、新依赖、公开接口、CI 配置属于敏感改动，需要安全审查后再进 main。网关的转发头、内部接口拦截和跨域规则，服务之间调用的签名校验，也算在内。部署文件里的基础镜像、密钥的生成和存放、Nacos 的鉴权、对外的端口，同样算。
- 改动 `.github/` 下任何文件的提交，必须先有安全官签字，才能进 main。
