package com.mugsun.cloud.common.sync;

import com.mugsun.boot.config.ApiScopeRuleLoader;
import com.mugsun.boot.config.DataScopeRuleLoader;
import com.mugsun.boot.datasource.TenantDataSourceRegistry;
import com.mugsun.boot.datasource.mapper.SysTenantDatasourceMapper;
import com.mugsun.boot.tenant.TenantContext;
import com.mybatisflex.core.query.QueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * 让本实例内存里的三样东西跟上库里的改动：数据权限规则、接口权限规则和角色授权、租户独立数据源。
 *
 * <p>这三样以库里的为准。每条 SQL、每次接口调用都要查它们，所以 mugsun-boot 在每个进程的内存里各留了一份；数据源是连接池，
 * 本来就只能在进程里。单体版只有一个进程，管理员一保存，控制器顺手刷新内存里的那一份就够了。拆成多个服务、
 * 每个服务又可以起多个实例以后，保存只发生在处理那次请求的 system 实例里，别的实例内存里的那一份没人刷新：
 * 收回的授权在别处还作数，改用独立库的租户在别处还往主库写。所以每个服务的每个实例（包括 system）都跑这个任务：
 *
 * <ul>
 * <li>有人改了：改动所在的实例给 Redis 里的版本换一个新值（见 {@link SharedStateChangeAspect}）。每个实例隔一秒读一次版本，
 * 和上次不一样就从库里重新加载。发出改动的那个实例也一样：它的控制器刷新内存时如果正好和这里的一次加载撞上，
 * 被旧数据盖掉了，这一遍会改回来。</li>
 * <li>兜底：版本没变，离上次加载满 30 秒也再加载一次。Redis 不可用、通知没发出去时靠这一条。</li>
 * </ul>
 *
 * <p>为什么不把规则放进 Redis、每次去 Redis 查：连接池放不进 Redis，租户数据源无论如何要有「通知各实例重建」这一套，
 * 规则用同一套就够了，查询还是只读内存；改成每次查 Redis 要改 mugsun-boot 里鉴权的代码，换来的只是把一两秒变成立即。
 *
 * <p>检查在自己的线程里做，没有用 Spring 的定时任务：那边的线程是和清理日志、重发通知、埋点汇总这些任务共用的，
 * 它们跑得久的时候，权限的生效不应该跟着等。
 *
 * <p>开关 {@code mugsun.cloud.sync.enabled}，默认开。多久读一次版本：{@code mugsun.cloud.sync.check-interval-ms}，默认 1 秒。
 * 兜底间隔：{@code mugsun.cloud.sync.interval-ms}，默认 30 秒。把这个包的日志级别调到 DEBUG，能看到每次通知和每次重新加载。
 *
 * <p>不归这里管的：系统参数（JetCache 会经 Redis 通知各进程清缓存）、租户和套餐（各实例缓存 60 秒）、登录态（在 Redis 里）。
 * 字典只缓存在各实例自己的内存里，靠过期跟上，system 在自己的 application.yml 里把过期时间设成了 30 秒。
 */
@Component
@ConditionalOnProperty(name = "mugsun.cloud.sync.enabled", havingValue = "true", matchIfMissing = true)
public class SharedStateSyncTask implements DisposableBean {

	private static final Logger log = LoggerFactory.getLogger(SharedStateSyncTask.class);

	private static final long MIN_CHECK_INTERVAL_MS = 100;

	/** 当前线程正在这里重新加载时有值 */
	private static final ThreadLocal<Boolean> SYNCING = new ThreadLocal<>();

	private final DataScopeRuleLoader dataScopeRuleLoader;

	private final ApiScopeRuleLoader apiScopeRuleLoader;

	private final TenantDataSourceSync tenantDataSourceSync;

	/** 读 Redis 里的版本，读不到时抛异常 */
	private final Supplier<String> version;

	private final long intervalNanos;

	private final long checkIntervalMs;

	private final LongSupplier nanoClock;

	private final Object tickerLock = new Object();

	private ScheduledExecutorService ticker;

