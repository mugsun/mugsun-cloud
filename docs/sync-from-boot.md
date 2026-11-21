# 从 mugsun-boot 同步代码

业务逻辑只在 mugsun-boot 维护一份。mugsun-cloud 不改业务代码，只决定每个类放进哪个服务，再加上微服务才需要的部分，比如网关和注册发现。

同步由 `scripts/sync_from_boot.py` 完成，规则在 `scripts/sync-rules.json`。脚本只用 Python 标准库。

## 同步了什么

脚本从 mugsun-boot 的一个提交里读文件，原样写到各模块的 `src/boot/` 下，包名和内容都不改。

| 来源（mugsun-boot-core 的 `src/main`） | 去向 |
| --- | --- |
| 划进公共层的 Java 类 | `mugsun-cloud-common/src/boot/java` |
| 其余的 Java 类 | `mugsun-cloud-service/mugsun-cloud-system/src/boot/java` |
| `application.yml` | 公共层，改名为 `mugsun-boot-defaults.yml` |
| `application-*.yml`、`logback-spring.xml` | 公共层 |
| `META-INF/spring.factories` | 按里面登记的类分别属于哪边，拆成两份 |
| 其他资源文件，如数据库迁移脚本 | system |

可选模块整个对应一个服务，模块的 `src/main` 原样拷到那个服务的 `src/boot` 下：

| 来源 | 去向 |
| --- | --- |
| `mugsun-boot-gis` | `mugsun-cloud-service/mugsun-cloud-gis/src/boot` |
| `mugsun-boot-track` | `mugsun-cloud-service/mugsun-cloud-track/src/boot` |
| `mugsun-boot-ai` | `mugsun-cloud-service/mugsun-cloud-ai/src/boot` |

没有同步的部分：

- `MugsunApplication`。每个服务有自己的启动类。
- 测试代码。

`application.yml` 改名是因为每个服务有自己的 `application.yml`。服务的配置先引入 `mugsun-boot-defaults.yml`，再引入微服务版的 `mugsun-cloud-defaults.yml`，最后写自己的端口和服务名。

## 公共层怎么划分

公共层是各服务都要带的代码，比如登录态、租户、数据权限和数据源。规则写在 `sync-rules.json` 的 `core` 里，类名都相对 `com.mugsun.boot`。

| 规则 | 含义 |
| --- | --- |
| `commonPackages` | 整个包连同子包进公共层 |
| `commonClasses` | 单个类进公共层 |
| `commonControllers` | 允许进公共层的控制器。没列在这里的控制器一律留在 system |
| `systemClasses` | 强制留在 system 的类。类名里可以用 `*`，如 `config.*SchemaRunner` |
| `skip` | 不同步的类 |

划分分两步：

1. 上面指定的类先进公共层。
2. 公共层的类引用到的其他类也跟着进公共层，一直到没有新的类为止。引用关系按源码里的 `import` 和同包引用推断。

剩下的类都在 system。

只该执行一次的启动任务要写进 `systemClasses`，比如写初始数据的 `config.DataInitializer`。它们所在的 `config` 包整个在公共层，不单独列出来的话，每个服务启动时都会执行一遍。

如果第 2 步带到了控制器、`systemClasses` 或 `skip` 里的类，脚本会报错并列出是谁引用了谁。这时要改规则：把前者从公共层拿掉，或者允许后者进公共层。

两个查看划分结果的命令：

```bash
python3 scripts/sync_from_boot.py --explain ClientIpResolver   # 这个类分到了哪里，被谁带进来的
python3 scripts/sync_from_boot.py --report                     # 两边各用到哪些第三方包，维护 pom 时用
```

## 单独拷贝的类

网关不依赖公共层，但要用 mugsun-boot 里判断地址段的 `common.web.IpCidr`，这样网关和单体版按同一套规则算客户端地址。这类需求写在 `extras` 里：

```json
{ "module": "mugsun-boot-core", "classes": ["common.web.IpCidr"], "to": "mugsun-cloud-gateway" }
```

服务也可以这样单独带一个类。`system.file.FileStorageTikaConfig` 是文件存储组件的配置，留在 system。track 也带文件存储，要用同一份配置；但这个类进不了公共层，因为公共层和 gis 不带文件存储组件。所以给 track 单独拷一份：

```json
{ "module": "mugsun-boot-core", "classes": ["system.file.FileStorageTikaConfig"], "to": "mugsun-cloud-service/mugsun-cloud-track" }
```

可选模块里的类也能这样拷给 system。删租户在 system 里做，要顺带删掉这个租户的 AI 密钥，而密钥表的实体和 Mapper 在 AI 模块里。把它们拷给 system，system 的 `CloudAiSecretPurger` 就能直接删这张表，不用等 ai 服务在线：

