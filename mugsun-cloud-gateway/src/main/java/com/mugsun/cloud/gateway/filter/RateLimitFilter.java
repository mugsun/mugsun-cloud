package com.mugsun.cloud.gateway.filter;

import com.mugsun.boot.common.web.IpCidr;
import com.mugsun.cloud.gateway.error.GatewayRejectedException;
import com.mugsun.cloud.gateway.props.GatewayGuardProperties;
import com.mugsun.cloud.gateway.ratelimit.RateLimitRules;
import com.mugsun.cloud.gateway.ratelimit.RateLimiter;
import com.mugsun.cloud.gateway.ratelimit.RejectionReport;
import com.mugsun.cloud.gateway.support.ClientView;
import com.mugsun.cloud.gateway.support.ClientViewResolver;
import com.mugsun.cloud.gateway.support.IpRanges;
import com.mugsun.cloud.gateway.support.RequestPaths;
import com.mugsun.cloud.gateway.support.TraceIds;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;
import java.util.function.LongSupplier;

/**
 * 限流：按客户端地址数请求，超过限额的不转发，返回 429，响应头 {@code Retry-After} 写明过几秒再试。
 *
 * <ul>
 *   <li>默认限额（{@code mugsun.gateway.rate-limit}）：没有单独定限额的路径都用它</li>
 *   <li>单独定的限额（{@code mugsun.gateway.rate-limit-paths}）：给个别路径前缀定得更严、更松或者不限流</li>
 *   <li>不限流的地址（{@code mugsun.gateway.rate-limit-exempt-ips}）</li>
 * </ul>
 *
 * <p>三项默认都是空的，不配就不限流；写错了拒绝启动。一个请求只算在一条规则上，见 {@link RateLimitRules}；
 * 计数放在这个网关实例的内存里，为什么、代价是什么，见 {@link RateLimiter}。
 *
 * <p>客户端地址用 {@link ClientViewResolver} 算。网关前面有反向代理时要先配好可信代理，否则所有请求都算在代理的地址上，
 * 很快就全部被拒绝。
 *
 * <p>管的是转发。网关自己的健康检查、由网关自己应答的跨域预检、没有路由可转的请求不经过这里，不计数。
 */
@Component
public class RateLimitFilter implements GlobalFilter, Ordered {

	/**
	 * 排在按客户端地址限制转发（{@link ClientAccessFilter}）之后：黑名单里的地址、不对外开放的路径先被拒绝，不占限额。
	 * 也在选实例之前：超限额的请求不用去选。
	 */
	public static final int ORDER = ClientAccessFilter.ORDER + 1;

	/** 返回给调用方的说明，和单体版限流时用的是同一句 */
	static final String MESSAGE = "请求过于频繁，请稍后再试";

