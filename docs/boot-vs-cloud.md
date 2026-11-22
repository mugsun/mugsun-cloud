# 单体版和微服务版不一样的地方

> 这一页由 `scripts/boot_cloud_diff.py` 生成，不要手改。要改里面的说明：原因写在 `scripts/boot-cloud-diff.json` 里，第四节每个类后面的那句话是它类注释的第一句。改完重新生成。

业务代码只有一份：在单体版（mugsun-boot）里维护，用脚本同步到这边各模块的 `src/boot/`，同步来的不手改（见 [从 mugsun-boot 同步代码](./sync-from-boot.md)）。两边的业务逻辑一样，首先靠的是这一点。

这一页列的是**没法只有一份**的地方：

| | 是什么 | 现在有多少 |
| --- | --- | --- |
| 一 | 同一件事，两边各有一个实现 | 11 处 |
| 二 | 业务上两边做法不同 | 2 处 |
| 三 | 单体版有、这边不带的类 | 1 个 |
| 四 | 只有微服务版才有的类 | 103 个 |

微服务版手写的类一共 130 个，每一个都在这一页的某一节里。类名的写法：单体版的类省略开头的 `com.mugsun.boot.`，微服务版手写的类省略 `com.mugsun.cloud.`。

## 这一页怎么保持是对的

```bash
python3 scripts/boot_cloud_diff.py           # 重新生成这一页
python3 scripts/boot_cloud_diff.py --check   # 只检查，推送前要通过
```

下面几种情况 `--check` 不通过：

- 漏记：手写的类实现或继承了同步来的类型，第一节却没有它。
- 过期：记了的类或测试已经不存在，或者那个类不再实现同步来的类型。
- `sync-rules.json` 里不同步的类没有写原因。
- 手写的类没有类注释，或者有手写代码的模块没有写它是干什么的。
- 这一页和按现在的代码重新生成出来的不一样。

脚本查不出来的两样，靠改的人自己记：

- 第二节「业务上两边做法不同」。一个手写的类有没有改变业务的做法，脚本判断不了。新写了这样的类，在 `scripts/boot-cloud-diff.json` 的 `differs` 里加一条。
- 配置。微服务版改掉单体版默认值的地方都在 `mugsun-cloud-common` 的 `mugsun-cloud-defaults.yml` 里，每一项上面有注释写着原因，这一页不逐项列。

## 两边的结果怎么保证一样

1. 绝大部分业务代码两边是同一份文件。`python3 scripts/sync_from_boot.py --locked --check` 检查同步来的代码没有被手改。
2. 第一节、第二节的每一处都有测试，名字列在各自下面。
3. 同一组请求分别发给单体版和微服务版，逐个对比返回的结果：**还没有做**，排在各模块拆完以后。

## 一、同一件事，两边各有一个实现（11 处）

单体版里各模块在同一个进程里，互相直接调用。微服务版里它们在不同的服务里，所以同一个接口这边另写了一个实现。接口本身在同步来的代码里，两边用的是同一个。做法有两种：

- **转给对方服务**：这边的实现只负责把请求发给对方服务，对方服务里执行的还是单体版的那个实现，业务逻辑没有第二份。
- **这边重写**：照单体版的规则另写了一遍，靠测试守着。单体版改了这条规则，这边要跟着改。

### 1. AI 向地图、埋点要库里的真实数字

- 接口：`common.module.ModuleInsightPort`（同步在 mugsun-cloud-common）
- 单体版的实现：`gis.GisInsightAdapter`（同步在 mugsun-cloud-gis）、`track.TrackInsightAdapter`（同步在 mugsun-cloud-track）
- 微服务版的实现：`ai.RemoteInsightPort`（mugsun-cloud-ai）
- 一起用到的手写类：`ai.RemoteInsightPorts`（mugsun-cloud-ai）、`common.insight.LocalInsightEndpoint`（mugsun-cloud-common）、`gis.GisInsightEndpoint`（mugsun-cloud-gis）、`track.TrackInsightEndpoint`（mugsun-cloud-track）
- 为什么另写：「AI 问图层」「AI 解读埋点数据」要先取到图层、埋点的真实数字。单体版里 AI、地图、埋点在一个进程里，AI 直接调地图、埋点模块的实现；微服务版里它们是三个服务，ai 服务里没有那两个实现。
- 做法：转给对方服务
- 怎么保证一样：ai 服务把请求发给 gis、track 服务的内部接口，取数的仍然是单体版的那两个实现，一行没动。对方服务不在线时告诉用户现在查不了，效果和单体版没装那个模块时一样。
- 测试：`RemoteInsightPortsTest`、`LocalInsightEndpointTest`、`GisInsightEndpointTest`、`TrackInsightEndpointTest`

