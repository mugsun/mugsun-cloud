package com.mugsun.cloud.gateway.route;

import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.core.Ordered;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * 动态路由交给 Spring Cloud Gateway 的地方。这里放的是 {@link DynamicRouteLoader} 检查并转换好的路由，
 * 网关刷新路由表时原样取走，不再转换一遍：检查过的和真正在用的是同一份。
 *
 * <p>排在 application.yml 里的路由前面：order 相同时，动态路由先匹配。
 */
public class DynamicRouteLocator implements RouteLocator, Ordered {

	private volatile List<Route> routes = List.of();

	@Override
	public Flux<Route> getRoutes() {
		return Flux.fromIterable(routes);
	}

	void replace(List<Route> routes) {
		this.routes = List.copyOf(routes);
	}

	@Override
	public int getOrder() {
		return Ordered.HIGHEST_PRECEDENCE;
	}
}