	/** 应用启动完成（启动时那一遍加载已经做完）之后才开始 */
	private volatile boolean ready;

	/** 应用在停机。这时被打断的加载不算故障，不记日志 */
	private volatile boolean stopped;

	/** 上一次加载成功时，加载之前读到的版本。null 表示还没有过 */
	private String seenVersion;

	private long lastSyncAt;

	private boolean versionFailing;

	private boolean rulesFailing;

	private boolean dataSourcesFailing;

	private boolean tickFailing;

	@Autowired
	public SharedStateSyncTask(DataScopeRuleLoader dataScopeRuleLoader, ApiScopeRuleLoader apiScopeRuleLoader,
							   TenantDataSourceRegistry registry, SysTenantDatasourceMapper datasourceMapper,
							   SharedStateVersion version,
							   @Value("${mugsun.cloud.sync.interval-ms:30000}") long intervalMs,
							   @Value("${mugsun.cloud.sync.check-interval-ms:1000}") long checkIntervalMs) {
		this(dataScopeRuleLoader, apiScopeRuleLoader,
			// 启用中的配置：和 TenantDataSourceRegistry.loadAll() 用同一个条件
			new TenantDataSourceSync(
				() -> TenantContext.ignore(() -> datasourceMapper.selectListByQuery(QueryWrapper.create().eq("status", 1))),
				tenant -> !TenantDataSourceRegistry.DEFAULT_KEY.equals(registry.resolveDsKey(tenant)),
				registry::register,
				registry::unregister),
			version::current, intervalMs, checkIntervalMs, System::nanoTime);
	}

	SharedStateSyncTask(DataScopeRuleLoader dataScopeRuleLoader, ApiScopeRuleLoader apiScopeRuleLoader,
						TenantDataSourceSync tenantDataSourceSync, Supplier<String> version,
						long intervalMs, long checkIntervalMs, LongSupplier nanoClock) {
		this.dataScopeRuleLoader = dataScopeRuleLoader;
		this.apiScopeRuleLoader = apiScopeRuleLoader;
		this.tenantDataSourceSync = tenantDataSourceSync;
		this.version = version;
		this.intervalNanos = TimeUnit.MILLISECONDS.toNanos(intervalMs);
		this.checkIntervalMs = Math.max(MIN_CHECK_INTERVAL_MS, checkIntervalMs);
		this.nanoClock = nanoClock;
	}

	/** 给 {@link TenantDataSourceRegistryAspect} 用：控制器注册、注销数据源时要和这里的比较互斥。 */
	TenantDataSourceSync tenantDataSourceSync() {
		return tenantDataSourceSync;
	}

	/**
	 * 当前线程是不是正在这里重新加载。{@link SharedStateChangeAspect} 靠它区分「本实例跟着别人重新加载」和「本实例上有人改了东西」，
	 * 前一种不能再通知出去。
	 */
	static boolean isSyncing() {
		return SYNCING.get() != null;
	}

	/** 这个事件在所有 ApplicationReadyEvent 监听器（包括启动时加载数据源的那个）执行完之后才发出。 */
	@EventListener
	public void onReadinessChanged(AvailabilityChangeEvent<ReadinessState> event) {
		if (event.getState() != ReadinessState.ACCEPTING_TRAFFIC) {
			ready = false;
			return;
		}
		becomeReady();
		startTicker();
	}

	/** 启动完成的那一刻先对一遍租户数据源：记下启动时注册的是哪些配置，之后才比得出变化。 */
	synchronized void becomeReady() {
		whileSyncing(this::syncTenantDataSources);
		lastSyncAt = nanoClock.getAsLong();
		ready = true;
	}

	private void startTicker() {
		synchronized (tickerLock) {
			if (ticker != null) {
				return;
			}
			ticker = Executors.newSingleThreadScheduledExecutor(task -> {
				Thread thread = new Thread(task, "shared-state-sync");
				thread.setDaemon(true);
				return thread;
			});
			ticker.scheduleWithFixedDelay(this::tickQuietly, checkIntervalMs, checkIntervalMs, TimeUnit.MILLISECONDS);
		}
	}

