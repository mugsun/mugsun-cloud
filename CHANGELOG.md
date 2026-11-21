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
  - 自己的健康检查在 `/_gateway/health`，`/actuator` 转给后端服务
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
  - PowerJob 执行器只在 system 上启动，埋点的三个 PowerJob 处理器用不了
  - 「问数据」没有大模型可用，只按规则解析问题，等 AI 拆过来再接
- 多个服务共用一个库的处理
  - 主库迁移和初始数据只由 system 执行，其它服务不跑 Flyway，要先启动 system
  - 在 system 里改的数据权限规则、接口权限、租户数据源，其它服务 30 秒内生效
- 同步脚本支持可选模块：整个模块对应一个服务，并检查它只引用公共层的类
- 本地开发用的 Nacos：`deploy/dev/nacos.sh`，密钥首次启动时随机生成
- 文档：`docs/sync-from-boot.md`、`docs/deployment.md`
