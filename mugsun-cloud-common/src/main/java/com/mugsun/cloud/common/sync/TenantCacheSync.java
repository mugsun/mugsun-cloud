package com.mugsun.cloud.common.sync;

import com.mugsun.boot.tenant.TenantValidator;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 让本实例缓存的租户和套餐跟上别的实例上的改动。
 *
 * <p>mugsun-boot 的 {@link TenantValidator} 把查到的租户（状态、到期时间、用的哪个套餐）和套餐的菜单各缓存 60 秒，
 * 缓存在进程自己的内存里；管理员一保存，控制器调用它的 evict、evictPackage 把那一条清掉。多个实例时清掉的只是
 * 处理保存的那个实例上的那一条，别的实例要等缓存过期：停用一个租户以后，它已经登录的人在别的实例上最长还能用 60 秒。
 *
 * <p>这里做两件事：
 * <ul>
 * <li>记下本实例缓存过哪些租户、哪些套餐。TenantValidator 没有「全部清掉」的方法，缓存又是它私有的，只能一条一条清，
 * 所以要知道有哪些。只记库里查得到的租户：查不到的 TenantValidator 不缓存，这里也不记，不然登录页上乱填的租户编号
 * 能把这张表撑大。记下的不删，最多和用过的租户、套餐一样多。</li>
 * <li>{@link SharedStateSyncTask} 每次重新加载时调用 {@link #evictAll()}，把记下的都清掉，下一次用到时再查库。
 * 别的实例上改的是哪一个租户、哪一个套餐，通知里没有带，所以全清：每个租户、每个套餐多查一次库。</li>
 * </ul>
 *
 * <p>改动怎么通知到别的实例：{@link SharedStateChangeAspect} 盯着 evict 和 evictPackage，和权限规则用同一个版本值。
 *
 * <p>TenantValidator 是同步来的，不能改，所以用切面。它的 validate 在类里面直接调用 loadTenant，这一次调用不经过切面，
 * 所以 validate 也要盯着。boot 以后给它加了别的会缓存的方法、这里没跟上时，那些缓存靠它自己的 60 秒过期跟上
 * （单测里对着它的方法清单，多了、改了会先失败）。
 */
@Aspect
@Component
@ConditionalOnProperty(name = "mugsun.cloud.sync.enabled", havingValue = "true", matchIfMissing = true)
public class TenantCacheSync {

	/** 库里没有这个租户时 validate 返回的那句话 */
	private static final String NOT_FOUND = TenantValidator.invalidReason(null);

	/** 用到时才取：取到的是套了切面的那一个，这个类自己就是其中一个切面 */
	private final ObjectProvider<TenantValidator> validator;

	private final Set<String> tenants = ConcurrentHashMap.newKeySet();

	private final Set<Long> packages = ConcurrentHashMap.newKeySet();

	public TenantCacheSync(ObjectProvider<TenantValidator> validator) {
		this.validator = validator;
	}

	@AfterReturning(pointcut = "execution(* com.mugsun.boot.tenant.TenantValidator.loadTenant(String)) && args(code)",
		returning = "tenant")
	public void tenantLoaded(String code, Object tenant) {
		if (code != null && tenant != null) {
			tenants.add(code);
		}
	}

	@AfterReturning(pointcut = "execution(* com.mugsun.boot.tenant.TenantValidator.validate(String)) && args(code)",
		returning = "reason")
	public void tenantValidated(String code, Object reason) {
		if (code != null && !NOT_FOUND.equals(reason)) {
			tenants.add(code);
		}
	}

	@AfterReturning("execution(* com.mugsun.boot.tenant.TenantValidator.loadPackageKeys(Long)) && args(packageId)")
	public void packageLoaded(Long packageId) {
		if (packageId != null) {
			packages.add(packageId);
		}
	}

	/** 把本实例缓存过的租户和套餐都清掉。 */
	void evictAll() {
		if (tenants.isEmpty() && packages.isEmpty()) {
			return;
		}
		TenantValidator target = validator.getObject();
		tenants.forEach(target::evict);
		packages.forEach(target::evictPackage);
	}

	int tenantCount() {
		return tenants.size();
	}

	int packageCount() {
		return packages.size();
	}
}
