package com.mugsun.cloud.common.sync;

import org.aspectj.lang.annotation.After;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * 本实例上有人改了数据权限规则、接口权限规则和角色授权、租户独立数据源时，通知其它实例重新加载。
 *
 * <p>怎么知道有人改了：mugsun-boot 的控制器保存以后会刷新本进程内存里的那一份，也就是调用两个规则加载器的 reload、
 * 数据源注册表的 register 和 unregister。这里盯着这几个方法：单体版会刷新自己的时候，就是该告诉别的实例的时候，
 * boot 以后新增的调用点也自动算在内。这几个类是同步来的，不能改，所以用切面。加载器和注册表在启动时自己调用自己，
 * 不经过这里；本实例跟着别人重新加载（{@link SharedStateSyncTask}）不算改动，不再通知。
 *
 * <p>什么时候通知：别的实例收到通知就去读库，所以要等这次改动全部写进库里。
 * <ul>
 * <li>在处理请求的线程里：等请求处理完，只通知一次。这时事务已经提交；「先注销数据源、再删库里的配置」这种写法，
 * 删除也已经做完。</li>
 * <li>不在请求里、在事务里：等事务结束。</li>
 * <li>都不在：立刻通知。</li>
 * </ul>
 *
 * <p>方法抛了异常、事务回滚了也照样通知。多通知一次，各实例只是多读一遍库；本实例如果在回滚之前已经把没提交的数据
 * 刷进了内存，也靠这次通知改回来。
 *
 * <p>方法改了名、切点对不上时，这个切面不起作用，改动要等定时的重新加载才到别的实例（单测里用真的类看切点还对不对得上）。
 */
@Aspect
@Component
@ConditionalOnProperty(name = "mugsun.cloud.sync.enabled", havingValue = "true", matchIfMissing = true)
public class SharedStateChangeAspect {

	/** 请求上的标记：这次请求改过共享状态，处理完以后要通知 */
	static final String PENDING = SharedStateChangeAspect.class.getName() + ".pending";

	private final SharedStateVersion version;

	public SharedStateChangeAspect(SharedStateVersion version) {
		this.version = version;
	}

	@After("execution(* com.mugsun.boot.config.DataScopeRuleLoader.reload())"
		+ " || execution(* com.mugsun.boot.config.ApiScopeRuleLoader.reload())"
		+ " || execution(* com.mugsun.boot.datasource.TenantDataSourceRegistry.register(..))"
		+ " || execution(* com.mugsun.boot.datasource.TenantDataSourceRegistry.unregister(..))")
	public void changed() {
		if (SharedStateSyncTask.isSyncing()) {
			return;
		}
		if (notifyWhenRequestCompletes() || notifyWhenTransactionCompletes()) {
			return;
		}
		version.bump();
	}

	private boolean notifyWhenRequestCompletes() {
		RequestAttributes request = RequestContextHolder.getRequestAttributes();
		if (request == null) {
			return false;
		}
		try {
			request.setAttribute(PENDING, Boolean.TRUE, RequestAttributes.SCOPE_REQUEST);
		} catch (IllegalStateException e) {
			// 请求已经处理完了，这次调用是在它之后发生的：等不到「处理完」的那一刻了
			return false;
		}
		// 同一个名字只留一个回调，一次请求里改了几处也只通知一次
		request.registerDestructionCallback(PENDING, version::bump, RequestAttributes.SCOPE_REQUEST);
		return true;
	}

	private boolean notifyWhenTransactionCompletes() {
		if (!TransactionSynchronizationManager.isSynchronizationActive()) {
			return false;
		}
		// 用 afterCompletion，不用 afterCommit：控制器可能是在另一个提交后回调里调用过来的，那时新登记的回调等不到 afterCommit
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCompletion(int status) {
				version.bump();
			}
		});
		return true;
	}
}
