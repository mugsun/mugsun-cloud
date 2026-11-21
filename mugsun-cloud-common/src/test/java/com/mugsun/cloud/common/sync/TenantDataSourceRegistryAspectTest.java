package com.mugsun.cloud.common.sync;

import com.mugsun.boot.datasource.TenantDataSourceRegistry;
import com.mugsun.boot.datasource.entity.SysTenantDatasource;
import com.mugsun.boot.datasource.mapper.SysTenantDatasourceMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 切点还对不对得上 mugsun-boot 的 {@code TenantDataSourceRegistry}：给真的注册表套上切面，调用它的 register 和 unregister，
 * 看同步那边有没有跟着记下来。boot 给这两个方法改名或换参数以后，这里会先失败。
 *
 * <p>注册表拿到的不是动态数据源，它的 register 什么都不做；「哪些租户的数据源在用」由这里的集合代替。
 */
class TenantDataSourceRegistryAspectTest {

	private final Set<String> live = new HashSet<>();

	private final List<String> registeredBySync = new ArrayList<>();

	private List<SysTenantDatasource> rows = new ArrayList<>();

	private final TenantDataSourceSync sync = new TenantDataSourceSync(
		() -> rows,
		live::contains,
		config -> registeredBySync.add(config.getTenantCode()),
		tenant -> {
		});

	private TenantDataSourceRegistry registry;

	@BeforeEach
	void wrapRegistry() {
		TenantDataSourceRegistry target = new TenantDataSourceRegistry(mock(DataSource.class), mock(SysTenantDatasourceMapper.class));
		AspectJProxyFactory factory = new AspectJProxyFactory(target);
		factory.setProxyTargetClass(true);
		factory.addAspect(new TenantDataSourceRegistryAspect(sync));
		registry = factory.getProxy();
		// 第一轮过后，同步不再直接采信已经在用的数据源
		sync.run();
	}

	@Test
	void registerOnTheRegistryIsRecordedBySync() {
		SysTenantDatasource saved = config("t1");
		live.add("t1");
		registry.register(saved);
		rows = List.of(config("t1"));

		// 没记下来的话，这一轮会把它当成来历不明的数据源再注册一次
		assertThat(sync.run()).isZero();
		assertThat(registeredBySync).isEmpty();
	}

	@Test
	void unregisterOnTheRegistryIsRecordedBySync() {
		live.add("t1");
		registry.register(config("t1"));
		rows = List.of(config("t1"));
		assertThat(sync.run()).isZero();

		// 注销以后记录要去掉：库里还启用着，又不知道在用的这个是按哪份配置建的，下一轮就该重新注册
		registry.unregister("t1");
		sync.run();
		assertThat(registeredBySync).containsExactly("t1");
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