### 2. 删租户时清掉它的 AI 密钥

- 接口：`tenant.TenantCredentialPurger`（同步在 mugsun-cloud-common）
- 单体版的实现：`ai.tenant.AiSecretTenantPurger`（同步在 mugsun-cloud-ai）
- 微服务版的实现：`system.ai.CloudAiSecretPurger`（mugsun-cloud-system）
- 为什么另写：单体版删租户时，在同一个事务里调用各模块的清理实现，AI 密钥由 AI 模块的实现清。微服务版里删租户在 system 服务，AI 模块在 ai 服务，system 里没有那个实现，不补的话租户删掉以后密钥还留在库里。
- 做法：这边重写
- 怎么保证一样：密钥表在各服务共用的主库里，system 直接删，删除语句和单体版相同，仍然和删租户在同一个事务里，ai 服务没启动也照样清。单体版的可选模块以后新增别的清理实现时，`TenantPurgerGuardTest` 会失败，提醒在 system 里补上。
- 测试：`CloudAiSecretPurgerTest`、`TenantPurgerGuardTest`

### 3. 发短信、验短信验证码

- 接口：`common.sms.SmsGateway`（同步在 mugsun-cloud-common）
- 单体版的实现：`system.service.SmsService`（同步在 mugsun-cloud-resource）
- 微服务版的实现：`api.resource.RemoteSmsGateway`（mugsun-cloud-resource-api）
- 一起用到的手写类：`resource.SmsCodeEndpoint`（mugsun-cloud-resource）、`resource.SmsTextEndpoint`（mugsun-cloud-resource）
- 为什么另写：单体版里登录、双因子和短信通知直接调同一个进程里的短信服务类。微服务版里短信在文件和短信服务，这几处还在 system。
- 做法：转给对方服务
- 怎么保证一样：system 把每一次调用发给文件和短信服务的内部接口，那边执行的仍然是单体版的那个短信服务类；发不出去时（发得太频繁、通道没配、短信平台没发出去）给用户的话是同一句。手机号或验证码是空的直接算没通过，不发起调用。单体版没有的情况只有一种：文件和短信服务没在运行或者没调成。这时发短信和验验证码报「短信服务暂时不可用」，不当成发出去了，也不记成验证码错误；开发环境回显验证码按不回显算。
- 测试：`RemoteSmsGatewayTest`、`SmsCodeEndpointTest`、`SmsTextEndpointTest`

### 4. 手机端首页取待办

- 接口：`app.AppTodoReader`（同步在 mugsun-cloud-common）
- 单体版的实现：`app.AppTodoService`（同步在 mugsun-cloud-flow）
- 微服务版的实现：`api.flow.RemoteAppTodoReader`（mugsun-cloud-flow-api）
- 一起用到的手写类：`flow.AppTodoEndpoint`（mugsun-cloud-flow）
- 为什么另写：单体版里手机端首页直接调同一个进程里的待办服务类，显示待办数和前几条待办。微服务版里审批流是单独的服务，手机端首页还在 system。
- 做法：转给对方服务
- 怎么保证一样：system 把当前登录用户的编号发给审批流服务的内部接口，那边执行的仍然是单体版的那个待办服务类，查的是同一个租户，结果和手机端待办列表一致。单体版没有的情况只有一种：审批流服务没在运行或者没调成。这时首页报「待办暂时取不到」，不显示成 0 条待办。
- 测试：`RemoteAppTodoReaderTest`、`AppTodoEndpointTest`

### 5. 手机端首页取公告

- 接口：`app.AppNoticeReader`（同步在 mugsun-cloud-common）
- 单体版的实现：`app.AppNoticeService`（同步在 mugsun-cloud-desk）
- 微服务版的实现：`api.desk.RemoteAppNoticeReader`（mugsun-cloud-desk-api）
- 一起用到的手写类：`desk.AppNoticeSummaryEndpoint`（mugsun-cloud-desk）
- 为什么另写：单体版里手机端首页直接调同一个进程里的公告服务类，显示公告未读条数和最近几条公告。微服务版里公告在工作台服务，手机端首页还在 system。
- 做法：转给对方服务
- 怎么保证一样：system 把当前登录用户的编号和他有没有公告管理权限发给工作台服务的内部接口，那边执行的仍然是单体版的那个公告服务类，查的是同一个租户，结果和手机端公告列表一致。权限在 system 查，用的是单体版同一句判断。单体版没有的情况只有一种：工作台服务没在运行或者没调成。这时首页报「公告暂时取不到」，不显示成 0 条未读。
- 测试：`RemoteAppNoticeReaderTest`、`AppNoticeSummaryEndpointTest`

