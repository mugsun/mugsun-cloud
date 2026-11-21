package com.mugsun.cloud.gateway.support;

import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.web.server.ServerWebExchange;

import java.net.URI;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 一次请求涉及的所有路径：客户端发来的、路由改写之前的、改写之后要发给服务的。
 *
 * <p>按路径拦请求的过滤器要把这几个都检查一遍：只看客户端发来的路径，给路由加了去前缀、改写路径的规则以后就能绕过去。
 * 要在路由算出转发地址之后调用（过滤器的顺序排在 {@code RouteToRequestUrlFilter} 后面），否则拿不到改写之后的路径。
 */
public final class RequestPaths {

	private RequestPaths() {
	}

	/** 各路径的原始写法（未解码，不含查询串） */
	public static Set<String> of(ServerWebExchange exchange) {
		Set<String> paths = new LinkedHashSet<>();
		paths.add(exchange.getRequest().getURI().getRawPath());
		Set<URI> originals = exchange.getAttributeOrDefault(ServerWebExchangeUtils.GATEWAY_ORIGINAL_REQUEST_URL_ATTR,
			Set.of());
		for (URI original : originals) {
			paths.add(original.getRawPath());
		}
		URI target = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR);
		if (target != null) {
			paths.add(target.getRawPath());
		}
		return paths;
	}
}
