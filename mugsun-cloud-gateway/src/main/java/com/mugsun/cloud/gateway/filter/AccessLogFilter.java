package com.mugsun.cloud.gateway.filter;

import com.mugsun.cloud.gateway.error.GatewayErrorHandler;
import com.mugsun.cloud.gateway.props.GatewayGuardProperties;
import com.mugsun.cloud.gateway.props.GatewayGuardProperties.AccessLogMode;
import com.mugsun.cloud.gateway.support.ClientViewResolver;
import com.mugsun.cloud.gateway.support.TraceIds;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 访问日志：每个请求结束时记一行，写明谁访问了什么、网关转给了哪个服务的哪个实例、结果和耗时。日志名是 {@code ACCESS}。
 *
 * <pre>
 * ip=203.0.113.7 method=GET uri=/system/user/list status=200 ms=41 ttfb=38 route=mugsun-system to=10.0.0.12:8081 end=ok trace=5f0c...
 * </pre>
 *
 * <ul>
 *   <li>{@code ip}：客户端地址，按可信代理的规则算出来的，和传给服务的是同一个</li>
 *   <li>{@code uri}：只记路径，不记查询串：WebSocket 的登录令牌在查询串里</li>
 *   <li>{@code status}：返回给客户端的状态码；客户端没等到回应就断开时记 499（沿用 Nginx 的记法）</li>
 *   <li>{@code ms}：从收到请求到请求结束（响应写完或者连接断开）的毫秒数</li>
 *   <li>{@code ttfb}：从收到请求到开始回应的毫秒数，还没回应客户端就断开时是 {@code -}</li>
 *   <li>{@code route}：命中的路由；{@code to}：转给了哪个实例（地址:端口）。没有转发时是 {@code -}</li>
 *   <li>{@code end}：{@code ok} 正常结束，{@code error} 转发出错，{@code closed} 客户端先断开了</li>
 *   <li>{@code trace}：请求编号，和服务日志里的是同一个，见 {@link TraceIdFilter}</li>
 * </ul>
 *
 * <p>5xx、转发出错、慢请求按警告级别记，其余按信息级别记。慢不慢看 {@code ttfb}，不看 {@code ms}：
 * 下载大文件、流式输出、WebSocket 连接本来就会持续很久。
 *
 * <p>记哪些请求由 {@code mugsun.gateway.access-log} 定：{@code all} 每个都记，{@code problems} 只记按警告级别记的那些，
 * {@code none} 不记。网关自己的健康检查每隔几秒就来一次，成功的不记，只记失败的。
 */
@Component
public class AccessLogFilter implements WebFilter, Ordered {

	/** 紧跟在定请求编号之后，包住后面所有的处理 */
	public static final int ORDER = TraceIdFilter.ORDER + 1;

	/** 客户端没等到回应就断开了 */
	static final int CLIENT_CLOSED = 499;

	private static final Logger ACCESS = LoggerFactory.getLogger("ACCESS");
	private static final int MAX_PATH = 200;
	private static final int MAX_FIELD = 64;

	enum End {
		OK("ok"), ERROR("error"), CLOSED("closed");

		private final String label;

		End(String label) {
			this.label = label;
		}
	}

	private final ClientViewResolver clientViewResolver;
	private final AccessLogMode mode;
	private final long slowMillis;
	private final String managementPath;

	public AccessLogFilter(ClientViewResolver clientViewResolver, GatewayGuardProperties properties,
			@Value("${management.endpoints.web.base-path:/actuator}") String managementPath) {
		this.clientViewResolver = clientViewResolver;
		this.mode = properties.getAccessLog();
		this.slowMillis = properties.getSlowMillis();
		String trimmed = managementPath == null ? "" : managementPath.trim();
		while (trimmed.endsWith("/")) {
			trimmed = trimmed.substring(0, trimmed.length() - 1);
		}
		this.managementPath = trimmed;
	}

