package com.mugsun.cloud.gateway.filter;

import org.springframework.cloud.gateway.filter.headers.HttpHeadersFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 跨域响应头只由网关写（规则在配置的 globalcors 里）。
 *
 * <p>后面的服务带着和单体版一样的跨域过滤器，也会写一份 Access-Control-*；两份一起返回的话，
 * 浏览器会因为 Access-Control-Allow-Origin 出现两次而拒收响应。这里把服务写的那份去掉。
 *
 * <p>不用自带的 DedupeResponseHeader：它在响应写出之后才改响应头，WebSocket 握手会因此报错断开。
 */
@Component
public class DownstreamCorsHeadersFilter implements HttpHeadersFilter, Ordered {

	private static final String CORS_PREFIX = "access-control-";

	@Override
	public boolean supports(Type type) {
		return type == Type.RESPONSE;
	}

	@Override
	public HttpHeaders filter(HttpHeaders input, ServerWebExchange exchange) {
		HttpHeaders output = new HttpHeaders();
		for (Map.Entry<String, List<String>> entry : input.headerSet()) {
			if (!entry.getKey().toLowerCase(Locale.ROOT).startsWith(CORS_PREFIX)) {
				output.addAll(entry.getKey(), entry.getValue());
			}
		}
		return output;
	}

	@Override
	public int getOrder() {
		return 0;
	}
}
