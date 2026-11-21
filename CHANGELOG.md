# 更新日志

本文件记录 [mugsun-cloud](https://github.com/mugsun/mugsun-cloud) 的重要变更，格式参考
[Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)。

## [Unreleased]

### 新增

- 工程骨架：父 pom、公共层、system 服务、网关，版本与 mugsun-boot 对齐
- 同步脚本 `scripts/sync_from_boot.py`：业务代码从 mugsun-boot 原样复制
  - 同步来的代码放在各模块的 `src/boot`，来源提交记在 `boot-sync.lock.json`
  - 公共层和 system 的划分规则在 `scripts/sync-rules.json`
  - `--locked --check` 检查 `src/boot` 有没有被手改
- system 服务：`mugsun-boot-core` 的全部功能，注册到 Nacos，端口 8101
- 网关：端口 8080，路径不改写，按服务名转发，支持 WebSocket
  - 按可信代理计算客户端地址并重写转发头，客户端带来的转发头一律删掉
  - 拦下 `/inner` 开头的内部接口路径
  - 跨域只由网关应答，规则与 mugsun-boot 一致
  - 转发失败按统一格式返回 502、503、504
  - 连不上选中的实例时换一个实例再转发一次；请求已经发给实例的不重发，连接断了返回 502
  - 自己的健康检查在 `/_gateway/health`，`/actuator` 转给后端服务
  - 给每个请求定一个请求编号，经 `X-Trace-Id` 传给服务并写进响应头
  - 访问日志：每个请求一行，写明状态码、耗时和转给了哪个实例，不记查询串
  - `MUGSUN_GATEWAY_ACCESS_LOG` 可以改成只记 5xx、转发出错和慢请求，或者不记
  - 可以按客户端地址限制转发：黑名单、白名单、只对指定地址开放的路径，默认不限制
  - 上面这几项写错时网关拒绝启动；路径换个写法（编码、大小写、分号参数）绕不过去
  - 动态路由：打开后网关另外从 Nacos 的一份配置里读路由，改了不用重启，默认不开
  - 动态路由写错时整份不采用，继续用原来的；只能转给注册中心里的服务，只能改路径
- gis 服务：`mugsun-boot-gis` 的全部功能，端口 8102，可以不部署
  - 网关把 `/system/gis/**` 转给它，没部署时这些接口返回 503
  - system 按注册中心里有没有 gis 实例决定 GIS 菜单显不显示
  - 系统参数 `gis.module.enabled` 可以不停服务关掉 GIS
- track 服务：对应 `mugsun-boot-track`（埋点分析），端口 8103，可以不部署
  - 网关把 `/track/**` 和 `/system/track/**` 转给它，没部署时返回 503
  - system 按注册中心里有没有 track 实例决定埋点分析菜单显不显示
  - 埋点库 `mugsun_track` 由 track 自己建库和迁移
  - 告警站内信写主库，实时推送经 Redis 由 system 投递，推送方式固定为 redis
  - 校验文件地址签名的过滤器划进公共层，各服务端口上的 `/file/**` 都有防护
  - 文件类型识别的配置 `FileStorageTikaConfig` 单独拷给 track，和单体版用同一份
  - PowerJob 执行器只在 system 上启动，埋点的三个 PowerJob 处理器用不了
- ai 服务：对应 `mugsun-boot-ai`（AI 模块），端口 8104，可以不部署
  - 网关把 `/system/ai/**` 和 `/v1/**` 转给它，没部署时返回 503
  - system 按注册中心里有没有 ai 实例决定 AI 菜单显不显示
  - 系统参数 `ai.module.enabled` 可以不停服务关掉 AI
  - 删租户时由 system 清掉这个租户的 AI 密钥，不依赖 ai 服务在线
  - 审批流的 AI 预审节点暂不支持：要走到它的操作在写库之前被拒绝
- 服务之间的调用：各服务在自己的端口上开 `/inner` 内部接口，网关不转发
  - 用共享密钥 `MUGSUN_INNER_SECRET` 签名，带时间和随机串防重放，响应也签名
  - 只带租户，不带用户令牌；没配密钥时内部调用停用，服务照常启动
  - AI 问图层、解读埋点数据：ai 向 gis、track 取库里的实数
  - 埋点「问数据」：track 请 ai 用大模型解析提问，ai 不在时按规则解析
  - 代码生成的建模草稿：system 请 ai 用大模型出，ai 不在时按规则生成
- 多个服务共用一个库的处理
  - 主库迁移和初始数据只由 system 执行，其它服务不跑 Flyway，要先启动 system
  - 数据权限规则、接口权限、租户数据源改了以后，约 1 秒在所有实例上生效，停用租户、改套餐也一样
  - 保存的实例改 Redis 里的版本值，各实例每秒读一次，变了就从库里重新加载，并清掉缓存的租户和套餐
  - 版本没变也每 30 秒重新加载一次，Redis 连不上或直接改库时靠它兜底
  - 每个服务的每个实例启动时到 Redis 领一个机器号（2 到 99），程序生成的主键不会和别的实例相同
  - 号的登记 3 分钟过期、每 30 秒续期，正常停止时归还；领不到时用 1 号照常启动
  - 过期时间用 `mugsun.cloud.machine-number.ttl` 调，`mugsun.cloud.machine-number.enabled` 设成 `false` 可以关掉
- system 可以部署多个实例
  - 启动时的数据库迁移靠数据库里的一把锁排队，一次一个实例
  - 启动时改表、写初始数据的那一段靠 Redis 里的一把锁排队，一次一个实例
  - 字典缓存改成 30 秒过期，别的实例最长 30 秒后读到改过的字典
  - 一键部署用 `MUGSUN_SYSTEM_REPLICAS` 设实例数，默认 1
  - 一键自检默认起两个 system 实例，检查各实例领到的机器号不同、从空库一起启动都没有重启过、实例之间一致、正常停掉一个的过程中经网关的访问没有失败
- 四个服务正常停止时不丢请求：先从 Nacos 注销，照常应答 8 秒再开始停
  - 这 8 秒用 `mugsun.cloud.shutdown.deregister-wait` 调，`dev` 环境下是 0
- 同步脚本支持可选模块：整个模块对应一个服务，并检查它只引用公共层的类
- 本地开发用的 Nacos：`deploy/dev/nacos.sh`，密钥首次启动时随机生成
- 文档：`docs/sync-from-boot.md`、`docs/deployment.md`
- 比对脚本 `scripts/compare_libs.py`：比较各服务的包和单体版的包里第三方库的版本
- Docker 镜像 `deploy/docker/Dockerfile`：一个文件构建网关和四个服务的镜像
  - 基础镜像固定摘要，非 root 运行，默认 prod 配置，自带健康检查
  - 口令和密钥用环境变量给，或者写在 `MUGSUN_ENV_FILE` 指的文件里
- 一条命令起全套 `deploy/docker/docker-compose.yml`，只需要 Docker
  - 含 PostgreSQL、Redis、Nacos、网关、四个服务和管理后台
  - 口令和密钥首次启动时随机生成，按使用方分卷，不进环境变量和日志
  - 对外只有管理后台一个端口，默认只绑 127.0.0.1
  - Nacos 打开鉴权，网关和各服务用只能注册和查询服务的账号接入
  - 容器之间按固定地址连接；停止时等进程自己收尾，不被强杀
  - 机器重启后各容器先后顺序不定，整套也能自己恢复
  - 出网经过 fake-ip 代理的机器，可以打开开关让 gis 取到底图瓦片（默认关）
- 一键自检 `deploy/docker/smoke.sh`：从零起整套，检查部署约定和主要功能
- 同步脚本支持单独同步文件：建库、生成密钥等部署脚本从 mugsun-boot 同步

### 修复

- 第三方库的版本和单体版打出的包对齐，此前有 5 个库不一致
  - tika-core 2.4.1 → 3.2.2，commons-io 2.16.1 → 2.20.0
  - commons-collections4 4.4 → 4.5.0，guava 33.4.8 → 33.3.1
  - mybatis-spring-boot-starter 3.0.5 → 3.0.3
  - 父 pom 不再引入 `spring-cloud-alibaba-dependencies`，只写 Nacos 组件的版本
- 网关不再把 `10.0.0.300` 这类像地址又不是地址的写法拿去查 DNS
  - 此前在什么名字都解析得出地址的网络里，它会被当成另一个不相干的地址
  - 影响可信代理、转发头里的地址和按客户端地址限制转发的名单