### 6. 全局搜索按标题找公告

- 接口：`system.service.NoticeTitleFinder`（同步在 mugsun-cloud-common）
- 单体版的实现：`system.service.NoticeReadService`（同步在 mugsun-cloud-desk）
- 微服务版的实现：`api.desk.RemoteNoticeTitleFinder`（mugsun-cloud-desk-api）
- 一起用到的手写类：`desk.NoticeTitlesEndpoint`（mugsun-cloud-desk）
- 为什么另写：单体版里顶栏的全局搜索直接调同一个进程里的公告读取类，按标题找当前用户看得到的公告。微服务版里公告在工作台服务，全局搜索还在 system。
- 做法：转给对方服务
- 怎么保证一样：system 把搜索词、当前登录用户的编号和他有没有公告管理权限发给工作台服务的内部接口，那边执行的仍然是单体版的那个公告读取类，查的是同一个租户，找到的标题和顺序一样；搜索词是空的直接算没找到，不发起调用。单体版没有的情况只有一种：工作台服务没在运行或者没调成。这时这次搜索报「公告暂时搜不到」，不当成没找到；菜单、部门、角色这几类结果也跟着出不来，和单体版里查公告出错时整次搜索报错是一样的。
- 测试：`RemoteNoticeTitleFinderTest`、`NoticeTitlesEndpointTest`

### 7. 代码生成的「规则建模」请大模型出草稿

- 接口：`gen.GenAiDraftEnhancer`（同步在 mugsun-cloud-common）
- 单体版的实现：`ai.weave.GenAiDraftEnhancerImpl`（同步在 mugsun-cloud-ai）
- 微服务版的实现：`develop.RemoteGenAiDraftEnhancer`（mugsun-cloud-develop）
- 一起用到的手写类：`ai.GenDraftEndpoint`（mugsun-cloud-ai）
- 为什么另写：单体版里代码生成直接调 AI 模块的实现，让大模型出建模草稿。微服务版里代码生成单独是一个服务，大模型在 ai 服务。
- 做法：转给对方服务
- 怎么保证一样：代码生成服务把描述发给 ai 服务的内部接口，出草稿的仍然是单体版的那个实现。ai 服务没部署、调不通、返回的内容读不出来，都当作没有结果，改用规则解析，和单体版没装 AI 模块或者大模型没给出结果时一样。
- 测试：`RemoteGenAiDraftEnhancerTest`、`GenDraftEndpointTest`

### 8. AI 模块在不在、开没开

- 接口：`common.module.AiModuleStatus`（同步在 mugsun-cloud-common）
- 单体版的实现：`ai.AiModuleService`（同步在 mugsun-cloud-ai）
- 微服务版的实现：`module.CloudAiModuleStatus`（mugsun-cloud-common）
- 一起用到的手写类：`module.ServicePresence`（mugsun-cloud-common）
- 为什么另写：菜单里显不显示 AI、登录信息里 AI 的开关都问这个接口。单体版里它由 AI 模块自己实现，「在不在」看模块的包有没有打进来。微服务版里 AI 是另一个服务，要问它的 system 和登录服务里都没有那个实现。
- 做法：这边重写
- 怎么保证一样：「在不在」改成看注册中心里有没有 ai 服务的实例（结果缓存几秒，实例没了以后过一段宽限期才算不在）。另外两个开关和单体版是同一个、规则也一样：配置项 `mugsun.ai.enabled` 不设按开，只认 true/false；系统参数 `ai.module.enabled` 可以在运行时关掉。
- 测试：`ModuleStatusTest`

### 9. 地图模块在不在、开没开

