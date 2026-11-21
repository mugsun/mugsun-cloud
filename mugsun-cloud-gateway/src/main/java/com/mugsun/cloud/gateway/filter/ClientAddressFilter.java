package com.mugsun.cloud.gateway.filter;

import com.mugsun.cloud.gateway.support.ClientView;
import com.mugsun.cloud.gateway.support.ClientViewResolver;
import org.springframework.cloud.gateway.filter.headers.HttpHeadersFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 把真实的客户端地址和客户端访问时用的协议、主机名、端口交给后面的服务（普通请求和 WebSocket 握手都经过这里）。
 *
 * <p>客户端自己带来的 Forwarded、X-Forwarded-*、X-Real-IP 一律不转发，由网关写入算好的值，客户端伪造不了。
 * 后面的服务开着 {@code server.forward-headers-strategy=native}，由容器采信这几个头，
 * 业务代码里的 {@code getRemoteAddr()}、{@code isSecure()} 得到的值和单体版直接对外时一样。
 *
 * <p>排在所有请求头处理的最后：Spring Cloud Gateway 自己会先把转发头全部删掉，写在它前面会被一起删掉。
 */
@Component
public class ClientAddressFilter implements HttpHeadersFilter, Ordered {

	private final ClientViewResolver resolver;

	public ClientAddressFilter(ClientViewResolver resolver) {
		this.resolver = resolver;
	}

	@Override
	public HttpHeaders filter(HttpHeaders input, ServerWebExchange exchange) {
		ClientView view = resolver.resolve(exchange.getRequest());
		HttpHeaders output = new HttpHeaders();
		for (Map.Entry<String, List<String>> entry : input.headerSet()) {
			if (!isForwardingHeader(entry.getKey())) {
				output.addAll(entry.getKey(), entry.getValue());
			}
		}
		if (!view.clientIp().isEmpty()) {
			output.set(ClientView.X_FORWARDED_FOR, view.clientIp());
		}
		output.set(ClientView.X_FORWARDED_PROTO, view.scheme());
		if (!view.host().isEmpty()) {
			output.set(ClientView.X_FORWARDED_HOST, view.host());
		}
		output.set(ClientView.X_FORWARDED_PORT, String.valueOf(view.port()));
		return output;
	}

	private static boolean isForwardingHeader(String name) {
		String lower = name.toLowerCase(Locale.ROOT);
		return lower.equals("forwarded") || lower.equals("x-real-ip") || lower.startsWith("x-forwarded-");
	}

	@Override
	public int getOrder() {
		return Ordered.LOWEST_PRECEDENCE;
	}
}
