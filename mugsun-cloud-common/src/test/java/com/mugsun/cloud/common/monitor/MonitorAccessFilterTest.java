package com.mugsun.cloud.common.monitor;

import com.mugsun.boot.monitor.ActuatorAuthFilter;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.annotation.Order;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.security.Principal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MonitorAccessFilterTest {

	/** 登录这一关被问到了哪些地址 */
	private final List<String> askedLoginGate = new ArrayList<>();

	/** 登录这一关放不放行，不放行时回 401 */
	private boolean loggedIn = true;

	private final Filter loginGate = (request, response, chain) -> {
		askedLoginGate.add(((HttpServletRequest) request).getRequestURI());
		if (loggedIn) {
			chain.doFilter(request, response);
		} else {
			((HttpServletResponse) response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
		}
	};

	private final MonitorAccessFilter filter = new MonitorAccessFilter(loginGate);

	/** 请求到没到后面的接口；到了的话记下它带的身份，没有身份记成 "无" */
	private final List<String> reached = new ArrayList<>();

	private final FilterChain chain = (request, response) -> {
		Principal principal = ((HttpServletRequest) request).getUserPrincipal();
		reached.add(principal == null ? "无" : principal.getName());
	};

	@ParameterizedTest
	@ValueSource(strings = {"/actuator", "/actuator/health", "/actuator/env", "/actuator/heapdump", "/actuator/threaddump",
		"/actuator/loggers/com.mugsun", "/actuator/metrics/jvm.memory.used"})
	void directCallSeesEveryEndpointWithoutLogin(String path) throws Exception {
		MockHttpServletResponse response = call(path, null);

		assertThat(reached).containsExactly("monitor");
		assertThat(askedLoginGate).isEmpty();
		assertThat(response.getStatus()).isEqualTo(200);
	}

	@ParameterizedTest
	@ValueSource(strings = {"/actuator", "/actuator/health", "/actuator/health/liveness", "/actuator/info", "/actuator/metrics",
		"/actuator/metrics/jvm.memory.used", "/actuator/prometheus"})
	void throughGatewayTheOldEndpointsStillGoThroughTheLoginGate(String path) throws Exception {
		MockHttpServletResponse response = call(path, "X-Forwarded-Proto");

		assertThat(askedLoginGate).containsExactly(path);
		assertThat(reached).containsExactly("无");
		assertThat(response.getStatus()).isEqualTo(200);
	}

	@ParameterizedTest
	@ValueSource(strings = {"/actuator/env", "/actuator/heapdump", "/actuator/threaddump", "/actuator/loggers/ROOT", "/actuator/beans",
		"/actuator/healthx", "/actuator/metricsx/a", "/actuator/env;a=b", "/actuator//env"})
	void throughGatewayTheNewEndpointsDoNotExist(String path) throws Exception {
		MockHttpServletResponse response = call(path, "X-Forwarded-Proto");

		assertThat(askedLoginGate).containsExactly(path);
		assertThat(reached).isEmpty();
		assertThat(response.getStatus()).isEqualTo(404);
	}

	@ParameterizedTest
	@ValueSource(strings = {"/actuator/metrics", "/actuator/env"})
	void throughGatewayWithoutLoginIsRejectedFirst(String path) throws Exception {
		loggedIn = false;

		MockHttpServletResponse response = call(path, "X-Forwarded-Proto");

		assertThat(reached).isEmpty();
		assertThat(response.getStatus()).isEqualTo(401);
	}

	@ParameterizedTest
	@ValueSource(strings = {"X-Forwarded-For", "X-Forwarded-Proto", "X-Forwarded-Port", "X-Forwarded-Host", "x-forwarded-prefix",
		"X-FORWARDED-PROTO", "Forwarded", "X-Real-IP"})
	void anyForwardingHeaderMeansThroughGateway(String header) throws Exception {
		MockHttpServletResponse response = call("/actuator/env", header);

		assertThat(reached).isEmpty();
		assertThat(response.getStatus()).isEqualTo(404);
	}

	@ParameterizedTest
	@ValueSource(strings = {"/ping", "/system/user/list", "/actuatorx", "/actuatorx/env"})
	void otherPathsAreLeftAlone(String path) throws Exception {
		call(path, null);
		call(path, "X-Forwarded-Proto");

		assertThat(reached).containsExactly("无", "无");
		assertThat(askedLoginGate).isEmpty();
	}

	/** 同步来的过滤器改了排序号时，这里要跟着改：排到登录态准备好之前，经网关来的请求会全部被当成没登录 */
	@Test
	void sameOrderAsTheFilterItStandsInFor() {
		Order order = AnnotationUtils.findAnnotation(ActuatorAuthFilter.class, Order.class);

		assertThat(order).isNotNull();
		assertThat(order.value()).isEqualTo(MonitorAccessFilter.ORDER);
	}

	private MockHttpServletResponse call(String path, String forwardingHeader) throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
		if (forwardingHeader != null) {
			request.addHeader(forwardingHeader, "http");
		}
		MockHttpServletResponse response = new MockHttpServletResponse();
		filter.doFilter(request, response, chain);
		return response;
	}
}