- 接口：`common.module.GisModuleStatus`（同步在 mugsun-cloud-common）
- 单体版的实现：`gis.GisModuleService`（同步在 mugsun-cloud-gis）
- 微服务版的实现：`module.CloudGisModuleStatus`（mugsun-cloud-common）
- 一起用到的手写类：`module.ServicePresence`（mugsun-cloud-common）
- 为什么另写：菜单里显不显示地图、登录信息里地图的开关都问这个接口。单体版里它由地图模块自己实现，「在不在」看模块的包有没有打进来。微服务版里地图是另一个服务，要问它的 system 和登录服务里都没有那个实现。
- 做法：这边重写
- 怎么保证一样：「在不在」改成看注册中心里有没有 gis 服务的实例。另外两个开关和单体版是同一个、规则也一样：配置项 `mugsun.gis.enabled` 不设按开，只认 true/false；系统参数 `gis.module.enabled` 可以在运行时关掉。
- 测试：`ModuleStatusTest`

### 10. 埋点模块在不在、开没开

- 接口：`common.module.TrackModuleStatus`（同步在 mugsun-cloud-common）
- 单体版的实现：`track.TrackModuleService`（同步在 mugsun-cloud-track）
- 微服务版的实现：`module.CloudTrackModuleStatus`（mugsun-cloud-common）
- 一起用到的手写类：`module.ServicePresence`（mugsun-cloud-common）
- 为什么另写：菜单里显不显示埋点分析、登录信息里埋点的开关都问这个接口。单体版里它由埋点模块自己实现，「在不在」看模块的包有没有打进来。微服务版里埋点是另一个服务，要问它的 system 和登录服务里都没有那个实现。
- 做法：这边重写
- 怎么保证一样：「在不在」改成看注册中心里有没有 track 服务的实例。配置项 `mugsun.track.enabled` 的规则和单体版一样。
- 测试：`ModuleStatusTest`

### 11. 埋点「问数据」请大模型理解提问

- 接口：`common.module.TrackAskLlmGateway`（同步在 mugsun-cloud-common）
- 单体版的实现：`ai.weave.TrackAskLlmGatewayImpl`（同步在 mugsun-cloud-ai）
- 微服务版的实现：`track.RemoteTrackAskLlmGateway`（mugsun-cloud-track）
- 一起用到的手写类：`ai.TrackAskIntentEndpoint`（mugsun-cloud-ai）
- 为什么另写：单体版里埋点的「问数据」直接调 AI 模块的实现，让大模型把提问解析成意图。微服务版里埋点在 track 服务，大模型在 ai 服务。
- 做法：转给对方服务
- 怎么保证一样：track 把提问发给 ai 服务的内部接口，解析的仍然是单体版的那个实现。三种情况和单体版一一对应：ai 服务没部署，直接用规则解析、不提示；ai 服务在但没调成，用规则解析并提示模型调用失败；调成了，意图和参数原样用。数字仍然全部来自 track 自己的埋点分析。
- 测试：`RemoteTrackAskLlmGatewayTest`、`TrackAskIntentEndpointTest`

## 二、业务上两边做法不同（2 处）

同一个功能，用户在两个版本里看到的不一样。每一处都是暂时没法做成一样，原因写在下面。

### 1. 审批流里的 AI 预审节点

- 单体版：审批流里可以放 AI 预审节点：由 AI 模块里的监听器调用 AI 应用，决定通过、退回还是转人工。
- 微服务版：这个节点不能用。流程要走到它时被拦下并提示；单体版里留下来、已经停在这个节点上的任务只能退回或作废，不能通过。
- 为什么不一样：那个监听器在 AI 模块里，微服务版的审批流服务里没有它。流程引擎找不到监听器时不报错、直接跳过，节点会变成发起人自己点一下就过的普通任务，预审等于没有，所以拦下。要让它在微服务版里真正能用，得先改 mugsun-boot：把监听器里操作流程的部分和调用 AI 的部分拆开，前一半留在审批流服务，后一半放到 ai 服务。
- 微服务版的类：`flow.AiApprovalNodeGate`（mugsun-cloud-flow）、`flow.AiApprovalNodeGuard`（mugsun-cloud-flow）
- 单体版相关的类：`ai.flow.AiApprovalNodeListener`（同步在 mugsun-cloud-ai）、`system.service.MugsunFlowListener`（同步在 mugsun-cloud-flow）
- 测试：`AiApprovalNodeGateTest`、`AiApprovalNodeGuardTest`

### 2. 监控接口谁能访问