	private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);
	private static final String TRUSTED_PROXIES = "mugsun.gateway.trusted-proxies，环境变量 MUGSUN_OPENAPI_TRUSTED_PROXIES";
	private static final int MAX_LOGGED_PATH = 200;
	private static final long NANOS_PER_SECOND = 1_000_000_000L;

	private final ClientViewResolver clientViewResolver;
	private final RateLimitRules rules;
	private final List<IpCidr> exempt;
	private final RateLimiter limiter;
	private final RejectionReport report;
	private final boolean noTrustedProxies;

	@Autowired
	public RateLimitFilter(GatewayGuardProperties properties, ClientViewResolver clientViewResolver) {
		this(properties, clientViewResolver, System::nanoTime, Schedulers.parallel());
	}

	/**
	 * @param nanoClock       数请求用的时钟
	 * @param reportScheduler 到时间记限流汇总日志用的调度器
	 */
	RateLimitFilter(GatewayGuardProperties properties, ClientViewResolver clientViewResolver, LongSupplier nanoClock,
			Scheduler reportScheduler) {
		this.clientViewResolver = clientViewResolver;
		this.rules = RateLimitRules.parse(properties.getRateLimit(), "mugsun.gateway.rate-limit",
			properties.getRateLimitPaths(), "mugsun.gateway.rate-limit-paths");
		this.exempt = IpRanges.parse(properties.getRateLimitExemptIps(), "mugsun.gateway.rate-limit-exempt-ips");
		this.limiter = rules.isEmpty() ? null : new RateLimiter(nanoClock);
		this.report = new RejectionReport(reportScheduler);
		this.noTrustedProxies = !clientViewResolver.hasTrustedProxies();
		if (!rules.isEmpty()) {
			log.info("限流已启用，按客户端地址数请求，计数在这个网关实例的内存里：默认限额 {}，单独定了限额的路径前缀 {} 个，"
					+ "不限流的地址 {} 段{}",
				rules.defaultRule() == null ? "没有" : rules.defaultRule().quota(), rules.pathRuleCount(), exempt.size(),
				noTrustedProxies
					? "。没有配可信代理（" + TRUSTED_PROXIES + "），客户端地址按直接连上网关的地址算；"
						+ "网关前面有反向代理的话必须配，否则所有请求都算在代理的地址上"
					: "");
		}
	}

	@Override
	public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
		if (limiter == null) {
			return chain.filter(exchange);
		}
		String clientIp = clientViewResolver.resolve(exchange.getRequest()).clientIp();
		if (IpCidr.anyContains(exempt, clientIp)) {
			return chain.filter(exchange);
		}
		RateLimitRules.Rule rule = rules.ruleFor(RequestPaths.of(exchange));
		if (rule == null || rule.unlimited()) {
			return chain.filter(exchange);
		}
		String client = clientKey(clientIp);
		long waitNanos = limiter.tryAcquire(rule, client);
		if (waitNanos == 0) {
			return chain.filter(exchange);
		}
		// 每个被拒绝的请求在访问日志里已经有一行（status=429，带客户端地址）。这里另外记的警告是限了条数的，不会把日志刷满
		report.rejected(rule.toString(), client.isEmpty() ? "（算不出地址）" : client, () -> describe(exchange));
		HttpHeaders headers = new HttpHeaders();
		headers.set(HttpHeaders.RETRY_AFTER, Long.toString((waitNanos + NANOS_PER_SECOND - 1) / NANOS_PER_SECOND));
		return Mono.error(new GatewayRejectedException(HttpStatus.TOO_MANY_REQUESTS, MESSAGE, headers));
	}

	/**
	 * 按什么给客户端分桶：IPv4 按单个地址；IPv6 按 /64——一个 IPv6 用户通常拿到一整个 /64，按单个地址算的话换个地址就绕过去了。
	 * 算不出地址的请求（空串）共用一个桶。
	 */
	static String clientKey(String clientIp) {
		byte[] bytes = IpRanges.bytes(clientIp);
		if (bytes == null || bytes.length != 16) {
			return clientIp == null ? "" : clientIp;
		}
		Arrays.fill(bytes, 8, 16, (byte) 0);
		try {
			return InetAddress.getByAddress(bytes).getHostAddress() + "/64";
		} catch (UnknownHostException e) {
			return clientIp;
		}
	}

	private String describe(ServerWebExchange exchange) {
		String path = AccessLogFilter.clean(exchange.getRequest().getURI().getRawPath(), MAX_LOGGED_PATH);
		String text = exchange.getRequest().getMethod() + " " + path + "，请求编号 " + TraceIds.of(exchange);
		if (noTrustedProxies && exchange.getRequest().getHeaders().containsKey(ClientView.X_FORWARDED_FOR)) {
			text += "。这个请求带着 X-Forwarded-For，而可信代理（" + TRUSTED_PROXIES + "）没有配："
				+ "网关前面有反向代理的话，所有请求都被算在了代理这一个地址上";
		}
		return text;
	}

	@Override
	public int getOrder() {
		return ORDER;
	}
}
