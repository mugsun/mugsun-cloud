package com.mugsun.cloud.gateway.route;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.config.GatewayProperties;
import org.springframework.cloud.gateway.event.RefreshRoutesEvent;
import org.springframework.cloud.gateway.filter.FilterDefinition;
import org.springframework.cloud.gateway.filter.factory.GatewayFilterFactory;
import org.springframework.cloud.gateway.handler.predicate.PredicateDefinition;
import org.springframework.cloud.gateway.handler.predicate.RoutePredicateFactory;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.cloud.gateway.route.RouteDefinitionRouteLocator;
import org.springframework.cloud.gateway.support.ConfigurationService;
import org.springframework.context.ApplicationEventPublisher;
import reactor.core.publisher.Flux;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 把一份路由配置整份换上去：先读，再逐条检查，再转换，全部合格才一次换掉原来的动态路由并通知网关刷新；
 * 有一条不合格就整份不用，原来的路由不动。空的配置表示不要动态路由。
 *
 * <p>动态路由只管三件事：哪些请求（predicates）转给注册中心里的哪个服务（uri），路上怎么改路径（filters），
 * 和别的路由谁先谁后（order）。能用的断言和过滤器是下面两张固定的表，表外的一律不收：
 * <ul>
 * <li>过滤器只有改路径的。改写以后的路径照样要过内部接口拦截和地址名单，它们排在路由的过滤器之后。
 * 能改请求头的不收：客户端地址是从请求头里算出来的，改了请求头就能冒充别的地址。
 * 能把请求引到任意地址的（RequestHeaderToRequestUri、RedirectTo）和改响应头的也不收。</li>
 * <li>断言只有看请求本身的。RemoteAddr 看的是直接连上来的地址，网关前面有代理时那是代理的地址，
 * 按地址限制要用 mugsun.gateway.allow-ips 那一组配置；Weight 靠事件登记权重，路由删掉以后权重还留着。</li>
 * </ul>
 * 转换用的是一个不发事件的 ConfigurationService：一份配置不管最后收不收，都不会在别处留下痕迹。
 */
public class DynamicRouteLoader {

	public enum Status {
		/** 这份配置已经换上 */
		UPDATED,
		/** 和正在用的一样，什么都没做 */
		UNCHANGED,
		/** 不合格，没有用，原来的路由不动 */
		REJECTED
	}

	/**
	 * @param status  这份配置的下场
	 * @param routes  现在生效的动态路由有几条
	 * @param problem 不合格的原因；合格时为 null
	 */
	public record Outcome(Status status, int routes, String problem) {
	}

	static final List<String> PREDICATES = List.of("After", "Before", "Between", "Cookie", "Header", "Host", "Method",
		"Path", "Query");

	static final List<String> FILTERS = List.of("PrefixPath", "RewritePath", "SetPath", "StripPrefix");

	private static final Logger log = LoggerFactory.getLogger(DynamicRouteLoader.class);
	private static final Pattern ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");
	private static final Duration CONVERT_TIMEOUT = Duration.ofSeconds(10);

	private final DynamicRouteLocator locator;
	private final GatewayProperties gatewayProperties;
	private final String selfService;
	private final ApplicationEventPublisher publisher;
	private final RouteDefinitionRouteLocator converter;

	/** 正在转换的那一条，converter 从这里取；只在 load 里读写 */
	private List<RouteDefinition> converting = List.of();

	/** 正在用的动态路由的定义，用来判断新来的配置有没有变 */
	private List<RouteDefinition> current = List.of();

	/**
	 * @param quietConfigurationService 没有事件发布器的 ConfigurationService（自己 new 的、没有注册成 bean 的就是）
	 * @param selfService               网关自己在注册中心里的名字，动态路由不能转给它
	 */
	@SuppressWarnings("rawtypes")
	public DynamicRouteLoader(DynamicRouteLocator locator, GatewayProperties gatewayProperties,
			List<RoutePredicateFactory> predicates, List<GatewayFilterFactory> filters,
			ConfigurationService quietConfigurationService, String selfService, ApplicationEventPublisher publisher) {
		this.locator = locator;
		this.gatewayProperties = gatewayProperties;
		this.selfService = selfService == null ? "" : selfService.trim();
		this.publisher = publisher;
		this.converter = new RouteDefinitionRouteLocator(() -> Flux.fromIterable(converting), predicates, filters,
			gatewayProperties, quietConfigurationService);
	}

	/**
	 * 要在可以等待的线程上调用（Nacos 的回调线程、启动线程），不要在处理请求的线程上调用。
	 *
	 * @param content 配置的全文；null 或空白表示一条动态路由也不要
	 */
	public synchronized Outcome load(String content) {
		List<RouteDefinition> definitions;
		List<Route> routes;
		try {
			definitions = DynamicRouteParser.parse(content);
			check(definitions);
			if (definitions.equals(current)) {
				return new Outcome(Status.UNCHANGED, current.size(), null);
			}
			routes = convert(definitions);
		} catch (InvalidRoutesException e) {
			log.warn("动态路由没有更新，继续用原来的 {} 条。原因：{}", current.size(), e.getMessage());
			return new Outcome(Status.REJECTED, current.size(), e.getMessage());
		}
		locator.replace(routes);
		current = definitions;
		if (routes.isEmpty()) {
			log.info("动态路由已清空：配置被删掉了，或者里面没有路由。现在只按 application.yml 里的路由转发");
		} else {
			log.info("动态路由已更新，现在 {} 条：{}", routes.size(),
				definitions.stream().map(RouteDefinition::getId).collect(Collectors.joining(", ")));
		}
		publisher.publishEvent(new RefreshRoutesEvent(this));
		return new Outcome(Status.UPDATED, routes.size(), null);
	}

