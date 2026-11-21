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

没有同步的部分：

- `MugsunApplication`。每个服务有自己的启动类。
- 测试代码。
- `mugsun-boot-ai`。这个模块对应的服务还没拆。

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

列在 `extras` 里的类不能引用没有一起拷贝的类，否则脚本报错。

## mugsun-boot 有新提交时

```bash
python3 scripts/sync_from_boot.py                # 同步 ../mugsun-boot 的 HEAD
python3 scripts/sync_from_boot.py --ref <提交>   # 或者指定提交、分支、标签
./mvnw -B verify
```

- 脚本读的是提交，不是工作区。mugsun-boot 里没提交的改动不会被同步。
- 每次同步会整个重写 `src/boot/`，并更新 `boot-sync.lock.json`。这个文件记着来源提交、内容摘要和各模块的文件数。
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
| sms4j 间接带入的 `hutool-http` 的版本，mugsun-bom 升级 sms4j 时会变 | 父 pom 的 `hutool-http.version` |
| `OptionalModuleConfiguration` 里 GIS 模块状态的判断规则 | system 的 `CloudGisModuleStatus` |
| 埋点模块里 `TrackModuleStatus` 的实现和 `mugsun.track.enabled` 的规则 | system 的 `CloudTrackModuleStatus` |
| Spring Boot 版本 | 父 pom 的 `spring-boot-starter-parent`，同时确认 Spring Cloud 的版本配套 |

## 检查 `src/boot` 有没有被手改

```bash
python3 scripts/sync_from_boot.py --locked --check
```

`--locked` 表示和 `boot-sync.lock.json` 里记的那个提交比较。输出「一致」说明没人动过，有差异时列出文件，退出码为 1。

不带 `--locked` 时比较的是 mugsun-boot 的 HEAD，用来看这边是不是落后了。

## 拆出新服务时

mugsun-boot 的可选模块整个对应一个服务。在 `modules` 里加一项，模块的 `src/main` 会被整个拷到目标模块的 `src/boot` 下：

```json
{ "module": "mugsun-boot-gis", "target": "mugsun-cloud-service/mugsun-cloud-gis" }
```

可选模块只能引用公共层的类，拆出去的服务里没有 system 的代码。脚本会检查这一点，引用了留在 system 的类就报错并列出来。这时有两种处理：

- 那个类各服务都能带：把它加进 `commonClasses`。track 要发站内信，`message.MessageService` 和 `websocket.RedisWsMessageSender` 就是这样进公共层的。
- 那个类只能在 system 里跑：先设计服务间调用，再拆这个模块。

另外还要做这几件事：

1. 给新服务写 pom、启动类和 `application.yml`，照 `mugsun-cloud-gis` 的写。
2. 在网关的路由里把它的路径写在 `mugsun-system` 前面。
3. 在 `deploy/dev/nacos.sh` 的 `SERVICES` 里补上服务名。
4. 在 system 里写一个模块状态类，照 `CloudGisModuleStatus` 的写。system 靠它知道这个服务有没有部署，决定菜单显不显示。
5. 逐个检查留在 system 的过滤器和拦截器。新服务带了它们保护的功能，就把它们加进 `commonClasses`。track 带文件存储，所以 `system.file.LocalFileAccessFilter` 进了公共层：不这样做，track 的端口上 `/file/**` 能匿名读到存储目录。
6. 新服务带了 PowerJob 依赖的话，在启动类上排除 `PowerJobAutoConfiguration`，照 `TrackApplication` 的写。执行器只在 system 上启动。
7. 对照单体版打出的包，检查新服务包里第三方库的版本。单体版里由别的模块间接带进来的库，新服务要自己显式引入，版本取单体版包里的那个。

## 为什么包名不改

同步过来的代码保留 `com.mugsun.boot` 这个包名。这样文件和 mugsun-boot 逐字节相同，能用脚本校验；MyBatis 的扫描路径、缓存注解的扫描范围和配置文件里写死的类名也都不用改。

微服务版自己写的代码用 `com.mugsun.cloud`，和同步来的代码一眼能分开。
