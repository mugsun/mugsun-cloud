package com.mugsun.cloud.gateway.filter;

import com.mugsun.cloud.gateway.support.TraceIds;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.loadbalancer.DefaultRequest;
import org.springframework.cloud.client.loadbalancer.DefaultResponse;
import org.springframework.cloud.client.loadbalancer.LoadBalancerUriTools;
import org.springframework.cloud.client.loadbalancer.Request;
import org.springframework.cloud.client.loadbalancer.RequestData;
import org.springframework.cloud.client.loadbalancer.RequestDataContext;
import org.springframework.cloud.client.loadbalancer.Response;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.filter.ReactiveLoadBalancerClientFilter;
import org.springframework.cloud.gateway.support.DelegatingServiceInstance;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplier;
import org.springframework.cloud.loadbalancer.support.LoadBalancerClientFactory;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 连不上选中的服务实例时，换一个实例再转发一次。
 *
 * <p>网关把每个服务的实例列表缓存几秒（{@code spring.cloud.loadbalancer.cache.ttl}）。一个实例意外挂掉以后，
 * 在列表更新之前，轮到它的请求会连不上。这时别的实例是好的，换一个转发，客户端就不会收到 502。
 * 正常停止的实例不靠这里：它先从注册中心注销、过几秒才停，见 mugsun-cloud-common 的 DeregisterBeforeStop。
 *
 * <p>只在连接没有建立起来时重试：连接被拒绝、连接超时、找不到主机。这时请求一个字节都还没发出去，对方不可能已经
 * 执行过，所以不分请求方法都可以重试；请求体也还没被读过，不需要先存进内存。已经连上以后的失败（对方中途断开、
 * 迟迟不返回）不重试，因为对方可能已经执行了。服务之间的内部调用是同一条规则，见 mugsun-cloud-common 的 InnerClient。
 *
 * <p>只重试一次。没有别的实例可换，或者换了还是不行，按原来的方式返回 502 / 504。
 *
 * <p>没有用网关自带的 Retry 过滤器：它为了能重发，会把每个请求的请求体整个存进内存，上传大文件时网关吃不消。
 *
 * <p>排在负载均衡选出实例之后，只包住「转发」这一步。
 */
@Component
public class NextInstanceOnConnectFailureFilter implements GlobalFilter, Ordered {

	/** 负载均衡过滤器之后还有一个写实例 Cookie 的过滤器（+1），排在它后面 */
	static final int ORDER = ReactiveLoadBalancerClientFilter.LOAD_BALANCER_CLIENT_FILTER_ORDER + 2;

	private static final Logger log = LoggerFactory.getLogger(NextInstanceOnConnectFailureFilter.class);
	private static final int MAX_CAUSE_DEPTH = 8;
	private static final int MAX_LOGGED_PATH = 200;

	private final LoadBalancerClientFactory clientFactory;

	public NextInstanceOnConnectFailureFilter(LoadBalancerClientFactory clientFactory) {
		this.clientFactory = clientFactory;
	}

	@Override
	public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
		Response<ServiceInstance> chosen = exchange.getAttribute(
			ServerWebExchangeUtils.GATEWAY_LOADBALANCER_RESPONSE_ATTR);
		URI target = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR);
		if (chosen == null || !chosen.hasServer() || target == null) {
			// 路由里直接写了地址，没有经过负载均衡：没有别的实例可换
			return chain.filter(exchange);
		}
		ServiceInstance first = chosen.getServer();
		return chain.filter(exchange).onErrorResume(
			error -> !exchange.getResponse().isCommitted() && neverConnected(error),
			error -> instancesOf(first.getServiceId(), exchange).flatMap(instances -> {
				ServiceInstance next = another(instances, first);
				if (next == null) {
					return Mono.error(error);
				}
				log.warn("连不上 {} 的实例 {}:{}，换 {}:{} 再转发一次：{} {}，原因：{}，请求编号 {}", first.getServiceId(),
					first.getHost(), first.getPort(), next.getHost(), next.getPort(), exchange.getRequest().getMethod(),
					shown(exchange.getRequest().getURI().getRawPath()), error.toString(), TraceIds.of(exchange));
				exchange.getAttributes().put(ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR,
					LoadBalancerUriTools.reconstructURI(new DelegatingServiceInstance(next, target.getScheme()), target));
				exchange.getAttributes().put(ServerWebExchangeUtils.GATEWAY_LOADBALANCER_RESPONSE_ATTR,
					new DefaultResponse(next));
				// 清掉「已经转发过」的标记，转发过滤器才会再执行一次
				ServerWebExchangeUtils.reset(exchange);
				return chain.filter(exchange);
			}));
	}

	/** 连接有没有建立起来。没建立起来，请求就一个字节都没发出去 */
	static boolean neverConnected(Throwable error) {
		Throwable current = error;
		for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++) {
			// 连接超时（Netty 的 ConnectTimeoutException）也是 ConnectException
			if (current instanceof ConnectException || current instanceof NoRouteToHostException
				|| current instanceof UnknownHostException) {
				return true;
			}
			current = current.getCause();
		}
		return false;
	}

	/** 从列表里挑一个地址和刚才那个不同的实例；没有就返回 null */
	static ServiceInstance another(List<ServiceInstance> instances, ServiceInstance first) {
		List<ServiceInstance> others = instances.stream()
			.filter(instance -> !(instance.getPort() == first.getPort() && instance.getHost() != null
				&& instance.getHost().equals(first.getHost())))
			.toList();
		if (others.isEmpty()) {
			return null;
		}
		return others.get(ThreadLocalRandom.current().nextInt(others.size()));
	}

	/** 负载均衡现在手里的实例列表，和它选实例时用的是同一份；取不到按没有处理 */
	private Mono<List<ServiceInstance>> instancesOf(String serviceId, ServerWebExchange exchange) {
		ServiceInstanceListSupplier supplier = clientFactory
			.getLazyProvider(serviceId, ServiceInstanceListSupplier.class).getIfAvailable();
		if (supplier == null) {
			return Mono.just(List.of());
		}
		Request<RequestDataContext> request = new DefaultRequest<>(new RequestDataContext(
			new RequestData(exchange.getRequest(), exchange.getAttributes()), hintOf(serviceId)));
		return supplier.get(request).next().defaultIfEmpty(List.of()).onErrorReturn(List.of());
	}

	/** 和负载均衡过滤器取提示值的方式一样：按提示值分组的实例（比如灰度），重试时不会换到别的组去 */
	private String hintOf(String serviceId) {
		Map<String, String> hints = clientFactory.getProperties(serviceId).getHint();
		String specific = hints.get(serviceId);
		return specific != null ? specific : hints.getOrDefault("default", "default");
	}

	private static String shown(String path) {
		if (path != null && path.length() > MAX_LOGGED_PATH) {
			return path.substring(0, MAX_LOGGED_PATH) + "...";
		}
		return path;
	}

	@Override
	public int getOrder() {
		return ORDER;
	}
}