- 单体版：只开放健康检查、基本信息、指标这几个监控接口，哪些要登录、要什么权限由 `ActuatorAuthFilter` 判断。
- 微服务版：监控接口全部打开。内网里直接连服务端口的（监控中心）不用登录、全部能看；经网关来的和单体版一样，还是那几个接口，仍然由 `ActuatorAuthFilter` 判断。
- 为什么不一样：监控中心要直接读每个实例的内存、线程、日志级别。照的是 BladeX 的做法。
- 微服务版的类：`common.monitor.MonitorAccessFilter`（mugsun-cloud-common）、`common.monitor.MonitorAccessConfiguration`（mugsun-cloud-common）
- 单体版相关的类：`monitor.ActuatorAuthFilter`（同步在 mugsun-cloud-common）
- 测试：`MonitorAccessFilterTest`

## 三、单体版有、这边不带的类（1 个）

| 单体版的类 | 为什么不带，这边用什么代替 |
| --- | --- |
| `MugsunApplication` | 单体版的启动入口，一个进程装下所有模块。这边每个服务有自己的启动入口（如 `SystemApplication`）。单体版改了它上面的注解，这边各服务的启动入口要手工跟着改。 |

另外有两样也不同步：单体版的打包模块 `mugsun-boot-server`（只有 pom 和测试），和各模块的测试代码。业务测试在 mugsun-boot 里跑。

## 四、只有微服务版才有的类（103 个）

单体版是一个进程，用不着这些。每个类后面的话是它类注释的第一句。

### mugsun-cloud-config（7 个）

配置中心的接入（从 Nacos 读动态路由、灰度规则这类整份的配置），和日志打码（日志写出去之前把凭证换成星号）。网关和各服务都带着它。

- `config`
  - `ConfigText`：从 Nacos 读来的配置（动态路由、灰度规则）出错时，写进日志的那句话里能带什么。
  - `ConfigYaml`：读运维写在 Nacos 里的 YAML 配置（动态路由、灰度规则）时公用的两步：把文本读成一行行属性，把「有不认识的配置项」说清楚。
  - `NacosConfigSource`：从 Nacos 读这个进程自己要的几份配置（比如网关的动态路由、灰度规则）：开始收请求之前各读一次，之后哪一份改了（包括被删掉）就重新读哪一份。
  - `NacosConfigSourceConfiguration`：路由、灰度规则这类「整份拿来自己解析」的配置，一个进程里只由 `NacosConfigSource` 这一个入口读。
- `config.logging`
  - `LogSecrets`：日志里不该留下的凭证：每一行日志写出去之前先过 `mask`，认出来的换成 `***`。
  - `LogSecretsMasking`：把 `LogSecrets` 接到日志的出口上：给每个写日志的出口（控制台、日志文件）的格式外面包一层 `MaskedLayout`。
  - `MaskedLayout`：包在原来的格式外面：一行日志照原样排好以后，整行过一遍 `LogSecrets.mask` 再交出去。

### mugsun-cloud-common（32 个）

各服务都带的公共层里手写的部分：服务之间的内部调用、灰度标记、机器号、几个实例之间同步内存里的状态、启动和停止的顺序。

- `common.inner`
  - `InnerBreakers`：调用方这边的熔断：替同一个租户调同一个实例的同一个接口接连失败够多次，就先不调它、直接报失败，过一会儿放一个请求去试。
  - `InnerCall`：一次已经验签通过的内部调用。
  - `InnerCallException`：内部调用没有成功。
  - `InnerClient`：调用别的服务的内部接口（`InnerEndpoint`）。
  - `InnerDeadline`：当前线程正在处理的那次内部调用，调用方最多等到什么时候。
  - `InnerEndpoint`：一个只给别的服务调用的接口。
  - `InnerGate`：一个内部接口的名额：同时最多放 `limit` 个请求进去处理，其中同一个租户最多 `perTenant` 个。
  - `InnerMappingGuard`：启动时检查：不允许有 Controller 挂在 `/inner` 下，有就拒绝启动。
  - `InnerNonceStore`：记住最近用过的随机串，让同一个请求不能被重放第二次。
  - `InnerPaths`：现有的内部接口。
  - `InnerProperties`：服务之间互相调用（`/inner` 开头的接口）的配置，前缀 `mugsun.cloud.inner`。
  - `InnerProtocol`：内部调用的约定：请求头叫什么、每个值允许什么字符、签名怎么算。
  - `InnerQueryTimeout`：处理内部调用期间发出的查询，最多跑到调用方不再等为止。
  - `InnerRequestFilter`：`/inner` 下的请求全部由这个过滤器处理：验签通过就调用对应的 `InnerEndpoint`，不通过就拒绝，不再往后传。
  - `ServiceNames`：可选服务在注册中心里的名字，发起内部调用时用。
