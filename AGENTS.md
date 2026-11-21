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

预留的端口：track 8103、ai 8104。服务名是 `mugsun-<名字>`，如 `mugsun-system`。

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
- 网关不解析登录令牌，登录校验和权限判断在服务里做。
- 需要 mugsun-boot 里的类时，在 `sync-rules.json` 的 `extras` 里单独拷贝，不要让网关依赖公共层。

网关的行为都要有测试。`GatewayForwardingTest` 测网关直接面向客户端的情况，`GatewayBehindProxyTest` 测前面有可信代理的情况，两者都用 `GatewayTestBase` 里的假后端，不需要 Nacos、数据库和 Redis。

## 服务的约定

- 启动类上的注解和 mugsun-boot 的 `MugsunApplication` 保持一致，只多扫描 `com.mugsun.cloud` 下自己的包。
- `application.yml` 分两段：第一段只写 `spring.config.import`，先引入 `mugsun-boot-defaults.yml` 再引入 `mugsun-cloud-defaults.yml`；第二段写端口和服务名。各服务都一样的配置写进 `mugsun-cloud-defaults.yml`。
- `/inner` 开头的路径留给服务之间调用的内部接口，网关不对外转发。对外的接口不要用这个前缀。服务之间目前还没有调用。
- 主库的迁移和初始数据只由 system 执行。其它服务的 `spring.flyway.enabled` 是 `false`；只该执行一次的启动任务写进 `sync-rules.json` 的 `systemClasses`，留在 system。
- 有些状态各进程在内存里各存一份，又只由 system 的管理接口修改：数据权限规则、接口权限规则、租户数据源。其它服务靠公共层的 `SharedStateSyncTask` 定时重新加载，新增这类状态时要在那里补上。system 自己不跑这个任务，所以 system 目前只能部署一个实例。
- 可选模块的服务可以不部署。system 用 `CloudGisModuleStatus` 这样的类查注册中心，决定菜单显不显示；可选服务自己要在模块没装配上时拒绝启动，参考 `GisModuleRequired`。
- 加新服务的步骤见 [docs/sync-from-boot.md](./docs/sync-from-boot.md) 的「拆出新服务时」。

## 构建和测试

```bash
(cd ../mugsun-core && mvn -B install -DskipTests)    # core 0.2.0-SNAPSHOT 不在 Central
./mvnw -B verify                                     # 编译全部模块，跑本仓自己的测试
python3 scripts/sync_from_boot.py --locked --check   # src/boot 没有被手改
```

- 这里没有业务测试，业务改动在 mugsun-boot 里测。
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
- scope 用模块名：`gateway`、`common`、`system`、`gis`、`sync`、`deploy`、`build`。
- 同步 mugsun-boot 代码单独做一个提交，类型用 `chore(sync)`，正文写 mugsun-boot 的提交号。
- `CHANGELOG.md` 写在 `## [Unreleased]` 下，每行不超过 80 字。
- 提交身份和 mugsun-boot 用同一个，只在仓库级配置里设，不改全局配置。
- 推送写全 refspec：`git push origin <完整 SHA>:refs/heads/<分支>`。不 force push，不用 `git stash`。
- 鉴权、租户、密钥、新依赖、公开接口、CI 配置属于敏感改动，需要安全审查后再合。网关的转发头、内部接口拦截和跨域规则也算在内。
- 改动 `.github/` 下任何文件的提交，必须先有安全官签字，才能进 main。
