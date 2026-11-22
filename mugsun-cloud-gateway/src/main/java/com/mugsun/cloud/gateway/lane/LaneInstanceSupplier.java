package com.mugsun.cloud.gateway.lane;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.loadbalancer.Request;
import org.springframework.cloud.client.loadbalancer.RequestData;
import org.springframework.cloud.client.loadbalancer.RequestDataContext;
import org.springframework.cloud.loadbalancer.core.DelegatingServiceInstanceListSupplier;
import org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplier;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * 按灰度标记，从一个服务的实例里挑出这次请求能发的那几个，负载均衡再从里面选一个。
 *
 * <ul>
 * <li>没有标记的请求：只有正式实例。没有正式实例就是没有实例可发（返回 503），不会退而求其次发给灰度实例</li>
 * <li>有标记的请求：同一个标记的灰度实例；这个服务没有这样的实例时是正式实例。
 * 一次发版通常只动一两个服务，没动的服务只有正式实例，带着标记的请求到了它们那里照常处理</li>
 * <li>刚才选的那个没连上、要换一个再转发（见 NextInstanceOnConnectFailureFilter）：先换正式实例；没有正式实例，才换同一个标记的另一个灰度实例。
 * 同一个标记的几个灰度实例跑的是同一个新版本，一个起不来，另一个多半也一样；只能再试一次，就试最可能成的</li>
 * <li>别的标记的灰度实例：任何时候都不发</li>
 * </ul>
 *
 * <p>服务之间互相调用时是同一套规矩，见 mugsun-cloud-common 的 InnerClient。
 *
 * <p>包在带缓存的实例列表外面：缓存里是这个服务全部的实例，每个请求按自己的标记现挑。反过来包的话，缓存住的是某一个请求挑剩的结果。
 * 标记和「刚才没连上的实例」从请求的属性里取（{@link LaneNames#LANE_ATTR}、{@link LaneNames#FAILED_INSTANCE_ATTR}）；
 * 不是网关转发的请求取不到属性，按没有标记处理。
 */
final class LaneInstanceSupplier extends DelegatingServiceInstanceListSupplier {

	private static final Logger log = LoggerFactory.getLogger(LaneInstanceSupplier.class);
	private static final long WARN_INTERVAL_NANOS = TimeUnit.MINUTES.toNanos(1);

	private final LongSupplier nanoClock;
	private boolean warned;
	private long lastWarnedAt;

	LaneInstanceSupplier(ServiceInstanceListSupplier delegate) {
		this(delegate, System::nanoTime);
	}

	/** @param nanoClock 限制警告条数用的时钟 */
	LaneInstanceSupplier(ServiceInstanceListSupplier delegate, LongSupplier nanoClock) {
		super(delegate);
		this.nanoClock = nanoClock;
	}

	@Override
	public Flux<List<ServiceInstance>> get() {
		return delegate.get().map(all -> pick(all, null, null));
	}

	@Override
	@SuppressWarnings("rawtypes")
	public Flux<List<ServiceInstance>> get(Request request) {
		Map<String, Object> attributes = attributesOf(request);
		String lane = attributes.get(LaneNames.LANE_ATTR) instanceof String text ? text : null;
		ServiceInstance failed = attributes.get(LaneNames.FAILED_INSTANCE_ATTR) instanceof ServiceInstance instance
			? instance
			: null;
		return delegate.get(request).map(all -> pick(all, lane, failed));
	}

	/**
	 * @param all    这个服务在注册中心里的全部实例
	 * @param lane   这次请求的灰度标记；没有是 null
	 * @param failed 刚才没连上的那个实例；第一次选实例时是 null
	 */
	List<ServiceInstance> pick(List<ServiceInstance> all, String lane, ServiceInstance failed) {
		List<ServiceInstance> base = new ArrayList<>(all.size());
		List<ServiceInstance> sameLane = new ArrayList<>();
		for (ServiceInstance instance : all) {
			if (failed != null && sameAddress(instance, failed)) {
				continue;
			}
			String registered = LaneNames.of(instance);
			if (registered == null) {
				base.add(instance);
			} else if (registered.equals(lane)) {
				sameLane.add(instance);
			}
		}
		List<ServiceInstance> picked;
		if (lane == null) {
			picked = base;
		} else if (failed != null) {
			picked = base.isEmpty() ? sameLane : base;
		} else {
			picked = sameLane.isEmpty() ? base : sameLane;
		}
		if (picked.isEmpty() && !all.isEmpty() && failed == null && shouldWarn()) {
			log.warn("{} 在注册中心里的 {} 个实例都是灰度实例，{}不能发给它们，返回 503。灰度实例只接带着同一个灰度标记的请求，"
					+ "要留着没带标记的正式实例；检查是不是给所有实例都配了 MUGSUN_LANE（这条警告一分钟最多记一次）",
				getServiceId(), all.size(), lane == null ? "没有灰度标记的请求" : "灰度标记是 " + lane + " 的请求");
		}
		return picked;
	}

	private static boolean sameAddress(ServiceInstance one, ServiceInstance other) {
		return one.getPort() == other.getPort() && one.getHost() != null && one.getHost().equals(other.getHost());
	}

	private synchronized boolean shouldWarn() {
		long now = nanoClock.getAsLong();
		if (warned && now - lastWarnedAt < WARN_INTERVAL_NANOS) {
			return false;
		}
		warned = true;
		lastWarnedAt = now;
		return true;
	}

	private static Map<String, Object> attributesOf(Request<?> request) {
		if (request != null && request.getContext() instanceof RequestDataContext context) {
			RequestData clientRequest = context.getClientRequest();
			if (clientRequest != null && clientRequest.getAttributes() != null) {
				return clientRequest.getAttributes();
			}
		}
		return Map.of();
	}
}