- `common.lane`
  - `InstanceLane`：这个实例自己的灰度标记（`mugsun.cloud.lane`，环境变量 `MUGSUN_LANE`）。
  - `LaneContext`：当前这次请求的灰度标记。
  - `LaneFilter`：把网关写在请求头 `Lanes.HEADER` 里的灰度标记记成当前请求的标记，处理完清掉。
  - `Lanes`：灰度标记（代码里叫 lane）的约定，和按标记挑实例的规则。
- `common.machine`
  - `MachineNumber`：机器号：每个实例启动时领一个别的实例没在用的号，生成主键时带上它，几个实例同时生成的主键就不会相同。
  - `MachineNumberIdGenerators`：把机器号交给生成主键的三处。
  - `MachineNumberStore`：机器号登记在哪儿。
  - `RedisMachineNumberStore`：机器号登记在 Redis 里：一个号一个键，值是领它的实例，带过期时间。
- `common.shutdown`
  - `DeregisterBeforeStop`：停止时先从注册中心注销，照常处理请求一段时间，然后才开始停。
- `common.startup`
  - `MigrationLock`：几个实例同时启动时，让主库的迁移（Flyway）一个一个来。
  - `StartupLock`：同一个服务的几个实例同时启动时，让「启动时改表结构、写初始数据」这一段一个一个来。
- `common.sync`
  - `SharedStateChangeAspect`：本实例上有人改了数据权限规则、接口权限规则和角色授权、租户独立数据源、租户或套餐时，通知其它实例重新加载。
  - `SharedStateSyncTask`：让本实例内存里的四样东西跟上库里的改动：数据权限规则、接口权限规则和角色授权、租户独立数据源，还有缓存的租户和套餐（见 `TenantCacheSync`：这一样不是重新加载，是把缓存清掉，下一次用到时再查库）。
  - `SharedStateVersion`：共享状态的版本：Redis 里的一个值，所有服务的所有实例读写的是同一个。
  - `TenantCacheSync`：让本实例缓存的租户和套餐跟上别的实例上的改动。
  - `TenantDataSourceRegistryAspect`：本进程里有人直接注册或注销租户数据源时（system 的控制器在保存、删除以后会这样做），让这次操作和定时比较互斥，并告诉 `TenantDataSourceSync` 连接池现在是按哪份配置建的。
  - `TenantDataSourceSync`：让本进程里的租户独立数据源和库里的配置保持一致。

### mugsun-cloud-gateway（39 个）

网关：所有请求的入口。转发、限流、黑白名单、灰度发布、动态路由、访问日志都在这里。

- `gateway`
  - `GatewayApplication`：网关启动入口。
- `gateway.error`
  - `GatewayErrorHandler`：网关自己产生的失败响应（转发出错、请求被网关拒绝）：状态码按出错原因定，响应体用后端的统一格式。
  - `GatewayRejectedException`：网关主动拒绝的请求。
- `gateway.filter`
  - `AccessLogFilter`：访问日志：每个请求结束时记一行，写明谁访问了什么、网关转给了哪个服务的哪个实例、结果和耗时。
  - `ClientAccessFilter`：按客户端地址决定转不转发，三条规则按顺序看，不转发的返回 403。
  - `ClientAddressFilter`：把真实的客户端地址和客户端访问时用的协议、主机名、端口交给后面的服务（普通请求和 WebSocket 握手都经过这里）。
  - `DownstreamCorsHeadersFilter`：跨域响应头只由网关写（规则在配置的 globalcors 里）。
  - `InnerPathGuardFilter`：服务之间互相调用的内部接口（默认 /inner 开头）不对外：网关不转发，按「资源不存在」返回。
  - `LaneFilter`：灰度发布：给每个要转发的请求定灰度标记。
  - `LaneHeaderFilter`：把这次请求的灰度标记交给后面的服务（普通请求和 WebSocket 握手都经过这里）：请求头 `X-Mugsun-Lane`。
  - `NextInstanceOnConnectFailureFilter`：连不上选中的服务实例时，换一个实例再转发一次。
  - `RateLimitFilter`：限流：按客户端地址数请求，超过限额的不转发，返回 429，响应头 `Retry-After` 写明过几秒再试。
  - `TraceIdFilter`：给每个请求定一个编号，放在 `X-Trace-Id` 请求头里传给后面的服务，并保证响应头里也有它。