```json
{
  "module": "mugsun-boot-ai",
  "classes": ["ai.entity.AiSecret", "ai.mapper.AiSecretMapper", "ai.support.AiDateTimeDeserializer"],
  "to": "mugsun-cloud-service/mugsun-cloud-system"
}
```

`AiDateTimeDeserializer` 是 `AiSecret` 引用到的类，要一起拷。这三个类同时也随整个模块在 ai 服务里，两边的内容相同。

列在 `extras` 里的类不能引用没有一起拷贝的类，否则脚本报错。

## 单独同步的文件

代码以外的文件也能从 mugsun-boot 同步，比如部署时用的脚本。写在 `files` 里：

```json
{ "to": "deploy/docker/boot", "paths": ["scripts/gen-keys.sh", "scripts/init-db.sql"] }
```

- `paths` 是 mugsun-boot 仓库里的文件路径。只能写单个文件，不能写目录。
- 文件原样复制到 `to` 写的目录下，文件名不变，可执行位和来源一致。
- `to` 写的目录整个归脚本管，每次同步都会重写，微服务版自己的文件不要放进去。目录里已经有文件、又没有同步标记 `README.md` 时，脚本报错，不动这个目录。
- 这些文件和 `src/boot` 一样记进 `boot-sync.lock.json`，`--check` 时一起比较。可执行位变了也算差异。

现在同步的是 `deploy/docker` 那套编排要用的六个文件：

| mugsun-boot 里的文件 | 用在哪 |
| --- | --- |
| `scripts/gen-keys.sh` | 首次启动时生成国密密钥、接口密钥、文件签名密钥和 admin 的初始口令 |
| `scripts/init-db.sql`、`docker/postgres/00-init-db.sh` | PostgreSQL 首次初始化时建应用账号、主库和埋点库，装 PostGIS 和 pgvector 扩展 |
| `docker/full/init-pw-check.py` | `init show` 判断 admin 改没改过口令 |
| `docker/full/smoke.py`、`docker/full/smoke-requirements.txt` | 自检时登录、过验证码、改口令、上报并查到第一条埋点 |

## mugsun-boot 有新提交时

```bash
python3 scripts/sync_from_boot.py                # 同步 ../mugsun-boot 的 HEAD
python3 scripts/sync_from_boot.py --ref <提交>   # 或者指定提交、分支、标签
./mvnw -B verify
python3 scripts/compare_libs.py                  # 第三方库的版本和单体版是否一致，见下面「第三方库的版本」
```

- 脚本读的是提交，不是工作区。mugsun-boot 里没提交的改动不会被同步。
- 每次同步会整个重写 `src/boot/` 和 `files` 里写的目录，并更新 `boot-sync.lock.json`。这个文件记着来源提交、内容摘要和各目录的文件数。
- 一次同步单独做一个提交，提交说明里写 mugsun-boot 的提交号。
- mugsun-boot 不在 `../mugsun-boot` 时，用 `--boot <路径>` 指定。

同步后编译不过，常见的原因有两个：

| 现象 | 处理 |
| --- | --- |
| 找不到某个第三方库的类 | mugsun-boot 加了依赖。对照它对应模块的 pom，加到公共层或对应服务的 pom 里 |
| 脚本报「公共层的类引用了只能留在 system 的类」 | 按报错里的提示改 `sync-rules.json` |

## 要手工跟着改的地方

下面几处脚本管不到。mugsun-boot 改了左边，这边要手工改右边。

