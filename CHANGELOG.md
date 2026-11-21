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
- 本地开发用的 Nacos：`deploy/dev/nacos.sh`，密钥首次启动时随机生成
- 文档：`docs/sync-from-boot.md`、`docs/deployment.md`
