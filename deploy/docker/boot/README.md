# 这个目录由脚本同步，不要手改

这里的文件从 mugsun-boot 原样复制，来源提交记在仓库根目录的 `boot-sync.lock.json`。

- 要改这些文件：改 mugsun-boot，再执行 `python3 scripts/sync_from_boot.py`。
- 微服务版自己的文件不要放进这个目录，同步时整个目录会重写。
- 同步哪些文件：`scripts/sync-rules.json` 的 `files`。