| mugsun-boot 里的 | mugsun-cloud 里对应的 |
| --- | --- |
| `MugsunApplication` 上的注解 | 各服务的启动类，如 `SystemApplication` |
| `CorsConfig` 的跨域规则 | 网关 `application.yml` 里的 `globalcors` |
| 父 pom 和 `mugsun-boot-core/pom.xml` 的依赖、版本号 | 父 pom、`mugsun-cloud-common/pom.xml`、system 的 pom |
| `mugsun-boot-gis/pom.xml` 的依赖 | `mugsun-cloud-gis` 的 pom |
| `mugsun-boot-track/pom.xml` 的依赖 | `mugsun-cloud-track` 的 pom |
| `mugsun-boot-ai/pom.xml` 的依赖 | `mugsun-cloud-ai` 的 pom |
| sms4j 间接带入的 `hutool-http` 的版本，mugsun-bom 升级 sms4j 时会变 | 父 pom 的 `hutool-http.version` |
| `mugsun-boot-ai/pom.xml` 里 tika 的版本 | 父 pom 的 `tika.version` |
| 单体版包里 `commons-io`、`commons-collections4` 的版本，tika 的版本变了它们会跟着变 | 父 pom 的 `commons-io.version`、`commons-collections4.version` |
| `OptionalModuleConfiguration` 里 GIS 模块状态的判断规则 | system 的 `CloudGisModuleStatus` |
| 埋点模块里 `TrackModuleStatus` 的实现和 `mugsun.track.enabled` 的规则 | system 的 `CloudTrackModuleStatus` |
| AI 模块里 `AiModuleStatus` 的实现和 `mugsun.ai.enabled` 的规则 | system 的 `CloudAiModuleStatus` |
| 可选模块里实现 `TenantCredentialPurger` 的类，现在只有 AI 模块的 `AiSecretTenantPurger` | system 里对应的清理，现在是 `CloudAiSecretPurger`。模块增减了实现时，那个服务的 `TenantPurgerGuardTest` 会失败 |
| 模块之间互相调用的接口 `ModuleInsightPort`、`TrackAskLlmGateway`、`GenAiDraftEnhancer`，和它们在单体版里的实现 | 调用方的 `RemoteInsightPort`、`RemoteTrackAskLlmGateway`、`RemoteGenAiDraftEnhancer`，和被调用方的 `LocalInsightEndpoint`、`TrackAskIntentEndpoint`、`GenDraftEndpoint` |
| 流程的全局监听器 `MugsunFlowListener`，AI 预审节点监听器的类名 `FlowConstants.AI_APPROVAL_LISTENER` | system 的 `AiApprovalNodeGate` 和 `AiApprovalNodeGuard` |
| Spring Boot 版本 | 父 pom 的 `spring-boot-starter-parent`，同时确认 Spring Cloud 的版本配套 |
| `docker/full/boot.Dockerfile` 里钉的 mugsun-core 提交号 `MUGSUN_CORE_REF` 和基础镜像的摘要 | `deploy/docker/Dockerfile` 里同名的参数和基础镜像 |
| `docker-compose.full.yml` 里钉的 mugsun-pc 提交号，PostgreSQL 和 Redis 的镜像 | `deploy/docker/docker-compose.yml` 里 `pc` 的构建来源和对应的镜像 |
| `docker/full/init.Dockerfile` 的基础镜像和软件包版本 | `deploy/docker/init.Dockerfile` |
| `docker/full/gen-secrets.sh` 生成哪些口令和密钥、`init show` 怎么判断 | `deploy/docker/gen-secrets.sh` |
| `scripts/compose-full-smoke.sh` 检查的部署约定 | `deploy/docker/smoke.sh` |
| 加了、删了不用登录的接口，或者改了免登录名单 `LoginPublicPaths` | `deploy/docker/public-endpoints.txt`。自检的最后一步会列出多了、少了哪几个；多出来的先确认该对没登录的人开放，再加进去 |

## 第三方库的版本

业务代码和单体版是同一份，它用到的第三方库也要和单体版一样：两边的包里都有的库，版本要相同。业务测试是在单体版那套版本上跑的，版本一样，测试结果在这边才作数。

版本在三种情况下会和单体版不一样：

| 情况 | 例子 | 处理 |
| --- | --- | --- |
| 单体版里的版本是另一个模块带来的，拆开后这个服务里没有那个模块 | AI 模块把 `tika-core` 定在 3.2.2，文件存储组件自己带的是 2.4.1 | 在父 pom 的 `dependencyManagement` 里定成单体版包里的版本 |
| 引入的版本清单（BOM）改了业务代码也在用的库 | `spring-cloud-alibaba-dependencies` 会改 `guava` 和 `mybatis-spring-boot-starter` | 不引入这份清单，用到它的哪个组件就在父 pom 里单独写那个组件的版本 |
| 库是另一个库间接带进来的，拆开后带它的那个库不在这个服务里 | track 用到的 `hutool-http` 在单体版里由短信组件带入 | 在服务的 pom 里显式引入，版本取单体版包里的 |

用 `scripts/compare_libs.py` 检查。它比较网关、各服务的包和单体版的包，列出版本不同的库：

```bash
./mvnw -B package
python3 scripts/compare_libs.py                    # 单体版的包取 ../mugsun-boot/mugsun-boot-server/target/mugsun-boot.jar
python3 scripts/compare_libs.py --boot-jar <路径>   # 单体版的包在别处时
```

- 单体版的包要用 `boot-sync.lock.json` 里记的那个提交打。
- 输出「一致」说明版本都相同。有不同时逐个列出，退出码为 1。
- 「只在这个包里」列的是单体版没有的库，应当只有 Nacos、Spring Cloud 这些微服务组件和它们带进来的库。gis 和 track 的包里还有 `mybatis-flex-processor`，它是编译期的注解处理器，运行时不起作用。
- `annotations`、`error_prone_annotations` 这两个库里只有注解，版本不同不影响运行，脚本只提示，不算差异。

这几种时候要跑一遍：同步 mugsun-boot 之后，升级 Spring Boot、Spring Cloud 或 mugsun-bom 之后，改了任何一个 pom 之后，拆出新服务之后。

