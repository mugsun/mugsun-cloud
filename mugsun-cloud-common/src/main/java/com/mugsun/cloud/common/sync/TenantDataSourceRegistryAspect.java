package com.mugsun.cloud.common.sync;

import com.mugsun.boot.datasource.entity.SysTenantDatasource;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 本进程里有人直接注册或注销租户数据源时（system 的控制器在保存、删除以后会这样做），让这次操作和定时比较互斥，
 * 并告诉 {@link TenantDataSourceSync} 连接池现在是按哪份配置建的。
 *
 * <p>不这样做的话，system 打开定时同步以后有两个问题：控制器刚重建的连接池，下一轮会被当成「配置变了」再重建一次；
 * 两个实例先后保存同一个租户的数据源时，先保存的实例分不清自己的连接池是按哪一次的配置建的。
 *
 * <p>{@code TenantDataSourceRegistry} 是从 mugsun-boot 同步来的，不能改，所以用切面。它在自己内部调用 register
 * （启动时加载全部数据源）不经过这里，那一遍由启动完成时的第一轮比较记下。方法改了名、切点对不上时，这个切面不起作用，
 * 后果只是连接池多重建一次，不会用错配置（单测里有一条专门看切点还对不对得上）。
 */
@Aspect
@Component
@ConditionalOnProperty(name = "mugsun.cloud.sync.enabled", havingValue = "true", matchIfMissing = true)
public class TenantDataSourceRegistryAspect {

	private final TenantDataSourceSync sync;

	@Autowired
	public TenantDataSourceRegistryAspect(SharedStateSyncTask task) {
		this(task.tenantDataSourceSync());
	}

	TenantDataSourceRegistryAspect(TenantDataSourceSync sync) {
		this.sync = sync;
	}

	@Around("execution(* com.mugsun.boot.datasource.TenantDataSourceRegistry.register(..)) && args(config)")
	public Object aroundRegister(ProceedingJoinPoint call, SysTenantDatasource config) throws Throwable {
		return sync.registerExclusively(config, call::proceed);
	}

	@Around("execution(* com.mugsun.boot.datasource.TenantDataSourceRegistry.unregister(..)) && args(tenantCode)")
	public Object aroundUnregister(ProceedingJoinPoint call, String tenantCode) throws Throwable {
		return sync.unregisterExclusively(tenantCode, call::proceed);
	}
}
