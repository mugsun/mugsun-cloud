package com.mugsun.cloud.gateway.filter;

import com.mugsun.cloud.gateway.support.TraceIds;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * 给每个请求定一个编号，放在 {@code X-Trace-Id} 请求头里传给后面的服务，并保证响应头里也有它。
 *
 * <p>客户端带来的编号写法合法就沿用（和单体版一样），不合法或者没带就由网关生成。服务拿到的始终是一个合法的编号，
 * 所以网关的访问日志和服务的日志能用它对上。
 *
 * <p>响应头里的编号以网关的为准：服务正常返回时自己会带上同一个编号，这里覆盖成一份，不会出现两份；
 * 网关自己产生的响应（服务连不上、请求被网关拒绝）没有人写，也由这里补上。
 * 写的动作挂在「响应即将写出」的时刻，那时服务返回的响应头已经合并进来。
 * 不能等响应写出之后再改响应头：WebSocket 握手会因此报错断开。
 */
@Component
public class TraceIdFilter implements WebFilter, Ordered {

	/** 排在最前：后面的过滤器和出错处理记日志时都能取到编号 */
	public static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 1;

	@Override
	public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
		List<String> given = exchange.getRequest().getHeaders().getOrEmpty(TraceIds.HEADER);
		boolean reuse = given.size() == 1 && TraceIds.acceptable(given.get(0));
		String traceId = reuse ? given.get(0) : TraceIds.generate();
		exchange.getAttributes().put(TraceIds.ATTRIBUTE, traceId);

		ServerHttpResponse response = exchange.getResponse();
		response.beforeCommit(() -> {
			response.getHeaders().set(TraceIds.HEADER, traceId);
			return Mono.empty();
		});

		if (reuse) {
			return chain.filter(exchange);
		}
		ServerHttpRequest request = exchange.getRequest().mutate()
			.headers(headers -> headers.set(TraceIds.HEADER, traceId))
			.build();
		return chain.filter(exchange.mutate().request(request).build());
	}

	@Override
	public int getOrder() {
		return ORDER;
	}
}