	@Override
	public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
		if (mode == AccessLogMode.NONE || !ACCESS.isWarnEnabled()) {
			return chain.filter(exchange);
		}
		long startedAt = System.nanoTime();
		AtomicLong respondedAfter = new AtomicLong(-1);
		AtomicBoolean written = new AtomicBoolean();
		exchange.getResponse().beforeCommit(() -> {
			respondedAfter.compareAndSet(-1, millisSince(startedAt));
			return Mono.empty();
		});
		return chain.filter(exchange)
			.doOnSuccess(done -> write(exchange, startedAt, respondedAfter, written, End.OK, null))
			.doOnError(error -> write(exchange, startedAt, respondedAfter, written, End.ERROR, error))
			.doOnCancel(() -> write(exchange, startedAt, respondedAfter, written, End.CLOSED, null));
	}

	private void write(ServerWebExchange exchange, long startedAt, AtomicLong respondedAfter, AtomicBoolean written,
			End signal, Throwable error) {
		if (!written.compareAndSet(false, true)) {
			return;
		}
		try {
			long total = millisSince(startedAt);
			long responded = respondedAfter.get();
			boolean committed = exchange.getResponse().isCommitted();
			// WebSocket 握手成功时，101 是底层直接写出去的，响应对象上的状态码还是默认的 200
			HttpStatusCode responseStatus = committed && proxiedAsWebSocket(exchange)
				? HttpStatus.SWITCHING_PROTOCOLS
				: exchange.getResponse().getStatusCode();
			int status = statusOf(responseStatus, committed, signal, error);
			End end = endOf(signal, committed, status);
			ServerHttpRequest request = exchange.getRequest();
			String path = request.getURI().getRawPath();
			if (status < 400 && end == End.OK && underManagementPath(path)) {
				return;
			}
			// 还没开始回应就走到这里：正常结束和出错时响应马上就写，等待的时间就是到现在为止；客户端断开时没有回应
			long waited = responded >= 0 ? responded : total;
			String ttfb = responded < 0 && end == End.CLOSED ? "-" : Long.toString(waited);
			boolean warn = warns(status, end, waited, slowMillis);
			if (!warn && (mode == AccessLogMode.PROBLEMS || !ACCESS.isInfoEnabled())) {
				return;
			}
			String line = line(clientViewResolver.resolve(request).clientIp(), request.getMethod().name(), path, status,
				total, ttfb, routeOf(exchange), targetOf(exchange), end, TraceIds.of(exchange));
			if (warn) {
				ACCESS.warn(line);
			} else {
				ACCESS.info(line);
			}
		} catch (RuntimeException e) {
			// 记日志出错不能影响请求本身
			ACCESS.debug("访问日志没记上", e);
		}
	}

	/**
	 * 这次请求返回给客户端的状态码。响应已经开始写了，就是写出去的那个；还没写：出错时是出错处理即将返回的那个，
	 * 客户端已经断开时记 499。
	 */
	static int statusOf(HttpStatusCode responseStatus, boolean committed, End end, Throwable error) {
		if (!committed && end == End.ERROR && error != null) {
			return GatewayErrorHandler.statusOf(error).value();
		}
		if (!committed && end == End.CLOSED) {
			return CLIENT_CLOSED;
		}
		return responseStatus == null ? 200 : responseStatus.value();
	}

	/**
	 * 这次请求是怎么结束的。网关主动拒绝的请求（内部接口、不合法的地址）在程序里也是按出错往外抛的，
	 * 但那不是转发出了问题：响应还没开始写、状态码又是 4xx 的，算正常结束。
	 */
	static End endOf(End signal, boolean committed, int status) {
		return signal == End.ERROR && !committed && status < 500 ? End.OK : signal;
	}

	/** 这一行要不要按警告级别记：5xx、转发出错、等回应等得太久 */
	static boolean warns(int status, End end, long waitedMillis, long slowMillis) {
		return status >= 500 || end == End.ERROR || (slowMillis > 0 && waitedMillis >= slowMillis);
	}

	static String line(String ip, String method, String path, int status, long totalMillis, String ttfb, String route,
			String target, End end, String traceId) {
		return "ip=" + clean(ip, MAX_FIELD) + " method=" + clean(method, MAX_FIELD) + " uri=" + clean(path, MAX_PATH)
			+ " status=" + status + " ms=" + totalMillis + " ttfb=" + ttfb + " route=" + clean(route, MAX_FIELD)
			+ " to=" + clean(target, MAX_FIELD) + " end=" + end.label + " trace=" + clean(traceId, MAX_FIELD);
	}

	/**
	 * 写进日志前整理一下：空值记成 {@code -}；空格、换行、控制字符和非 ASCII 字符换成 {@code ?}，
	 * 一行日志就不会被请求里的内容拆成几行或者多出几个字段；太长的截断。
	 */
	static String clean(String value, int maxLength) {
		if (value == null || value.isEmpty()) {
			return "-";
		}
		int length = Math.min(value.length(), maxLength);
		StringBuilder out = new StringBuilder(length + 3);
		for (int i = 0; i < length; i++) {
			char c = value.charAt(i);
			out.append(c <= 0x20 || c >= 0x7f ? '?' : c);
		}
		if (value.length() > maxLength) {
			out.append("...");
		}
		return out.toString();
	}

	private boolean underManagementPath(String path) {
		if (managementPath.isEmpty() || path == null) {
			return false;
		}
		return path.equals(managementPath) || path.startsWith(managementPath + "/");
	}

	private static String routeOf(ServerWebExchange exchange) {
		Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
		return route == null ? null : route.getId();
	}

	/**
	 * 转给了哪个实例。只在网关确实动手转发了才有：请求在转发之前就被拒绝，或者注册中心里没有可用的实例时没有。
	 * 连不上换过实例的，记的是最后转给的那个。
	 */
	private static String targetOf(ServerWebExchange exchange) {
		URI target = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR);
		if (target == null || !ServerWebExchangeUtils.isAlreadyRouted(exchange)) {
			return null;
		}
		if (target.getHost() != null) {
			return target.getPort() > 0 ? target.getHost() + ":" + target.getPort() : target.getHost();
		}
		// 主机名里有下划线等字符时 URI 解析不出主机名，取整段地址；地址里写了账号密码的话去掉，不进日志
		String authority = target.getRawAuthority();
		return authority == null ? null : authority.substring(authority.lastIndexOf('@') + 1);
	}

	private static boolean proxiedAsWebSocket(ServerWebExchange exchange) {
		URI target = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR);
		if (target == null || !ServerWebExchangeUtils.isAlreadyRouted(exchange)) {
			return false;
		}
		return "ws".equalsIgnoreCase(target.getScheme()) || "wss".equalsIgnoreCase(target.getScheme());
	}

	private static long millisSince(long startedAt) {
		return Math.max(0, (System.nanoTime() - startedAt) / 1_000_000);
	}

	@Override
	public int getOrder() {
		return ORDER;
	}
}
