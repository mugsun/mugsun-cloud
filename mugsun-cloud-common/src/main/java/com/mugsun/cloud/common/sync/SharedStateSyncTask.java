package com.mugsun.cloud.common.sync;

import com.mugsun.boot.config.ApiScopeRuleLoader;
import com.mugsun.boot.config.DataScopeRuleLoader;
import com.mugsun.boot.datasource.TenantDataSourceRegistry;
import com.mugsun.boot.datasource.mapper.SysTenantDatasourceMapper;
import com.mugsun.boot.tenant.TenantContext;
import com.mybatisflex.core.query.QueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 定时把「存在库里、每个进程又各自在内存里留了一份」的状态和库对齐。
 *
 * <p>mugsun-boot 里有三样东西是这样的：数据权限规则、接口权限规则和角色授权、租户独立数据源。单体版里管理员一保存，
 * 控制器就顺手刷新了本进程的那一份。拆成多个服务后，保存发生在 system 服务里，其它服务内存里的那一份没人刷新：
 * 收回的授权在别的服务里还作数，改用独立库的租户在别的服务里还往主库写。所以 system 以外的服务要定时自己刷新。
 *
 * <p>开关 {@code mugsun.cloud.sync.enabled}，默认开。system 服务在自己的配置里关掉：它保存时已经刷新过，
 * 再定时刷一遍，有可能拿保存前读到的旧数据盖掉刚生效的新数据。间隔 {@code mugsun.cloud.sync.interval-ms}，默认 30 秒，
 * 也就是 system 里改完以后，其它服务最多晚这么久生效。
 *
 * <p>不需要这样同步的：系统参数（JetCache 会经 Redis 通知各进程清缓存）、租户和套餐（60 秒过期）、登录态（在 Redis 里）。
 */
@Component
@ConditionalOnProperty(name = "mugsun.cloud.sync.enabled", havingValue = "true", matchIfMissing = true)
public class SharedStateSyncTask {

	private static final Logger log = LoggerFactory.getLogger(SharedStateSyncTask.class);

	private final DataScopeRuleLoader dataScopeRuleLoader;

	private final ApiScopeRuleLoader apiScopeRuleLoader;

	private final TenantDataSourceSync tenantDataSourceSync;

	/** 应用启动完成（启动时那一遍加载已经做完）之后才开始同步 */
	private volatile boolean ready;

	private boolean rulesFailing;

	private boolean dataSourcesFailing;

	public SharedStateSyncTask(DataScopeRuleLoader dataScopeRuleLoader, ApiScopeRuleLoader apiScopeRuleLoader,
							   TenantDataSourceRegistry registry, SysTenantDatasourceMapper datasourceMapper) {
		this.dataScopeRuleLoader = dataScopeRuleLoader;
		this.apiScopeRuleLoader = apiScopeRuleLoader;
		// 启用中的配置：和 TenantDataSourceRegistry.loadAll() 用同一个条件
		this.tenantDataSourceSync = new TenantDataSourceSync(
			() -> TenantContext.ignore(() -> datasourceMapper.selectListByQuery(QueryWrapper.create().eq("status", 1))),
			tenant -> !TenantDataSourceRegistry.DEFAULT_KEY.equals(registry.resolveDsKey(tenant)),
			registry::register,
			registry::unregister);
	}

	/**
	 * 启动完成的那一刻先对一遍租户数据源：记下启动时注册的是哪些配置，之后才比得出变化。
	 * 这个事件在所有 ApplicationReadyEvent 监听器（包括启动时加载数据源的那个）执行完之后才发出。
	 */
	@EventListener
	public void onReadinessChanged(AvailabilityChangeEvent<ReadinessState> event) {
		if (event.getState() == ReadinessState.ACCEPTING_TRAFFIC) {
			syncTenantDataSources();
			ready = true;
		} else {
			ready = false;
		}
	}

	@Scheduled(initialDelayString = "${mugsun.cloud.sync.interval-ms:30000}",
		fixedDelayString = "${mugsun.cloud.sync.interval-ms:30000}")
	public void sync() {
		if (!ready) {
			return;
		}
		reloadScopeRules();
		syncTenantDataSources();
	}

	private void reloadScopeRules() {
		try {
			dataScopeRuleLoader.reload();
			apiScopeRuleLoader.reload();
			if (rulesFailing) {
				rulesFailing = false;
				log.info("数据权限和接口权限规则已恢复定时刷新");
			}
		} catch (RuntimeException e) {
			if (!rulesFailing) {
				rulesFailing = true;
				log.error("刷新数据权限和接口权限规则失败，暂时沿用内存里现有的规则，下一轮会再试：{}", e.getMessage());
			}
		}
	}

	private synchronized void syncTenantDataSources() {
		try {
			tenantDataSourceSync.run();
			if (dataSourcesFailing) {
				dataSourcesFailing = false;
				log.info("租户独立数据源已恢复定时同步");
			}
		} catch (RuntimeException e) {
			if (!dataSourcesFailing) {
				dataSourcesFailing = true;
				log.error("同步租户独立数据源失败，暂时沿用现有的数据源，下一轮会再试：{}", e.getMessage());
			}
		}
	}
}
