package com.mugsun.cloud.gateway.filter;

import com.mugsun.cloud.gateway.error.GatewayRejectedException;
import com.mugsun.cloud.gateway.filter.AccessLogFilter.End;
import com.mugsun.cloud.gateway.lane.LaneNames;
import com.mugsun.cloud.gateway.props.GatewayGuardProperties;
import com.mugsun.cloud.gateway.props.GatewayGuardProperties.AccessLogMode;
import com.mugsun.cloud.gateway.support.ClientViewResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.cloud.client.DefaultServiceInstance;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.loadbalancer.DefaultResponse;
import org.springframework.cloud.client.loadbalancer.EmptyResponse;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.net.ConnectException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 访问日志里的状态码、结束方式、级别和一行的写法，以及记哪些请求。经过网关的完整过程在 GatewayAccessLogTest 里测。
 */
@ExtendWith(OutputCaptureExtension.class)
class AccessLogFilterTest {

	@Test
	void statusIsWhatTheClientGets() {
		// 响应已经开始写：就是写出去的那个，不管后来怎么结束
		assertThat(AccessLogFilter.statusOf(HttpStatus.OK, true, End.OK, null)).isEqualTo(200);
		assertThat(AccessLogFilter.statusOf(HttpStatus.OK, true, End.ERROR, new IOException("reset"))).isEqualTo(200);
		assertThat(AccessLogFilter.statusOf(HttpStatus.SWITCHING_PROTOCOLS, true, End.CLOSED, null)).isEqualTo(101);
		// 还没写就出错：出错处理即将返回的那个
		assertThat(AccessLogFilter.statusOf(null, false, End.ERROR, new ConnectException("refused"))).isEqualTo(502);
		assertThat(AccessLogFilter.statusOf(HttpStatus.OK, false, End.ERROR,
			new GatewayRejectedException(HttpStatus.NOT_FOUND, "资源不存在"))).isEqualTo(404);
		// 还没写客户端就断开了
		assertThat(AccessLogFilter.statusOf(null, false, End.CLOSED, null)).isEqualTo(499);
		// 正常结束但没人设过状态码
		assertThat(AccessLogFilter.statusOf(null, false, End.OK, null)).isEqualTo(200);
	}

	@Test
	void rejectionByGatewayIsANormalEnd() {
		assertThat(AccessLogFilter.endOf(End.ERROR, false, 404)).isEqualTo(End.OK);
		assertThat(AccessLogFilter.endOf(End.ERROR, false, 403)).isEqualTo(End.OK);
		// 转发没成
		assertThat(AccessLogFilter.endOf(End.ERROR, false, 502)).isEqualTo(End.ERROR);
		// 响应写到一半断了
		assertThat(AccessLogFilter.endOf(End.ERROR, true, 200)).isEqualTo(End.ERROR);
		assertThat(AccessLogFilter.endOf(End.CLOSED, false, 499)).isEqualTo(End.CLOSED);
		assertThat(AccessLogFilter.endOf(End.OK, true, 200)).isEqualTo(End.OK);
	}

	@Test
	void warnsOnServerErrorsBrokenForwardsAndSlowResponses() {
		assertThat(AccessLogFilter.warns(200, End.OK, 12, 3000)).isFalse();
		assertThat(AccessLogFilter.warns(404, End.OK, 12, 3000)).isFalse();
		assertThat(AccessLogFilter.warns(499, End.CLOSED, 12, 3000)).isFalse();

		assertThat(AccessLogFilter.warns(500, End.OK, 12, 3000)).isTrue();
		assertThat(AccessLogFilter.warns(502, End.ERROR, 12, 3000)).isTrue();
		assertThat(AccessLogFilter.warns(200, End.ERROR, 12, 3000)).isTrue();
		assertThat(AccessLogFilter.warns(200, End.OK, 3000, 3000)).isTrue();
		assertThat(AccessLogFilter.warns(499, End.CLOSED, 8000, 3000)).isTrue();
	}

	@Test
	void slowThresholdOfZeroTurnsTheSlowRuleOff() {
		assertThat(AccessLogFilter.warns(200, End.OK, 600_000, 0)).isFalse();
		assertThat(AccessLogFilter.warns(500, End.OK, 1, 0)).isTrue();
	}