- `gateway.lane`
  - `InvalidLaneRulesException`：一份灰度规则不合格。
  - `LaneConfiguration`：灰度发布在网关里的装配。
  - `LaneInstanceSupplier`：按灰度标记，从一个服务的实例里挑出这次请求能发的那几个，负载均衡再从里面选一个。
  - `LaneLoadBalancerConfiguration`：每个服务的负载均衡都用这一套实例列表：从注册中心取，缓存几秒（`spring.cloud.loadbalancer.cache.ttl`），再按灰度标记挑（`LaneInstanceSupplier`）。
  - `LaneNames`：灰度发布的几个约定：请求头叫什么、实例把自己的灰度标记登记在注册中心的哪个键上、标记的名字怎么写。
  - `LaneRuleBook`：正在用的那份灰度规则。
  - `LaneRuleParser`：把一份灰度规则的文本读出来。
  - `LaneRules`：一份灰度规则：哪些请求带上哪个灰度标记。
- `gateway.props`
  - `GatewayGuardProperties`：网关自身的配置（前缀 `mugsun.gateway`）。
- `gateway.ratelimit`
  - `Quota`：一条限额，写成 `次数/时长`，例如 `600/60s`。
  - `RateLimitRules`：限流的规则：一条默认的限额，加上给个别路径前缀单独定的限额。
  - `RateLimiter`：数请求：每条规则下的每个客户端一个令牌桶，桶放在这个网关实例的内存里。
  - `RejectionReport`：把「有请求被限流拒绝了」记进日志，又不让日志被刷满。
- `gateway.route`
  - `DynamicRouteConfiguration`：动态路由的总开关：`mugsun.gateway.dynamic-routes` 不是 true 时，下面三个对象一个都不创建，网关的路由只有 application.yml 里写的那些。
  - `DynamicRouteLoader`：把一份路由配置整份换上去：先读，再逐条检查，再转换，全部合格才一次换掉原来的动态路由并通知网关刷新；有一条不合格就整份不用，原来的路由不动。
  - `DynamicRouteLocator`：动态路由交给 Spring Cloud Gateway 的地方。
  - `DynamicRouteParser`：把一份路由配置的文本读成路由定义。
  - `InvalidRoutesException`：一份路由配置不合格。
- `gateway.support`
  - `ClientView`：客户端看到的这次请求：客户端地址，以及它访问时用的协议、主机名和端口。
  - `ClientViewResolver`：按配置的可信代理，从一次请求里算出客户端的真实地址和它访问时用的协议、主机名、端口。
  - `IpRanges`：把文字认成 IP 地址、把名单认成地址段。
  - `PathGuard`：判断一个请求路径能不能转发：服务之间互相调用的内部接口（默认 /inner 开头）不对外，写法可疑的路径直接拒绝。
  - `PathPrefixes`：一组路径前缀，用来判断一个请求路径是不是落在其中某个前缀下面。
  - `RequestPaths`：一次请求涉及的所有路径：客户端发来的、路由改写之前的、改写之后要发给服务的。
  - `ResultWriter`：网关自己产生的失败响应（服务连不上、路径被拦下），格式和后端的统一返回体 `R` 一致，前端不用区分是谁返回的。
  - `TraceIds`：请求编号：一次请求在网关和后面各服务的日志里用同一个编号，排查问题时拿它把几边的日志对上。

### mugsun-cloud-auth（1 个）

登录服务：登录、注册、找回密码、验证码、第三方账号登录、开放授权（OAuth2）、在线用户，手机端的登录。业务类是同步来的，手写的只有启动入口。

- `auth`
  - `AuthApplication`：登录服务启动入口，对应 BladeX 最外层的 blade-auth。

### mugsun-cloud-system（1 个）

system 服务的启动入口。

- `system`
  - `SystemApplication`：system 服务启动入口。

### mugsun-cloud-gis（2 个）

gis 服务的启动入口，和启动时的检查。

- `gis`
  - `GisApplication`：gis 服务启动入口。
  - `GisModuleRequired`：gis 服务启动时确认 GIS 模块确实装配上了。

### mugsun-cloud-track（2 个）

track 服务的启动入口，和启动时的检查。

