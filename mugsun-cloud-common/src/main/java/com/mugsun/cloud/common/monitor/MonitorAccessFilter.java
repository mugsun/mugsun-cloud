package com.mugsun.cloud.common.monitor;

import com.mugsun.boot.monitor.ActuatorAuthFilter;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

import java.io.IOException;
import java.security.Principal;
import java.util.Enumeration;
import java.util.Locale;
import java.util.Set;

/**
 * 决定 {@code /actuator} 下面的监控接口谁能访问。照的是 BladeX 的做法：监控接口全部打开，内网里直接连服务端口的不用登录；
 * 从外面经网关来的，看得到的和以前一样。
 *
 * <p><b>直接连上来的</b>是监控中心（{@code mugsun-cloud-admin}）：它从 Nacos 拿到每个实例的地址，直接请求实例的监控接口，
 * 页面上的内存、线程、日志级别都从这里来。服务的端口只有内网连得到，所以不要登录。
 * 健康检查的明细（数据库、Redis 各自怎么样）也只给它看。
 *
 * <p><b>经网关来的</b>只开放原来就有的那几个（{@link #THROUGH_GATEWAY}），其中哪些要登录、要什么权限，
 * 仍然由同步来的 {@link ActuatorAuthFilter} 判断，管理后台的监控页照旧能用；其余的按没有这个接口处理。
 * BladeX 的网关把 {@code /actuator} 整个拦掉；这里多留这几个，是因为管理后台的监控页在用。
 *
 * <p>怎么分辨：网关给每个转发的请求都写 {@code X-Forwarded-Proto} 和 {@code X-Forwarded-Port}，客户端自己带的转发头
 * 在网关就被删掉重写了（网关的 {@code ClientAddressFilter}），所以经网关来的请求一定带转发头，客户端去不掉。
 * 带任何一个转发头的都按经网关来的处理，一个都不带的才算直接连上来的。
 * Tomcat 采信转发头的时候只拿走 {@code X-Forwarded-For}，另外几个原样留着，到这里还看得到。
 *
 * <p>{@link ActuatorAuthFilter} 不再由容器直接调用，只在这里按需调用，见 {@link MonitorAccessConfiguration}。
 */
public class MonitorAccessFilter extends OncePerRequestFilter {

	/** 和 {@link ActuatorAuthFilter} 原来的排序号一样：排在登录态准备好以后 */
	public static final int ORDER = -80;

	/** 经网关也能访问的监控接口，就是监控接口全部打开以前开放的那几个。空串是 {@code /actuator} 这个目录本身 */
	static final Set<String> THROUGH_GATEWAY = Set.of("", "health", "info", "metrics", "prometheus");

	private static final String ROOT = "/actuator";

	/** 和 {@link ActuatorAuthFilter} 用同一种办法算路径，两边对同一个请求的判断才一致 */
	private static final UrlPathHelper PATHS = UrlPathHelper.defaultInstance;

	/** 直接连上来的请求记成这个身份。健康检查的明细只给有身份的请求看（{@code show-details: when-authorized}） */
	private static final Principal MONITOR = () -> "monitor";

	/** 过了登录这一关、但这个接口不经网关开放时，按没有这个接口回 */
	private static final FilterChain NOT_FOUND =
		(request, response) -> ((HttpServletResponse) response).sendError(HttpServletResponse.SC_NOT_FOUND);

	private final Filter loginGate;

	/**
	 * @param loginGate 经网关来的请求要过的登录和权限这一关，运行时是 {@link ActuatorAuthFilter}
	 */
	public MonitorAccessFilter(Filter loginGate) {
		this.loginGate = loginGate;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
		throws ServletException, IOException {
		String endpoint = endpointOf(request);
		if (endpoint == null) {
			chain.doFilter(request, response);
			return;
		}
		if (!cameThroughGateway(request)) {
			chain.doFilter(new FromMonitor(request), response);
			return;
		}
		// 先过登录这一关，再说有没有这个接口：没登录的人问哪个地址得到的都是 401，和以前一样
		loginGate.doFilter(request, response, THROUGH_GATEWAY.contains(endpoint) ? chain : NOT_FOUND);
	}

	/**
	 * 请求的是哪个监控接口：{@code /actuator/metrics/jvm.memory.used} 是 {@code metrics}，{@code /actuator} 本身是空串。
	 * 不在 {@code /actuator} 下面的（包括 {@code /actuatorx} 这种只是开头一样的）返回 null。
	 */
	static String endpointOf(HttpServletRequest request) {
		String path = PATHS.getLookupPathForRequest(request);
		if (path.equals(ROOT)) {
			return "";
		}
		if (!path.startsWith(ROOT + "/")) {
			return null;
		}
		String rest = path.substring(ROOT.length() + 1);
		int slash = rest.indexOf('/');
		return slash < 0 ? rest : rest.substring(0, slash);
	}

	/** 带任何一个转发头就算经网关来的。认哪几个头和网关删掉重写的那几个一样 */
	static boolean cameThroughGateway(HttpServletRequest request) {
		Enumeration<String> names = request.getHeaderNames();
		while (names != null && names.hasMoreElements()) {
			String name = names.nextElement().toLowerCase(Locale.ROOT);
			if (name.startsWith("x-forwarded-") || name.equals("forwarded") || name.equals("x-real-ip")) {
				return true;
			}
		}
		return false;
	}

	private static final class FromMonitor extends HttpServletRequestWrapper {

		FromMonitor(HttpServletRequest request) {
			super(request);
		}

		@Override
		public Principal getUserPrincipal() {
			return MONITOR;
		}
	}
}