	@Test
	void valuesFromTheRequestCannotBreakTheLine() {
		assertThat(AccessLogFilter.clean("/a b\r\nc=d", 200)).isEqualTo("/a?b??c=d");
		assertThat(AccessLogFilter.clean("/路径", 200)).isEqualTo("/??");
		assertThat(AccessLogFilter.clean("tab\there\u007f", 200)).isEqualTo("tab?here?");
		assertThat(AccessLogFilter.clean(null, 10)).isEqualTo("-");
		assertThat(AccessLogFilter.clean("", 10)).isEqualTo("-");
		assertThat(AccessLogFilter.clean("abcdef", 3)).isEqualTo("abc...");
		assertThat(AccessLogFilter.clean("abc", 3)).isEqualTo("abc");
	}

	@Test
	void lineHasTheSameFieldsEveryTime() {
		assertThat(AccessLogFilter.line("203.0.113.7", "GET", "/system/user/list", 200, 41, "38", "mugsun-system",
			"10.0.0.12:8101", null, End.OK, "abc"))
			.isEqualTo("ip=203.0.113.7 method=GET uri=/system/user/list status=200 ms=41 ttfb=38 route=mugsun-system"
				+ " to=10.0.0.12:8101 lane=- end=ok trace=abc");
		assertThat(AccessLogFilter.line("", "GET", "/x", 499, 5, "-", null, null, null, End.CLOSED, "abc"))
			.isEqualTo("ip=- method=GET uri=/x status=499 ms=5 ttfb=- route=- to=- lane=- end=closed trace=abc");
		assertThat(AccessLogFilter.line("10.0.0.1", "POST", "/dead", 502, 3001, "3001", "dead", "10.0.0.9:80",
			"canary>base", End.ERROR, "abc"))
			.endsWith(" to=10.0.0.9:80 lane=canary>base end=error trace=abc");
	}

	/** 灰度字段：没有标记是空的；带着标记、选中的却是正式实例时要看得出来 */
	@Test
	void laneFieldTellsWhetherAGreyInstanceServedTheRequest() {
		MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/x"));
		assertThat(AccessLogFilter.laneOf(exchange)).isNull();

		// 带着标记，还没有选实例（比如注册中心里没有能发的实例）
		exchange.getAttributes().put(LaneNames.LANE_ATTR, "canary");
		assertThat(AccessLogFilter.laneOf(exchange)).isEqualTo("canary");

		// 负载均衡留下的是「一个实例都没选中」：照样记得下来，也不能当成转给了正式实例
		exchange.getAttributes().put(ServerWebExchangeUtils.GATEWAY_LOADBALANCER_RESPONSE_ATTR, new EmptyResponse());
		assertThat(AccessLogFilter.laneOf(exchange)).isEqualTo("canary");

		exchange.getAttributes().put(ServerWebExchangeUtils.GATEWAY_LOADBALANCER_RESPONSE_ATTR,
			new DefaultResponse(instance(Map.of("lane", "canary"))));
		assertThat(AccessLogFilter.laneOf(exchange)).isEqualTo("canary");

		exchange.getAttributes().put(ServerWebExchangeUtils.GATEWAY_LOADBALANCER_RESPONSE_ATTR,
			new DefaultResponse(instance(Map.of())));
		assertThat(AccessLogFilter.laneOf(exchange)).isEqualTo("canary>base");

		// 没有标记的请求转给正式实例：就是空的，不写 base
		exchange.getAttributes().remove(LaneNames.LANE_ATTR);
		assertThat(AccessLogFilter.laneOf(exchange)).isNull();
	}

	private static ServiceInstance instance(Map<String, String> metadata) {
		return new DefaultServiceInstance("gis-1", "mugsun-gis", "10.0.0.12", 8102, false, metadata);
	}

