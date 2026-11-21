package com.mugsun.cloud.gateway.filter;

import com.mugsun.cloud.gateway.error.GatewayRejectedException;
import com.mugsun.cloud.gateway.props.GatewayGuardProperties;
import com.mugsun.cloud.gateway.support.ClientViewResolver;
import com.mugsun.cloud.gateway.support.PathGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.filter.RouteToRequestUrlFilter;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 服务之间互相调用的内部接口（默认 /inner 开头）不对外：网关不转发，按「资源不存在」返回。
 *
 * <p>排在路由算出转发地址之后、真正转发之前：客户端发来的路径和经路由改写后要发给服务的路径都检查，
 * 以后给路由加了去前缀、改写路径的规则，这里也不会被绕过。
 */
@Component
public class InnerPathGuardFilter implements GlobalFilter, Ordered {

	private static final Logger log = LoggerFactory.getLogger(InnerPathGuardFilter.class);
	private static final int MAX_LOGGED_PATH = 200;

	private final PathGuard pathGuard;
	private final ClientViewResolver clientViewResolver;

	public InnerPathGuardFilter(GatewayGuardProperties properties, ClientViewResolver clientViewResolver) {
		this.pathGuard = new PathGuard(properties.getInnerPath());
		this.clientViewResolver = clientViewResolver;
	}

	@Override
	public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
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

		PathGuard.Verdict worst = PathGuard.Verdict.PASS;
		for (String path : paths) {
			PathGuard.Verdict verdict = pathGuard.inspect(path);
			if (verdict == PathGuard.Verdict.INNER) {
				worst = verdict;
				break;
			}
			if (verdict == PathGuard.Verdict.MALFORMED) {
				worst = verdict;
			}
		}
		if (worst == PathGuard.Verdict.PASS) {
			return chain.filter(exchange);
		}

		String shown = exchange.getRequest().getURI().getRawPath();
		if (shown != null && shown.length() > MAX_LOGGED_PATH) {
			shown = shown.substring(0, MAX_LOGGED_PATH) + "...";
		}
		String clientIp = clientViewResolver.resolve(exchange.getRequest()).clientIp();
		if (worst == PathGuard.Verdict.INNER) {
			log.warn("拦下从外部访问内部接口的请求：{} {}，客户端 {}", exchange.getRequest().getMethod(), shown, clientIp);
			return Mono.error(new GatewayRejectedException(HttpStatus.NOT_FOUND, "资源不存在"));
		}
		log.warn("拒绝路径写法不合法的请求：{} {}，客户端 {}", exchange.getRequest().getMethod(), shown, clientIp);
		return Mono.error(new GatewayRejectedException(HttpStatus.BAD_REQUEST, "请求地址不合法"));
	}

	@Override
	public int getOrder() {
		return RouteToRequestUrlFilter.ROUTE_TO_URL_FILTER_ORDER + 1;
	}
}
