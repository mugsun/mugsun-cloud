# 这个目录由脚本同步，不要手改

这里的文件从 mugsun-boot 原样复制，来源提交记在仓库根目录的 `boot-sync.lock.json`。

- 要改业务逻辑：改 mugsun-boot，再执行 `python3 scripts/sync_from_boot.py`。
- 要写微服务版特有的代码：写在本模块的 `src/main/` 下。
- 划分规则：`scripts/sync-rules.json`。
