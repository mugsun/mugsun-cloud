package com.mugsun.cloud.common.sync;

import com.mugsun.boot.config.ApiScopeRuleLoader;
import com.mugsun.boot.config.DataScopeRuleLoader;
import com.mugsun.boot.datasource.entity.SysTenantDatasource;
import com.mugsun.boot.system.entity.SysTenant;
import com.mugsun.boot.system.mapper.SysTenantMapper;
import com.mugsun.boot.system.mapper.SysUserMapper;
import com.mugsun.boot.tenant.TenantValidator;
import com.mugsun.boot.tenant.entity.SysTenantPackage;
import com.mugsun.boot.tenant.mapper.SysTenantPackageMapper;
import com.mugsun.core.lock.client.LocalLockClient;
import com.mugsun.core.lock.template.LockTemplate;
import com.mybatisflex.core.query.QueryWrapper;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 给 mugsun-boot 真的租户校验器套上切面：别的实例上停用了租户、改了套餐以后，本实例清一遍缓存就跟上。
 * boot 给这几个方法改名、换参数，或者加了新的方法以后，这里会先失败。
 *
 * <p>库是假的。测试里换掉「库里」的那一行，相当于别的实例改了它。
 */
class TenantCacheSyncTest {

	private static final long PACKAGE_ID = 7L;

	private final SysTenantMapper tenantMapper = mock(SysTenantMapper.class);

	private final SysTenantPackageMapper packageMapper = mock(SysTenantPackageMapper.class);

	private final SharedStateVersion version = mock(SharedStateVersion.class);

	private final DefaultListableBeanFactory beans = new DefaultListableBeanFactory();

	private final TenantCacheSync sync = new TenantCacheSync(beans.getBeanProvider(TenantValidator.class));

	private final TenantValidator validator = advised(new TenantValidator(tenantMapper, packageMapper,
		mock(SysUserMapper.class), new LockTemplate(new LocalLockClient())));

	TenantCacheSyncTest() {
		beans.registerSingleton("tenantValidator", validator);
	}

	/** 和运行时一样：同一个校验器上套着「记下缓存过谁」和「改动通知出去」两个切面 */
	private TenantValidator advised(TenantValidator target) {
		AspectJProxyFactory factory = new AspectJProxyFactory(target);
		factory.setProxyTargetClass(true);
		factory.addAspect(sync);
		factory.addAspect(new SharedStateChangeAspect(version));
		return factory.getProxy();
	}

	private void inDatabase(SysTenant tenant) {
		when(tenantMapper.selectOneByQuery(any(QueryWrapper.class))).thenReturn(tenant);
	}

	private void inDatabase(SysTenantPackage tenantPackage) {
		when(packageMapper.selectOneById(PACKAGE_ID)).thenReturn(tenantPackage);
	}

	private static SysTenant tenant(String code, int status) {
		SysTenant tenant = new SysTenant();
		tenant.setTenantCode(code);
		tenant.setStatus(status);
		tenant.setPackageId(PACKAGE_ID);
		return tenant;
	}

	private static SysTenantPackage tenantPackage(String menuKeys) {
		SysTenantPackage tenantPackage = new SysTenantPackage();
		tenantPackage.setMenuKeys(menuKeys);
		return tenantPackage;
	}

	@Test
	void aTenantDisabledElsewhereIsRefusedOnceTheCacheIsCleared() {
		inDatabase(tenant("t1", 1));
		assertThat(validator.validate("t1")).as("启用中的租户").isNull();

		// 别的实例上停用了它。本实例缓存着停用之前查到的那一行，还在放行
		inDatabase(tenant("t1", 0));
		assertThat(validator.validate("t1")).as("缓存还没清").isNull();

		sync.evictAll();

		assertThat(validator.validate("t1")).as("清掉以后读到停用").isNotNull();
	}

	@Test
	void aTenantLoadedForARequestIsRememberedToo() {
		// 每个请求进来时调用的是 loadTenant，不是 validate
		inDatabase(tenant("t1", 1));
		assertThat(validator.loadTenant("t1").getStatus()).isEqualTo(1);

		inDatabase(tenant("t1", 0));
		assertThat(validator.loadTenant("t1").getStatus()).as("缓存还没清").isEqualTo(1);

		sync.evictAll();

		assertThat(validator.loadTenant("t1").getStatus()).isZero();
	}

	@Test
	void aTenantDeletedElsewhereIsGoneOnceTheCacheIsCleared() {
		inDatabase(tenant("t1", 1));
		assertThat(validator.validate("t1")).isNull();

		inDatabase((SysTenant) null);
		sync.evictAll();

		assertThat(validator.loadTenant("t1")).isNull();
		assertThat(validator.validate("t1")).isNotNull();
	}

