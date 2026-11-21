package com.mugsun.cloud.gateway.filter;

import com.mugsun.boot.common.web.IpCidr;
import com.mugsun.cloud.gateway.error.GatewayRejectedException;
import com.mugsun.cloud.gateway.props.GatewayGuardProperties;
import com.mugsun.cloud.gateway.support.ClientViewResolver;
import com.mugsun.cloud.gateway.support.PathPrefixes;
import com.mugsun.cloud.gateway.support.RequestPaths;
import com.mugsun.cloud.gateway.support.TraceIds;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.filter.RouteToRequestUrlFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;

/**
 * 按客户端地址决定转不转发，三条规则按顺序看，不转发的返回 403：
 *
 * <ol>
 *   <li>黑名单（{@code mugsun.gateway.deny-ips}）：地址在里面的不转发</li>
 *   <li>白名单（{@code mugsun.gateway.allow-ips}）：配了的话，地址不在里面的不转发</li>
 *   <li>不对外开放的路径（{@code mugsun.gateway.restricted-paths}）：只有
 *       {@code mugsun.gateway.restricted-allow-ips} 里的地址能访问；后者留空就是谁都不能经网关访问</li>
 * </ol>
 *
 * <p>四项默认都是空的，不配就没有任何限制。客户端地址用 {@link ClientViewResolver} 算，网关前面有反向代理时要先配好
 * 可信代理，否则所有请求的地址都是代理的地址。算不出地址的请求，黑名单拦不住，白名单和不对外开放的路径一律不放行。
 *
 * <p>配置写错（地址段或路径前缀不合法）时拒绝启动，不忽略：白名单里唯一的一条写错了被忽略，白名单就成了空的，
 * 等于谁都能访问。
 *
 * <p>管的是转发。网关自己的健康检查（{@code /_gateway/health}）不经过这里：负载均衡探活用的地址通常不在白名单里，
 * 拦了网关会被当成不健康而摘掉。
 */
@Component
public class ClientAccessFilter implements GlobalFilter, Ordered {

	/**
	 * 排在路由算出转发地址之后（改写后的路径这时才有）、选实例之前（不转发的请求不用去选）。
	 * 内部接口检查（{@link InnerPathGuardFilter}）排在 +1，比这里先：带上级目录段的路径到这里之前已经被它拒绝。
	 */
	public static final int ORDER = RouteToRequestUrlFilter.ROUTE_TO_URL_FILTER_ORDER + 2;

	private static final Logger log = LoggerFactory.getLogger(ClientAccessFilter.class);
	private static final int MAX_LOGGED_PATH = 200;

	private final ClientViewResolver clientViewResolver;
	private final List<IpCidr> denied;
	private final List<IpCidr> allowed;
	private final PathPrefixes restrictedPaths;
	private final List<IpCidr> restrictedAllowed;

	public ClientAccessFilter(GatewayGuardProperties properties, ClientViewResolver clientViewResolver) {
		this.clientViewResolver = clientViewResolver;
		this.denied = cidrs(properties.getDenyIps(), "mugsun.gateway.deny-ips");
		this.allowed = cidrs(properties.getAllowIps(), "mugsun.gateway.allow-ips");
		this.restrictedPaths = PathPrefixes.parse(properties.getRestrictedPaths(), "mugsun.gateway.restricted-paths");
		this.restrictedAllowed = cidrs(properties.getRestrictedAllowIps(), "mugsun.gateway.restricted-allow-ips");
		if (active()) {
			log.info("按客户端地址限制转发：黑名单 {} 段，白名单 {} 段，不对外开放的路径 {} 个（能访问的地址 {} 段）",
				denied.size(), allowed.size(), restrictedPaths.size(), restrictedAllowed.size());
		}
	}

	@Override
	public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
		if (!active()) {
			return chain.filter(exchange);
		}
		String clientIp = clientViewResolver.resolve(exchange.getRequest()).clientIp();
		if (IpCidr.anyContains(denied, clientIp)) {
			return reject(exchange, clientIp, "地址在黑名单里", "当前网络地址不允许访问");
		}
		if (!allowed.isEmpty() && !IpCidr.anyContains(allowed, clientIp)) {
			return reject(exchange, clientIp, "地址不在白名单里", "当前网络地址不允许访问");
		}
		if (!restrictedPaths.isEmpty() && !IpCidr.anyContains(restrictedAllowed, clientIp)
			&& touchesRestrictedPath(exchange)) {
			return reject(exchange, clientIp, "路径不对外开放", "这个地址不对外开放");
		}
		return chain.filter(exchange);
	}

	private boolean active() {
		return !denied.isEmpty() || !allowed.isEmpty() || !restrictedPaths.isEmpty();
	}

	private boolean touchesRestrictedPath(ServerWebExchange exchange) {
		for (String path : RequestPaths.of(exchange)) {
			if (restrictedPaths.contains(path)) {
				return true;
			}
		}
		return false;
	}

	private static Mono<Void> reject(ServerWebExchange exchange, String clientIp, String why, String message) {
		// 每个被拒绝的请求在访问日志里已经有一行（status=403，带客户端地址）。这里只在调试级别记是哪条规则拒绝的：
		// 黑名单里的地址可能还在不停地发请求，按警告级别记会把日志刷满
		if (log.isDebugEnabled()) {
			String path = exchange.getRequest().getURI().getRawPath();
			if (path != null && path.length() > MAX_LOGGED_PATH) {
				path = path.substring(0, MAX_LOGGED_PATH) + "...";
			}
			log.debug("不转发（{}）：{} {}，客户端 {}，请求编号 {}", why, exchange.getRequest().getMethod(), path, clientIp,
				TraceIds.of(exchange));
		}
		return Mono.error(new GatewayRejectedException(HttpStatus.FORBIDDEN, message));
	}

	/**
	 * 解析地址段列表，有一条写错就报错。不用 {@link IpCidr#parseList}：它会忽略写错的条目。
	 */
	static List<IpCidr> cidrs(String text, String property) {
		List<IpCidr> result = new ArrayList<>();
		if (text != null) {
			for (String item : text.split("[,;\\s]+")) {
				if (item.isEmpty()) {
					continue;
				}
				IpCidr cidr = IpCidr.parse(item);
				if (cidr == null) {
					throw new IllegalArgumentException(property + " 里的地址段不合法：" + item
						+ "。要写成地址或 CIDR，例如 203.0.113.7 或 10.0.0.0/8");
				}
				result.add(cidr);
			}
		}
		return List.copyOf(result);
	}

	@Override
	public int getOrder() {
		return ORDER;
	}
}