	@Override
	public void destroy() {
		ready = false;
		stopped = true;
		synchronized (tickerLock) {
			if (ticker != null) {
				ticker.shutdownNow();
				ticker = null;
			}
		}
	}

	private void tickQuietly() {
		try {
			tick();
			tickFailing = false;
		} catch (Throwable e) {
			// 不拦住的话，线程池会把这个定时任务悄悄停掉
			if (!stopped && !tickFailing) {
				tickFailing = true;
				log.error("检查共享状态时出错，下一次接着查", e);
			}
		}
	}

	/** 每个检查间隔执行一次：版本变了，或者离上次加载满了一个兜底间隔，就重新加载。 */
	synchronized void tick() {
		if (!ready) {
			return;
		}
		String current = readVersion();
		boolean changed = current != null && !current.equals(seenVersion);
		long startedAt = nanoClock.getAsLong();
		if (!changed && startedAt - lastSyncAt < intervalNanos) {
			return;
		}
		boolean loaded = whileSyncing(() -> {
			boolean rules = reloadScopeRules();
			boolean dataSources = syncTenantDataSources();
			return rules && dataSources;
		});
		lastSyncAt = nanoClock.getAsLong();
		// 记下的是加载之前读到的版本：加载期间又有人改了的话，下一次还会发现不一样。没加载成功就不记，下一次接着试
		if (loaded && current != null) {
			seenVersion = current;
		}
		if (log.isDebugEnabled()) {
			log.debug("{}，重新加载{}，用时 {} 毫秒", changed ? "共享状态的版本变了" : "到了兜底间隔",
				loaded ? "完成" : "没有全部成功", TimeUnit.NANOSECONDS.toMillis(lastSyncAt - startedAt));
		}
	}

	/** 读不到时返回 null。 */
	private String readVersion() {
		try {
			String current = version.get();
			if (versionFailing) {
				versionFailing = false;
				log.info("又读得到 Redis 里的共享状态版本了");
			}
			return current;
		} catch (RuntimeException e) {
			if (!stopped && !versionFailing) {
				versionFailing = true;
				log.warn("读不到 Redis 里的共享状态版本。恢复之前，别的实例上的改动要等定时的重新加载才到本实例，最长 {} 秒：{}",
					TimeUnit.NANOSECONDS.toSeconds(intervalNanos), e.getMessage());
			}
			return null;
		}
	}

	private static boolean whileSyncing(BooleanSupplier work) {
		SYNCING.set(Boolean.TRUE);
		try {
			return work.getAsBoolean();
		} finally {
			SYNCING.remove();
		}
	}

	/** 两类规则分开加载：一类失败了，另一类照常更新。 */
	private boolean reloadScopeRules() {
		RuntimeException failure = null;
		try {
			dataScopeRuleLoader.reload();
		} catch (RuntimeException e) {
			failure = e;
		}
		try {
			apiScopeRuleLoader.reload();
		} catch (RuntimeException e) {
			failure = failure == null ? e : failure;
		}
		if (failure == null) {
			if (rulesFailing) {
				rulesFailing = false;
				log.info("数据权限和接口权限规则已恢复重新加载");
			}
			return true;
		}
		if (!stopped && !rulesFailing) {
			rulesFailing = true;
			log.error("重新加载数据权限和接口权限规则失败，没加载成的暂时沿用内存里现有的规则，下一轮会再试：{}", failure.getMessage());
		}
		return false;
	}

	private boolean syncTenantDataSources() {
		try {
			tenantDataSourceSync.run();
			if (dataSourcesFailing) {
				dataSourcesFailing = false;
				log.info("租户独立数据源已恢复同步");
			}
			return true;
		} catch (RuntimeException e) {
			if (!stopped && !dataSourcesFailing) {
				dataSourcesFailing = true;
				log.error("同步租户独立数据源失败，暂时沿用现有的数据源，下一轮会再试：{}", e.getMessage());
			}
			return false;
		}
	}
}