	@Test
	void aPackageChangedElsewhereAppliesOnceTheCacheIsCleared() {
		inDatabase(tenantPackage("a,b"));
		assertThat(validator.loadPackageKeys(PACKAGE_ID)).containsExactlyInAnyOrder("a", "b");

		inDatabase(tenantPackage("a"));
		assertThat(validator.loadPackageKeys(PACKAGE_ID)).as("缓存还没清").containsExactlyInAnyOrder("a", "b");

		sync.evictAll();

		assertThat(validator.loadPackageKeys(PACKAGE_ID)).containsExactly("a");
	}

	@Test
	void tenantsThatDoNotExistAreNotRemembered() {
		// 登录页上的租户编号是用户填的：查不到的校验器不缓存，这里也不能记，不然乱填能把内存撑大
		for (int i = 0; i < 1000; i++) {
			assertThat(validator.validate("没有这个租户" + i)).isNotNull();
			assertThat(validator.loadTenant("也没有这个" + i)).isNull();
		}
		assertThat(validator.validate(null)).isNull();

		assertThat(sync.tenantCount()).isZero();
	}

	@Test
	void remembersEachTenantAndPackageOnce() {
		inDatabase(tenant("t1", 1));
		inDatabase(tenantPackage("a"));

		for (int i = 0; i < 3; i++) {
			validator.validate("t1");
			validator.loadTenant("t1");
			validator.loadPackageKeys(PACKAGE_ID);
		}

		assertThat(sync.tenantCount()).isEqualTo(1);
		assertThat(sync.packageCount()).isEqualTo(1);
	}

	@Test
	void savingOnThisInstanceNotifiesTheOthers() {
		// 控制器保存租户、套餐以后清本实例的缓存：就是该告诉别的实例的时候
		validator.evict("t1");
		verify(version, times(1)).bump();

		validator.evictPackage(PACKAGE_ID);
		verify(version, times(2)).bump();
	}

	@Test
	void readingDoesNotNotify() {
		inDatabase(tenant("t1", 1));
		inDatabase(tenantPackage("a"));

		validator.validate("t1");
		validator.loadTenant("t1");
		validator.loadPackageKeys(PACKAGE_ID);
		validator.activeTenantExists("t1");

		verify(version, never()).bump();
	}

	@Test
	void clearingAlongWithAnotherInstanceDoesNotNotifyAgain() {
		inDatabase(tenant("t1", 1));
		inDatabase(tenantPackage("a"));
		validator.validate("t1");
		validator.loadPackageKeys(PACKAGE_ID);
		TenantDataSourceSync noDataSources = new TenantDataSourceSync(() -> List.<SysTenantDatasource>of(), tenant -> false,
			config -> {
			}, tenant -> {
			});
		SharedStateSyncTask task = new SharedStateSyncTask(mock(DataScopeRuleLoader.class), mock(ApiScopeRuleLoader.class),
			noDataSources, sync::evictAll, () -> "别的实例改过", 30_000, 1_000, () -> 0L);

		task.becomeReady();
		task.tick();

		// 缓存清掉了：再用到时又去读了一次库
		validator.validate("t1");
		validator.loadPackageKeys(PACKAGE_ID);
		verify(tenantMapper, times(2)).selectOneByQuery(any(QueryWrapper.class));
		verify(packageMapper, times(2)).selectOneById(PACKAGE_ID);
		// 跟着别的实例清缓存不算改动：再通知出去的话，各实例会没完没了地互相通知
		verify(version, never()).bump();
	}

	@Test
	void theValidatorHasNoOtherMethods() {
		// 校验器的公开方法就这些，会缓存的是 loadTenant、validate 和 loadPackageKeys。
		// 这条失败说明 boot 改了它：看新方法缓不缓存、要不要让 TenantCacheSync 也盯着，再改这张清单
		List<String> methods = Arrays.stream(TenantValidator.class.getDeclaredMethods())
			.filter(method -> Modifier.isPublic(method.getModifiers()) && !method.isSynthetic())
			.map(method -> method.getName() + Arrays.stream(method.getParameterTypes()).map(Class::getSimpleName)
				.collect(Collectors.joining(",", "(", ")")))
			.sorted()
			.toList();

		assertThat(methods).containsExactly(
			"activeTenantExists(String)",
			"assertAccountQuota(String)",
			"evict(String)",
			"evictPackage(Long)",
			"invalidReason(SysTenant)",
			"loadPackageKeys(Long)",
			"loadTenant(String)",
			"quotaLocked(String,Supplier)",
			"validate(String)");
	}
}