## 检查 `src/boot` 有没有被手改

```bash
python3 scripts/sync_from_boot.py --locked --check
```

`--locked` 表示和 `boot-sync.lock.json` 里记的那个提交比较。输出「一致」说明没人动过，有差异时列出文件，退出码为 1。单独同步的文件也一起比较。

不带 `--locked` 时比较的是 mugsun-boot 的 HEAD，用来看这边是不是落后了。

## 拆出新服务时

mugsun-boot 的可选模块整个对应一个服务。在 `modules` 里加一项，模块的 `src/main` 会被整个拷到目标模块的 `src/boot` 下：

```json
{ "module": "mugsun-boot-gis", "target": "mugsun-cloud-service/mugsun-cloud-gis" }
```

可选模块只能引用公共层的类，拆出去的服务里没有 system 的代码。脚本会检查这一点，引用了留在 system 的类就报错并列出来。这时有两种处理：

- 那个类各服务都能带：把它加进 `commonClasses`。track 要发站内信，`message.MessageService` 和 `websocket.RedisWsMessageSender` 就是这样进公共层的。
- 那个类只能在 system 里跑：改成调用 system 的内部接口，做法见 [AGENTS.md](../AGENTS.md) 的「服务之间的调用」。这要先改 mugsun-boot，把那次引用抽成一个接口。

另外还要做这几件事：

1. 给新服务写 pom、启动类和 `application.yml`，照 `mugsun-cloud-gis` 的写。
2. 在网关的路由里把它的路径写在 `mugsun-system` 前面。
3. 在 `deploy/dev/nacos.sh` 的 `SERVICES` 里补上服务名。
4. 在 system 里写一个模块状态类，照 `CloudGisModuleStatus` 的写。system 靠它知道这个服务有没有部署，决定菜单显不显示。
5. 逐个检查留在 system 的过滤器、拦截器和配置类。新服务带了它们保护或配置的功能，就让新服务也带上它们：各服务都能带的加进 `commonClasses`，只有个别服务能带的写进 `extras`。track 带文件存储，所以有两处：
   - `system.file.LocalFileAccessFilter` 进了公共层。不这样做，track 的端口上 `/file/**` 能匿名读到存储目录。
   - `system.file.FileStorageTikaConfig` 单独拷给了 track。它管文件类型怎么识别，不带的话 track 用的是组件的默认识别方式，和单体版不是同一份配置。
6. 新服务带了 PowerJob 依赖的话，在启动类上排除 `PowerJobAutoConfiguration`，照 `TrackApplication` 的写。执行器只在 system 上启动。
7. 打包后跑 `python3 scripts/compare_libs.py`，新服务包里第三方库的版本要和单体版一致。不一致时的处理见上面的「第三方库的版本」。
8. 找出模块之间靠接口互相调用的地方：单体版里一个模块提供实现，另一个模块或 core 注入使用。拆开后使用方的进程里没有那个实现，要在使用方写一个走内部接口的实现，在提供方写对应的 `InnerEndpoint`。AI 问图层和埋点「问数据」就是这样接上的。对方没部署、调不通时的表现，要和单体版没装那个模块时一样。
9. 找出模块替 system 做的事：模块里实现了 core 的扩展接口、由 system 的代码来调用的类，拆出去以后 system 里就没有了，而且不会报错。删租户时清 AI 密钥的 `TenantCredentialPurger` 是一例，要在 system 里补一个。
10. 找出按类名存在库里、运行时再按类名去找的类。审批流节点上记的监听器类名是一例：AI 预审节点的监听器随模块去了 ai 服务，system 里找不到它，流程引擎不报错，直接跳过。这类功能拆开后要么接上，要么明确拦下，不能让它悄悄失效。
11. 在 `deploy/docker` 里补上新服务，然后跑一遍 `deploy/docker/smoke.sh`：
    - `Dockerfile` 加一个构建目标。
    - `docker-compose.yml` 加一个服务，照 `gis` 的写，并写进 `ready` 的 `depends_on`。
    - `nacos-init.py` 的 `SERVICES` 补上服务名。
    - `smoke.sh` 的 `APP_SERVICES` 补上名字，`serving` 里加一条它的接口。
    - `smoke-sweep.py` 的 `SERVICES` 补上名字和端口。忘了补，自检的最后一步会报两边的服务不一致。

## 为什么包名不改

同步过来的代码保留 `com.mugsun.boot` 这个包名。这样文件和 mugsun-boot 逐字节相同，能用脚本校验；MyBatis 的扫描路径、缓存注解的扫描范围和配置文件里写死的类名也都不用改。

微服务版自己写的代码用 `com.mugsun.cloud`，和同步来的代码一眼能分开。