	private void check(List<RouteDefinition> definitions) {
		Set<String> builtIn = gatewayProperties.getRoutes().stream().map(RouteDefinition::getId)
			.collect(Collectors.toSet());
		Set<String> seen = new HashSet<>();
		for (int i = 0; i < definitions.size(); i++) {
			RouteDefinition definition = definitions.get(i);
			String id = definition.getId();
			if (id == null || !ID.matcher(id).matches()) {
				throw new InvalidRoutesException("第 " + (i + 1)
					+ " 条路由的 id 没写或者写法不对：字母或数字开头，只能有字母、数字、点、横线、下划线，最长 64 个字符");
			}
			if (!seen.add(id)) {
				throw new InvalidRoutesException("路由 id " + id + " 出现了不止一次");
			}
			if (builtIn.contains(id)) {
				throw new InvalidRoutesException("路由 id " + id + " 和 application.yml 里的路由重名");
			}
			checkUri(id, definition.getUri());
			if (definition.getPredicates().isEmpty()) {
				throw new InvalidRoutesException("路由 " + id + " 没写 predicates，不知道哪些请求走这条路由");
			}
			for (PredicateDefinition predicate : definition.getPredicates()) {
				if (predicate.getName() == null || !PREDICATES.contains(predicate.getName())) {
					throw new InvalidRoutesException("路由 " + id + " 用了动态路由里不能用的断言 "
						+ InvalidRoutesException.name(predicate.getName()) + "。能用的：" + String.join("、", PREDICATES));
				}
			}
			for (FilterDefinition filter : definition.getFilters()) {
				if (filter.getName() == null || !FILTERS.contains(filter.getName())) {
					throw new InvalidRoutesException("路由 " + id + " 用了动态路由里不能用的过滤器 "
						+ InvalidRoutesException.name(filter.getName()) + "。能用的：" + String.join("、", FILTERS));
				}
			}
			if (!definition.getMetadata().isEmpty()) {
				throw new InvalidRoutesException("路由 " + id + " 写了 metadata，动态路由里不支持");
			}
		}
	}

	private void checkUri(String id, URI uri) {
		if (uri == null) {
			throw new InvalidRoutesException("路由 " + id + " 没写 uri");
		}
		String path = uri.getRawPath();
		boolean serviceOnly = "lb".equals(uri.getScheme()) && uri.getHost() != null && uri.getPort() == -1
			&& uri.getRawUserInfo() == null && (path == null || path.isEmpty() || path.equals("/"))
			&& uri.getRawQuery() == null && uri.getRawFragment() == null;
		if (!serviceOnly) {
			throw new InvalidRoutesException("路由 " + id
				+ " 的 uri 只能写成 lb://服务名：服务名是注册中心里的名字（不能带下划线），后面不带端口和路径");
		}
		if (!selfService.isEmpty() && selfService.equalsIgnoreCase(uri.getHost())) {
			throw new InvalidRoutesException("路由 " + id + " 的 uri 指向网关自己，请求会在网关里打转");
		}
	}

	private List<Route> convert(List<RouteDefinition> definitions) {
		List<Route> routes = new ArrayList<>(definitions.size());
		for (RouteDefinition definition : definitions) {
			Route route = convertOne(definition);
			if (route == null) {
				throw new InvalidRoutesException("路由 " + definition.getId() + " 的" + culprit(definition) + "参数写得不对");
			}
			routes.add(route);
		}
		return routes;
	}

	/** 转换不了返回 null。框架给的原因里可能带着配置的值，所以只记在 DEBUG 日志里，不往上报 */
	private Route convertOne(RouteDefinition definition) {
		converting = List.of(definition);
		try {
			List<Route> converted = converter.getRoutes().collectList().block(CONVERT_TIMEOUT);
			// fail-on-route-definition-error 关掉时，转换失败不抛异常，只是少一条
			return converted != null && converted.size() == 1 ? converted.get(0) : null;
		} catch (RuntimeException e) {
			log.debug("动态路由 {} 转换失败", definition.getId(), e);
			return null;
		} finally {
			converting = List.of();
		}
	}

	/** 一条路由转换不了时，把断言和过滤器拆开一个个试，找出是哪一个 */
	private String culprit(RouteDefinition definition) {
		List<PredicateDefinition> predicates = definition.getPredicates();
		for (int i = 0; i < predicates.size(); i++) {
			if (convertOne(piece(definition, List.of(predicates.get(i)), List.of())) == null) {
				return "第 " + (i + 1) + " 个断言（" + predicates.get(i).getName() + "）";
			}
		}
		List<FilterDefinition> filters = definition.getFilters();
		for (int i = 0; i < filters.size(); i++) {
			if (convertOne(piece(definition, List.of(), List.of(filters.get(i)))) == null) {
				return "第 " + (i + 1) + " 个过滤器（" + filters.get(i).getName() + "）";
			}
		}
		return "断言或过滤器";
	}

	private static RouteDefinition piece(RouteDefinition whole, List<PredicateDefinition> predicates,
			List<FilterDefinition> filters) {
		RouteDefinition piece = new RouteDefinition();
		piece.setId(whole.getId());
		piece.setUri(whole.getUri());
		piece.setPredicates(predicates);
		piece.setFilters(filters);
		return piece;
	}
}
