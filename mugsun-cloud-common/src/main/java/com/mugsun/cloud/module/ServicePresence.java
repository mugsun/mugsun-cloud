package com.mugsun.cloud.module;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * 某个服务现在有没有部署：看注册中心里有没有它的实例。
 *
 * <p>结果缓存几秒，不用每个请求都去问。服务重启或者滚动发布的那一小段时间里实例数会降到 0，
 * 所以发现实例没了之后还要再等一段宽限期才算不在，免得用户正好这时候刷新页面，菜单少一块。
 * 注册中心问不到时沿用上一次的结果。
 */
@Component
public class ServicePresence {

	private static final Logger log = LoggerFactory.getLogger(ServicePresence.class);

	private final DiscoveryClient discoveryClient;

	private final long cacheNanos;

	private final long graceNanos;

	private final LongSupplier nanoClock;

	private final Map<String, State> states = new ConcurrentHashMap<>();

	/** 要定时查的服务 */
	private final Set<String> watched = ConcurrentHashMap.newKeySet();

	@Autowired
	public ServicePresence(DiscoveryClient discoveryClient,
						   @Value("${mugsun.cloud.module.presence-cache:5s}") Duration cache,
						   @Value("${mugsun.cloud.module.absent-grace:60s}") Duration grace) {
		this(discoveryClient, cache, grace, System::nanoTime);
	}

	ServicePresence(DiscoveryClient discoveryClient, Duration cache, Duration grace, LongSupplier nanoClock) {
		this.discoveryClient = discoveryClient;
		this.cacheNanos = cache.toNanos();
		this.graceNanos = grace.toNanos();
		this.nanoClock = nanoClock;
	}

	/** 登记一个要定时查的服务。问过一次的服务会自动登记；启动时就登记，还没人访问时也在查。 */
	public void watch(String serviceId) {
		watched.add(serviceId);
	}

	public boolean present(String serviceId) {
		watched.add(serviceId);
		long now = nanoClock.getAsLong();
		State state = states.get(serviceId);
		if (state != null && now - state.checkedAt() < cacheNanos) {
			return state.present();
		}
		return check(serviceId, state, now);
	}

	/**
	 * 每秒看一次，把结果已经过期的服务重新查一遍。
	 *
	 * <p>只在有人打开页面时才查是不够的：服务下线之后如果一直没人访问，宽限期要等到下一个人来了才开始算，
	 * 他会看到一个点进去就报错的菜单。
	 */
	@Scheduled(fixedDelay = 1000)
	public void refresh() {
		long now = nanoClock.getAsLong();
		for (String serviceId : watched) {
			State state = states.get(serviceId);
			if (state == null || now - state.checkedAt() >= cacheNanos) {
				check(serviceId, state, now);
			}
		}
	}

	private boolean check(String serviceId, State state, long now) {
		boolean previous = state != null && state.present();
		boolean hasInstance;
		try {
			hasInstance = !discoveryClient.getInstances(serviceId).isEmpty();
		} catch (RuntimeException e) {
			if (state == null || !state.failing()) {
				log.warn("向注册中心查询服务 {} 失败，暂时按{}处理：{}", serviceId, previous ? "已部署" : "未部署", e.getMessage());
			}
			states.put(serviceId, new State(previous, now, state == null ? null : state.goneSince(), true));
			return previous;
		}
		if (hasInstance) {
			states.put(serviceId, new State(true, now, null, false));
			return true;
		}
		if (!previous) {
			states.put(serviceId, new State(false, now, null, false));
			return false;
		}
		// 上次还在、这次没查到实例：宽限期从第一次发现实例没了的时刻算起
		long goneSince = state.goneSince() != null ? state.goneSince() : now;
		boolean present = now - goneSince < graceNanos;
		states.put(serviceId, new State(present, now, present ? Long.valueOf(goneSince) : null, false));
		return present;
	}

	/**
	 * @param present   上次得出的结论
	 * @param checkedAt 上次查询的时刻
	 * @param goneSince 实例已经没了、还在宽限期内时，第一次发现实例没了的时刻；其它情况为 null
	 * @param failing   上次查询是否失败（连续失败时只记一次日志）
	 */
	private record State(boolean present, long checkedAt, Long goneSince, boolean failing) {
	}
}
