# 部署注意事项

微服务版和单体版的业务代码相同，但进程从一个变成了多个。下面是因此和单体版不一样的地方。前五节配错会出安全问题，第七节关系到收回权限后多久生效，最后一节是从单体版迁过来之前要先处理的。

单体版已有的要求仍然适用，见 mugsun-boot 的[配置](https://docs.mugsun.com/boot/configuration)和[上线安全清单](https://docs.mugsun.com/boot/security-checklist)。

## 1. 只有网关对外

对外只开放网关的端口，默认 8080。各服务的端口（8101 起）只允许网关和其他服务访问。

原因有两个：

- 服务靠网关写的 `X-Forwarded-For` 等请求头知道客户端的地址，并且采信任何内网地址发来的这类请求头。能从内网直接连到服务端口的人可以伪造客户端地址，登录日志和按地址做的限制都会被骗过。
- 服务之间互相调用的内部接口（`/inner` 开头）开在各服务自己的端口上，网关不转发。这些接口要验签名，但内容不加密，各服务签名用的是同一把密钥，见第 4 节。

网关的地址不在常见的内网地址段时，要在各服务上设置 `server.tomcat.remoteip.internal-proxies`，写成能匹配网关地址的正则。否则服务会把网关的地址当成客户端地址。常见的内网地址段指 10.0.0.0/8、172.16.0.0/12、192.168.0.0/16、100.64.0.0/10 和本机回环地址。

## 2. 网关前面还有反向代理时

把代理的地址段配给网关，多个用逗号分隔：

```bash
export MUGSUN_OPENAPI_TRUSTED_PROXIES=172.30.88.10/32
```

- 只有请求直接来自这些地址时，网关才采信它带来的 `X-Forwarded-For`、`X-Forwarded-Proto`、`X-Forwarded-Host` 和 `X-Forwarded-Port`。其他来源带的这类请求头一律删掉。
- 这个变量在单体版里配给后端。微服务版里配给网关，后端服务不用再配。
- 留空表示网关直接面向客户端。前面有代理却留空的话，登录日志里记的全是代理的地址。
- 地址段写得越窄越好。写进来的地址都能让网关相信它报的客户端地址。

网关有两项配置不要动：

| 配置 | 要求 |
| --- | --- |
| `server.forward-headers-strategy` | 必须是 `none`，改成别的值网关拒绝启动 |
| `spring.cloud.gateway.server.webflux.trusted-proxies` | 不要设置。可信代理只用上面的变量配 |

## 3. 所有服务和实例用同一套密钥

单体版在没有显式配置密钥时，会自己生成：有的保存在 `mugsun.home` 目录（环境变量 `MUGSUN_HOME`，默认 `~/.mugsun`），有的只在进程里。

微服务版有多个进程。各自生成的话密钥互不相同，一个进程加密的数据、签过名的文件地址，另一个进程解不开、验不过。

同一台机器上用同一个系统用户启动各服务时，它们共用 `~/.mugsun`，密钥是同一套。分开部署在不同的机器或容器里时就不是了，下面两种做法要选一种：

- 用环境变量显式给出，所有服务用同样的值：`MUGSUN_SM4_KEY`、`MUGSUN_API_KEY`、`MUGSUN_SM2_PRIVATE_KEY` 和 `MUGSUN_SM2_PUBLIC_KEY`、`MUGSUN_SIGN_PRIVATE_KEY` 和 `MUGSUN_SIGN_PUBLIC_KEY`、`MUGSUN_FILE_SIGN_SECRET`。
- 所有服务挂载同一个 `mugsun.home` 目录。第一次启动时先只启动 system，等它把密钥生成完，再启动其它服务。

各变量的含义见 mugsun-boot 的配置文档。

ai 服务也要用这一套：模型的密钥、OpenAI 兼容接口用的 AI 密钥等凭据，是用 `MUGSUN_SM4_KEY` 加密后存进主库的。ai 服务的这把密钥和别的服务不一样时，这些凭据解不开。

生产环境打开了本地存储直出（`enable-access: true`）时，`MUGSUN_FILE_SIGN_SECRET` 每个服务都要配。校验文件地址签名的过滤器各服务都带，没配会拒绝启动。

Redis 也要是同一个：登录态存在 Redis 里。`dev` 配置下连不上 Redis 时会退回进程内存储，多个实例之间登录态就不通了。设置 `mugsun.redis.mode=redis` 可以禁止这种降级。

## 4. 服务之间的调用要配一把共享密钥

有三个功能要一个服务去调另一个服务：

| 功能 | 谁调谁 |
| --- | --- |
| AI 问图层、AI 解读埋点数据 | ai 调 gis、track，取库里的实数 |
| 埋点分析的「问数据」用大模型解析提问 | track 调 ai |
| 代码生成的建模草稿用大模型出 | system 调 ai |

调用走 `/inner` 开头的内部接口，直接连对方的服务端口，不经网关。内部接口不看登录令牌，只认签名。签名用的密钥由环境变量 `MUGSUN_INNER_SECRET` 给出：

```bash
openssl rand -hex 32
```

- system、gis、track、ai 配成同一个值，至少 32 个字符。网关不用配。
- 它没有默认值，也不会自动生成。配了但不到 32 个字符，服务拒绝启动。
- 换密钥时这四个服务要一起换、一起重启。新旧密钥并存的那段时间，上面三个功能按下表「两边不一致」处理。
- 拿到这把密钥、又能连到服务端口的人，可以读到任意租户的图层和埋点统计数字，也可以消耗已配置的大模型。要像保管数据库口令一样保管它，泄露后立即更换。

没配或配得不一致时，服务照常启动，其它功能不受影响，变的只有这三个功能：

| 情况 | AI 问图层、解读埋点数据 | 埋点「问数据」 | 建模草稿 |
| --- | --- | --- | --- |
| 发起调用的服务没配 | 提示内部调用没有开通 | 只按规则解析提问，没有提示 | 按规则生成 |
| 被调用的服务没配，或者两边不一致 | 提示对方服务暂时不可用 | 按规则解析提问，并提示模型调用失败 | 按规则生成 |

页面上的提示不带地址和服务名，原因要到日志里看：

| 日志里的话 | 出现在 | 意思 |
| --- | --- | --- |
| `没有配置 mugsun.cloud.inner.secret……服务之间的内部调用停用` | 启动日志 | 这个服务没配密钥 |
| `注册中心里有 ……，但本服务没有配置 mugsun.cloud.inner.secret` | 发起调用的服务 | 对方在，本服务没配密钥。对每个对方服务只提示一次 |
| `拒绝了一次内部调用 ……：签名不对` | 被调用的服务 | 两边的密钥不一致 |
| `拒绝了一次内部调用 ……的时间和本机差了 …… 秒` | 被调用的服务 | 两台机器的时间没有对齐 |
| `…… 拒绝了内部调用 ……` | 发起调用的服务 | 被对方拒绝了，具体原因在对方的日志里 |
| `……的随机串记不进 Redis` | 被调用的服务 | Redis 连不上，内部调用被拒绝 |

其它要求：

- 各机器要开时间同步。调用方和被调用方的时间相差超过 60 秒，调用会被拒绝。这个范围用 `mugsun.cloud.inner.clock-skew` 调，最大 10 分钟。
- 内部调用靠 Redis 防重放：每个请求带一个随机串，被调用的服务把用过的记在 Redis 里。Redis 连不上时内部调用一律被拒绝。
- 签名只证明请求来自拿着密钥的服务、内容没被改过，请求和响应本身不加密。服务之间的网络要是可信的内网，第 1 节的要求不变。
- 各服务用的是同一把密钥，所以调用方报的服务名只用来记日志，不能当成权限依据。
- 内部调用只带租户，不带用户。被调用的服务按这个租户查数据，不做按用户的数据权限过滤；用户有没有权限用这个功能，由发起调用的服务在调用之前判断。
- 调用方按 Nacos 里登记的地址找对方。冒名注册的实例没有密钥，签不出响应，它自己编的内容不会被采信。但它能看到发过去的请求，也能把请求原样转给真的实例、看到返回的数据，所以第 5 节对 Nacos 的要求同样要做到。
- 对方要去调大模型的调用最长等 120 秒，其它调用 10 秒。大模型比这更慢时，在发起调用的服务上调大 `mugsun.cloud.inner.long-read-timeout`。

## 5. Nacos 要开鉴权，只在内网开放

网关按 Nacos 里登记的地址转发请求。能往 Nacos 注册实例的人，可以把用户的请求引到自己的机器上。

- 生产环境的 Nacos 打开客户端鉴权。官方镜像用环境变量 `NACOS_AUTH_ENABLE=true`。
- 网关和各服务用 `MUGSUN_NACOS_USERNAME`、`MUGSUN_NACOS_PASSWORD` 接入。
- Nacos 的端口只对网关和各服务开放。
- 机器有多块网卡时，用 `MUGSUN_SERVICE_IP` 写明服务注册的地址。

`deploy/dev` 下的 compose 文件只给本机开发用：它没有开客户端鉴权，端口只绑定 127.0.0.1。不要拿去生产环境用。

## 6. 先启动 system：数据库迁移只由它执行

主库的迁移脚本和单体版是同一份，都在 system 服务里。建表、升级表结构、写入初始数据只在 system 启动时做。GIS 和 AI 的表也在这份脚本里，所以主库的库表和单体版相同。

其它服务不执行迁移，启动时直接用库里已有的表：

- 首次部署时先启动 system，等它启动完成，再启动其它服务。
- 升级时先升 system，再升其它服务。

埋点库 `mugsun_track` 是另一个库，由 track 服务自己管：

- track 启动时库不存在就建库，然后执行埋点库的迁移脚本。数据库账号要有 `CREATEDB` 权限，或者事先把库建好。mugsun-boot 的 `scripts/init-db.sql` 两样都做了。
- 埋点库的连接用 `TRACK_JDBC_URL` 这组变量，和单体版相同。不设时按主库的地址和账号连 `mugsun_track`。
- 埋点分析的菜单在主库里，由 system 的迁移写入。所以 track 也要在 system 之后启动。

## 7. 权限规则和租户数据源的改动，其它服务最长 30 秒后生效

有三样设置，每个服务进程都在内存里留了一份，而修改它们的管理页面只调用 system：

| 设置 | 在 system 里 | 在其它服务里 |
| --- | --- | --- |
| 数据权限规则 | 保存后立即生效 | 最长 30 秒后生效 |
| 接口权限规则和角色授权 | 保存后立即生效 | 最长 30 秒后生效 |
| 租户的独立数据源（新增、修改、停用） | 保存后立即生效 | 最长 30 秒后生效 |

其它服务每 30 秒从库里重新加载一次。也就是说，收回一个角色的接口授权、收紧一条数据权限规则之后，gis、track 和 ai 服务在最长 30 秒内还按旧的规则放行。给租户换数据源之后的 30 秒内，这几个服务还在读写原来的库。埋点库不分租户，不受租户数据源影响。

间隔用 `mugsun.cloud.sync.interval-ms` 调，单位是毫秒，配给 system 以外的服务。调小了生效快，查库也更频繁。

不受这个延迟影响的：

- 用户的角色和按钮权限，每次请求都查库。
- 登录态，存在 Redis 里。踢人下线在所有服务里立即生效。
- 系统参数，改动经 Redis 通知各服务。

system 服务目前按单实例部署。部署多个 system 实例时，表里的三样设置只在处理保存请求的那个实例上生效，其它 system 实例要重启才跟上。

## 8. gis、track 和 ai 服务可以不部署

system 按 Nacos 里有没有对应服务的实例判断模块在不在：

| 服务名 | 有实例 | 没有实例 |
| --- | --- | --- |
| `mugsun-gis` | 显示 GIS 菜单，`/system/gis/**` 由 gis 应答 | 不显示 GIS 菜单，`/system/gis/**` 返回 503 |
| `mugsun-track` | 显示埋点分析菜单，`/track/**` 和 `/system/track/**` 由 track 应答 | 不显示埋点分析菜单，这两类路径返回 503 |
| `mugsun-ai` | 显示 AI 菜单，`/system/ai/**` 和 `/v1/**` 由 ai 应答 | 不显示 AI 菜单，这两类路径返回 503 |

- 服务注册上之后，菜单几秒内出现。
- 实例全部消失后，菜单过一分钟左右才消失。重启或滚动发布服务时菜单不会少一块，这段时间里点进对应的页面会看到 503。这段时间用 system 的 `mugsun.cloud.module.absent-grace` 调，默认 `60s`。
- 已经登录的用户要刷新页面，菜单才会变。
- `/track/**` 是埋点 SDK 上报用的公开接口。没部署 track 时，SDK 的上报会收到 503。
- `/v1/**` 是 OpenAI 兼容的开放接口，调用方用 AI 密钥，不用登录。没部署 ai 时，这些调用会收到 503。
- 只部署了其中几个服务时，跨服务的功能怎么表现，见 [README](../README.md) 的「服务之间的调用」。

单体版的开关照样可用：

| 开关 | 配给谁 | 效果 |
| --- | --- | --- |
| 系统参数 `gis.module.enabled=false` | 在管理后台改 | 菜单隐藏，gis 服务对 `/system/gis/**` 返回 404，不用停服务 |
| `MUGSUN_GIS_ENABLED=false` | system | system 当 GIS 不存在，菜单隐藏 |
| `MUGSUN_TRACK_ENABLED=false` | system | system 当埋点不存在，菜单隐藏 |
| 系统参数 `ai.module.enabled=false` | 在管理后台改 | 菜单隐藏，ai 服务对 `/system/ai/**` 和 `/v1/**` 返回 404，不用停服务 |
| `MUGSUN_AI_ENABLED=false` | system | system 当 AI 不存在，菜单隐藏 |

- 埋点没有系统参数开关，单体版也没有。
- 配给 system 的三个环境变量只管菜单和登录信息。对应的服务还在跑的话，它的接口照常应答，别的服务也照常调它的内部接口。
- gis、track 和 ai 服务自己不接受这几个值，带着 `false` 会拒绝启动。不需要某个模块就不要部署它的服务。

## 9. 健康检查

| 地址 | 谁应答 | 用途 |
| --- | --- | --- |
| `/_gateway/health` | 网关 | 负载均衡对网关的探活 |
| `/actuator/**` | system | 和单体版一样，管理后台的监控页在用，由服务自己鉴权 |

gis、track 和 ai 服务的 `/actuator` 不经网关，要探活就在内网直接访问它们自己的端口。

## 10. 转发相关的行为

- 网关连后端服务的超时是 3 秒。不设响应超时，导出和流式输出这类长请求不会被中途切断，和单体版一致。
- AI 对话的流式输出由网关边收边转，不会攒到最后一起发。
- 上传大小等限制仍由服务自己控制，网关不另加限制。
- 没有可用实例时返回 503，连不上实例时返回 502，超时返回 504，响应体是和后端相同的统一格式。
- 网关把实例列表缓存 5 秒。一个实例停掉后的 5 秒内，网关还可能把请求转给它，这些请求返回 502。要做到升级时不丢请求，先在 Nacos 里把实例下线，等 10 秒再停进程。
- 新实例注册后，网关最迟 5 秒开始往它转发，不用重启网关。

## 11. 日志里的令牌

站内信的 WebSocket 连接把登录令牌放在地址里（`/ws/message?token=...`），这是单体版原有的做法。

- 网关自己记的日志只有路径，不带查询串。
- 框架在个别出错场景下会把完整地址记进日志。网关前面的反向代理如果开了访问日志，也会记下完整地址。

这些日志要按含有登录令牌来保管，或者在代理上配置成不记查询串。

## 12. track 服务也用文件存储

会话回放、接口报文和 sourcemap 存在文件存储里，由 track 服务读写。配置项和 system 用的是同一组（`dromara.x-file-storage.*`）。gis 和 ai 服务不带文件存储，不用配这一组。

- 用本地存储时，track 把文件写在自己机器的存储目录里。生产配置下这个目录是 `FILE_STORAGE_PATH`，默认 `/var/lib/mugsun/files/`。容器部署要给它挂卷，否则容器重建后回放和 sourcemap 就没了。
- track 部署多个实例时，用对象存储，或者让各实例挂同一个共享目录。
- 生产配置里 `FILE_DOMAIN` 没有默认值，track 也要配，和 system 用同一个值即可。
- 打开本地存储直出后，带文件存储的服务会在自己的端口上应答 `/file/**`。各服务都带着校验签名的过滤器：公开前缀下的文件可以直接取，其余的没有有效签名一律 404。经网关访问的 `/file/**` 只转给 system。
- 用环境变量或启动参数改 `dromara.x-file-storage.local-plus[0]` 的某一项时，要把这个列表元素的全部字段一起写上。Spring Boot 对列表是整项替换，只写一个字段会把其它字段丢掉。

## 13. PowerJob 执行器只在 system 上启动

`POWERJOB_ENABLED`、`POWERJOB_SERVER`、`POWERJOB_APP_NAME` 这组变量只对 system 起作用。track 服务不启动执行器，给它设 `POWERJOB_ENABLED=true` 也一样。

原因：同一个应用名下的执行器，调度中心派任务时不加区分。track 也接入的话，system 的任务会被派到 track 上，而 track 没有对应的处理器。

和单体版的差别：

- 管理后台的定时任务页只能选 system 里的处理器。
- 埋点的三个处理器 `TrackStatsProcessor`、`TrackMaintainProcessor`、`TrackUvBackfillProcessor` 不能再由 PowerJob 触发。
- 聚合、会话结算、清理和分区维护本来就由 track 服务内的定时任务执行，不受影响。
- 历史 UV 回填改用启动开关：给 track 设 `MUGSUN_TRACK_UV_BACKFILL_ON_STARTUP=true` 启动一次，回填在后台执行，跑完后去掉这个变量。细节见 mugsun-boot 的 `docs/upgrade/track-uv-backfill.md`。
- 从单体版迁过来时，调度中心里指向这三个处理器的任务要先停用或删除。留着的话会被派给 system，执行失败。

## 14. 站内信推送固定走 Redis

`mugsun.websocket.sender-type` 在微服务版里固定是 `redis`，不要用环境变量或启动参数改成 `local`。

WebSocket 连接都在 system 上。track 发告警时，站内信写进主库，实时推送发到 Redis，由 system 投递给在线用户。改成 `local` 后，system 不再接收 Redis 里的推送，track 的告警就到不了在线用户。

写在 `config/application-local.yml` 里的 `local` 不起作用。

## 15. 审批流里的 AI 预审节点用不了

单体版的流程设计里可以放 AI 预审节点：流程走到这一步时先由 AI 应用审一遍，再往下走。微服务版暂时不支持这种节点。

原因：这种节点靠 AI 模块里的一个监听器执行。监听器在 ai 服务里，审批流在 system 里跑。流程引擎找不到监听器时不报错，直接跳过，这个节点就成了一条发起人自己能点通过的待办，预审等于没做。所以 system 把它拦下来：

- 发起、通过、驳回、撤回，只要下一步会走到 AI 预审节点，操作被拒绝，库里什么都不写。
- 单子已经停在 AI 预审节点上的，不能通过，可以驳回到前面的节点，也可以作废。单体版里这样的单子可以人工点通过，微服务版不行。
- 作废不受影响。

操作的人会看到下面两种提示之一，提示里写了该怎么办：

- 「流程的下一步是 AI 预审节点，微服务版暂时不支持这种节点，这次操作没有生效……」
- 「这一步是 AI 预审节点，微服务版暂时不支持这种节点，不能通过……」

从单体版迁过来之前，先在单体版里处理：

1. 用下面第一条语句找出带这种节点的流程，在流程设计里去掉这个节点，重新发布。
2. 用第二条语句找出还在办的、流程里带这种节点的单子。重新发布只影响以后发起的单子，这些单子仍然按原来的版本走：
   - `on_ai_node` 是 1 的正停在这种节点上，在单体版里办完或作废。
   - 是 0 的还没停在上面。后面还要经过这种节点的，迁过来以后走到它的前一步会被拦住，要撤回或作废后重新发起。

```sql
select d.flow_code, d.flow_name, d.version, d.is_publish, n.node_name
from flow_node n
join flow_definition d on d.id = n.definition_id
where n.listener_path like '%AiApprovalNodeListener%'
  and coalesce(n.del_flag, '0') <> '1'
  and coalesce(d.del_flag, '0') <> '1'
order by d.flow_code, d.version;
```

```sql
select i.business_id, t.instance_id, d.flow_code, d.version, t.node_name,
       case when cur.listener_path like '%AiApprovalNodeListener%' then 1 else 0 end as on_ai_node
from flow_task t
join flow_instance i on i.id = t.instance_id
join flow_definition d on d.id = t.definition_id
join flow_node cur on cur.definition_id = t.definition_id and cur.node_code = t.node_code
where t.definition_id in (
        select definition_id from flow_node
        where listener_path like '%AiApprovalNodeListener%' and coalesce(del_flag, '0') <> '1')
  and coalesce(t.del_flag, '0') <> '1'
order by t.instance_id;
```

第一条里 `is_publish` 是 1 表示当前发布的版本，9 表示已经被新版本取代、但还有单子在按它走，0 表示没有发布。

其它要知道的：

- 发布带这种节点的流程不会被拦，拦的是走到它的那一步。
- 这种节点和票签、会签节点挨着时有一个缺口：一票定了结果（票签的票数提前够了，或者会签时有人驳回）、而下一步正好是这种节点时，操作被拦下，但其他还没表态的人的待办这时已经被引擎清掉了。单子留在最后操作的人手里，要作废后重新发起。
- 实验开关 `mugsun.lab.seed-fronttest=true` 会让 ai 服务在启动时发布两个带这种节点的演示流程（`ai-preaudit` 和 `ai-foreach-preaudit`），它们在微服务版里发起不了。这个开关不要在生产环境打开。
- 被拦下的操作在 system 的日志里有一条 WARN：`拦下一次要走到 AI 预审节点的操作`，或者 `拦下一次在 AI 预审节点上的办理`。
- 日志里出现 ERROR `AI 预审节点的任务被建出来了，写库之前的拦截没有生效` 时，说明有操作绕过了上面的拦截，对应的单子要人工核对。
- system 启动时会检查拦截有没有装到流程引擎上，没装上就拒绝启动。