- `track`
  - `TrackApplication`：track 服务启动入口。
  - `TrackModuleRequired`：track 服务启动时确认埋点模块确实装配上了。

### mugsun-cloud-ai（2 个）

ai 服务的启动入口，和启动时的检查。

- `ai`
  - `AiApplication`：ai 服务启动入口。
  - `AiModuleRequired`：ai 服务启动时确认 AI 模块确实装配上了。

### mugsun-cloud-desk（2 个）

工作台服务：通知公告（管理、我的通知、手机端的公告）和登录后首页的工作台（概览、快捷入口）。业务类是同步来的，手写的有启动入口、接收两个内部调用（首页要的公告、按标题找公告）的一端。

- `desk`
  - `DeskApplication`：工作台服务启动入口。
  - `DeskRequests`：读内部接口的请求里约好的几项：谁在看、他有没有公告管理权限、最多要几条、搜索词。

### mugsun-cloud-admin（2 个）

监控中心：一个页面看网关和各服务的每个实例在不在线。

- `admin`
  - `AdminApplication`：监控中心启动入口：一个页面看到网关和各服务的每个实例在不在线，用的是 Spring Boot Admin。
- `admin.config`
  - `SecurityConfiguration`：监控中心的登录。

### mugsun-cloud-log（1 个）

日志服务：六类日志的查询和处理。接口类是同步来的，手写的只有启动入口。

- `log`
  - `LogApplication`：日志服务启动入口。

### mugsun-cloud-report（1 个）

报表服务：报表的定义和预览。接口类、实体和 Mapper 是同步来的，手写的只有启动入口。

- `report`
  - `ReportApplication`：报表服务启动入口。

### mugsun-cloud-develop（1 个）

代码生成服务：代码生成、数据建模和在线表单。业务类和代码模板是同步来的，手写的是启动入口和请 ai 服务出建模草稿的转发。

- `develop`
  - `DevelopApplication`：代码生成服务启动入口。

### mugsun-cloud-job（1 个）

定时任务服务：定时任务和调度中心的管理接口、平台自带的任务处理器、按保留期清理日志。业务类是同步来的，手写的只有启动入口。

- `job`
  - `JobApplication`：定时任务服务启动入口。

### mugsun-cloud-resource（1 个）

文件和短信服务：文件上传下载、存储配置、头像、富文本图片、反馈附件、短信配置和发送。业务类是同步来的，手写的有启动入口和接收短信内部调用的一端。

- `resource`
  - `ResourceApplication`：文件和短信服务启动入口。

### mugsun-cloud-flow（1 个）

审批流服务：流程定义和设计、发起、待办、办理、流转记录，手机端的待办。业务类是同步来的，手写的有启动入口、接收取待办内部调用的一端、拦 AI 预审节点的两个类。

- `flow`
  - `FlowApplication`：审批流服务启动入口。

### mugsun-cloud-resource-api（2 个）

文件和短信服务给别的服务调用的那一层：把发短信、验短信验证码转给文件和短信服务的类，和两端约好的字段名。

- `api.resource`
  - `ResourceApiAutoConfiguration`：依赖了这个模块的服务自动得到的 Bean：服务自己没有短信的实现时，用转给文件和短信服务的那一个。
  - `SmsApi`：短信内部接口里两端约好的名字：请求和响应的字段、验证码接口的操作名。

### mugsun-cloud-flow-api（2 个）

审批流服务给别的服务调用的那一层：把取待办转给审批流服务的类，和两端约好的字段名。

- `api.flow`
  - `FlowApi`：审批流内部接口里两端约好的名字：请求和响应的字段。
  - `FlowApiAutoConfiguration`：依赖了这个模块的服务自动得到的 Bean：服务自己没有取待办的实现时，用转给审批流服务的那一个。

### mugsun-cloud-desk-api（3 个）

工作台服务给别的服务调用的那一层：把取首页要的公告、按标题找公告转给工作台服务的类，和两端约好的字段名。

- `api.desk`
  - `DeskApi`：工作台内部接口里两端约好的东西：请求和响应的字段名，和数字怎么读。
  - `DeskApiAutoConfiguration`：依赖了这个模块的服务自动得到的 Bean：服务自己没有取公告的实现时，用转给工作台服务的那两个。
  - `DeskCaller`：对工作台服务发内部调用时，两个转发类共用的部分：认服务名、在请求里写上是谁在看、把没调成变成给用户看的话。
