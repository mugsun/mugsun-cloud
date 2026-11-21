package com.mugsun.cloud.common.sync;

import com.mugsun.boot.config.ApiScopeRuleLoader;
import com.mugsun.boot.config.DataScopeRuleLoader;
import com.mugsun.boot.datasource.TenantDataSourceRegistry;
import com.mugsun.boot.datasource.entity.SysTenantDatasource;
import com.mugsun.boot.datasource.mapper.SysTenantDatasourceMapper;
import com.mugsun.boot.system.service.ApiScopeService;
import com.mugsun.boot.system.service.DataScopeService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import javax.sql.DataSource;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 给 mugsun-boot 真的规则加载器和数据源注册表套上切面，看改动有没有通知出去、是在什么时候通知的。
 * boot 给这几个方法改名或换参数以后，这里会先失败。
 *
 * <p>加载器背后的服务是假的，返回空列表；注册表拿到的不是动态数据源，它的 register 什么都不做。
 */
class SharedStateChangeAspectTest {

	private final SharedStateVersion version = mock(SharedStateVersion.class);

	private final DataScopeRuleLoader dataScopeRules = advised(new DataScopeRuleLoader(mock(DataScopeService.class)));

	private final ApiScopeRuleLoader apiScopeRules = advised(new ApiScopeRuleLoader(mock(ApiScopeService.class)));

	private final TenantDataSourceRegistry registry = advised(
		new TenantDataSourceRegistry(mock(DataSource.class), mock(SysTenantDatasourceMapper.class)));

	private <T> T advised(T target) {
		AspectJProxyFactory factory = new AspectJProxyFactory(target);
		factory.setProxyTargetClass(true);
		factory.addAspect(new SharedStateChangeAspect(version));
		return factory.getProxy();
	}

	@AfterEach
	void clearThreadState() {
		RequestContextHolder.resetRequestAttributes();
		if (TransactionSynchronizationManager.isSynchronizationActive()) {
			TransactionSynchronizationManager.clearSynchronization();
		}
	}

	@Test
	void everyWatchedMethodNotifies() {
		dataScopeRules.reload();
		verify(version, times(1)).bump();

		apiScopeRules.reload();
		verify(version, times(2)).bump();

		registry.register(config("t1"));
		verify(version, times(3)).bump();

		registry.unregister("t1");
		verify(version, times(4)).bump();
	}

	@Test
	void otherCallsDoNotNotify() {
		registry.resolveDsKey("t1");
		// 启动时那一遍：加载器自己调用自己的 reload，不经过切面
		dataScopeRules.run();
		apiScopeRules.run();

		verify(version, never()).bump();
	}

	@Test
	void insideARequestNotifiesOnceWhenTheRequestCompletes() {
		ServletRequestAttributes request = new ServletRequestAttributes(new MockHttpServletRequest());
		RequestContextHolder.setRequestAttributes(request);

		// 先注销、再删库里的配置：删除还没做，这时候通知出去，别的实例读到的还是删除前的数据
		registry.unregister("t1");
		dataScopeRules.reload();
		verify(version, never()).bump();

		request.requestCompleted();
		verify(version, times(1)).bump();
	}

	@Test
	void aCallAfterItsRequestIsOverNotifiesAtOnce() {
		ServletRequestAttributes request = new ServletRequestAttributes(new MockHttpServletRequest());
		RequestContextHolder.setRequestAttributes(request);
		request.requestCompleted();

		dataScopeRules.reload();

		verify(version, times(1)).bump();
	}

	@Test
	void insideATransactionNotifiesWhenItCompletes() {
		TransactionSynchronizationManager.initSynchronization();

		apiScopeRules.reload();
		verify(version, never()).bump();

		// 回滚了也通知：本实例可能已经把没提交的数据刷进了内存，要靠这次通知改回来
		for (TransactionSynchronization each : TransactionSynchronizationManager.getSynchronizations()) {
			each.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
		}
		verify(version, times(1)).bump();
	}

	@Test
	void notifiesEvenWhenTheCallFails() {
		DataScopeService broken = mock(DataScopeService.class);
		when(broken.listEnabled()).thenThrow(new IllegalStateException("库连不上"));
		DataScopeRuleLoader loader = advised(new DataScopeRuleLoader(broken));

		assertThatThrownBy(loader::reload).isInstanceOf(IllegalStateException.class);

		// 改动已经在库里了，只是本实例没刷新成：别的实例照样要知道
		verify(version, times(1)).bump();
	}

	@Test
	void followingAnotherInstanceDoesNotNotify() {
		// 同步任务拿到的也是套了切面的加载器和注册表：它跟着别的实例重新加载，不能又通知一遍，否则各实例会没完没了地互相通知
		Set<String> live = new HashSet<>();
		TenantDataSourceSync dataSources = new TenantDataSourceSync(
			() -> live.isEmpty() ? List.of(config("t1")) : List.<SysTenantDatasource>of(),
			live::contains,
			config -> {
				registry.register(config);
				live.add(config.getTenantCode());
			},
			tenant -> {
				registry.unregister(tenant);
				live.remove(tenant);
			});
		SharedStateSyncTask task = new SharedStateSyncTask(dataScopeRules, apiScopeRules, dataSources,
			() -> "别的实例改过", 30_000, 1_000, () -> 0L);

		// 启动完成时注册 t1；第一次检查重新加载规则，并注销库里已经没有的 t1
		task.becomeReady();
		task.tick();

		verify(version, never()).bump();
	}

	private static SysTenantDatasource config(String tenant) {
		SysTenantDatasource config = new SysTenantDatasource();
		config.setTenantCode(tenant);
		config.setDsUrl("jdbc:postgresql://db1/app");
		config.setDsUsername("app");
		config.setStatus(1);
		return config;
	}
}