	@Test
	void modeAllWritesEveryRequest(CapturedOutput output) {
		run(AccessLogMode.ALL, "/mode-all?token=secret-value&page=query-marker", answering(HttpStatus.OK));

		assertThat(lineFor(output, "/mode-all")).contains(" status=200 ").contains(" end=ok ");
		// 日志写出去之前 token= 后面的值会被换成星号（LogSecretsMasking），只找令牌的值看不出查询串有没有被记下来，
		// 所以另带一个参数：查询串要是被记了，它会原样出现
		assertThat(output.getAll()).doesNotContain("secret-value").doesNotContain("query-marker");
	}

	@Test
	void modeProblemsWritesOnlyWhatWouldBeAWarning(CapturedOutput output) {
		run(AccessLogMode.PROBLEMS, "/mode-problems-fine", answering(HttpStatus.OK));
		run(AccessLogMode.PROBLEMS, "/mode-problems-broken", answering(HttpStatus.INTERNAL_SERVER_ERROR));

		assertThat(lineFor(output, "/mode-problems-fine")).isNull();
		assertThat(lineFor(output, "/mode-problems-broken")).contains(" status=500 ");
	}

	@Test
	void modeNoneWritesNothing(CapturedOutput output) {
		run(AccessLogMode.NONE, "/mode-none", answering(HttpStatus.INTERNAL_SERVER_ERROR));

		assertThat(lineFor(output, "/mode-none")).isNull();
	}

	@Test
	void gatewayHealthChecksAreWrittenOnlyWhenTheyFail(CapturedOutput output) {
		run(AccessLogMode.ALL, "/_gateway/health", answering(HttpStatus.OK));
		assertThat(lineFor(output, "/_gateway/health")).isNull();

		run(AccessLogMode.ALL, "/_gateway/health", answering(HttpStatus.SERVICE_UNAVAILABLE));
		assertThat(lineFor(output, "/_gateway/health")).contains(" status=503 ");

		// 只是开头像，不是网关自己的地址
		run(AccessLogMode.ALL, "/_gatewayx", answering(HttpStatus.OK));
		assertThat(lineFor(output, "/_gatewayx")).contains(" status=200 ");
	}

	@Test
	void failedForwardIsWrittenWithTheStatusTheErrorHandlerWillReturn(CapturedOutput output) {
		run(AccessLogMode.ALL, "/forward-failed", exchange -> Mono.error(new ConnectException("refused")));

		assertThat(lineFor(output, "/forward-failed")).contains(" status=502 ").contains(" end=error ");
	}

	@Test
	void requestRejectedByGatewayEndsNormally(CapturedOutput output) {
		run(AccessLogMode.ALL, "/rejected",
			exchange -> Mono.error(new GatewayRejectedException(HttpStatus.FORBIDDEN, "不允许访问")));

		assertThat(lineFor(output, "/rejected")).contains(" status=403 ").contains(" end=ok ");
	}

	@Test
	void clientLeavingBeforeAnyResponseIsWrittenAs499(CapturedOutput output) {
		MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/left-early"));
		filter(AccessLogMode.ALL).filter(exchange, current -> Mono.never()).subscribe().dispose();

		assertThat(lineFor(output, "/left-early")).contains(" status=499 ").contains(" ttfb=- ")
			.contains(" end=closed ");
	}

	private static WebFilterChain answering(HttpStatus status) {
		return exchange -> {
			exchange.getResponse().setStatusCode(status);
			return Mono.empty();
		};
	}

	private static void run(AccessLogMode mode, String pathAndQuery, WebFilterChain chain) {
		MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get(pathAndQuery));
		filter(mode).filter(exchange, chain).onErrorResume(error -> Mono.empty()).block();
	}

	private static AccessLogFilter filter(AccessLogMode mode) {
		GatewayGuardProperties properties = new GatewayGuardProperties();
		properties.setAccessLog(mode);
		ServerProperties server = new ServerProperties();
		server.setForwardHeadersStrategy(ServerProperties.ForwardHeadersStrategy.NONE);
		return new AccessLogFilter(new ClientViewResolver(properties, server), properties, "/_gateway");
	}

	/** 这次测试里记下的、路径是 path 的最后一行访问日志；没有返回 null */
	private static String lineFor(CapturedOutput output, String path) {
		return output.getOut().lines().filter(line -> line.contains(" uri=" + path + " "))
			.reduce((first, second) -> second).orElse(null);
	}
}
