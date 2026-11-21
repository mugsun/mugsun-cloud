package com.mugsun.cloud.gateway.filter;

import com.mugsun.cloud.gateway.error.GatewayRejectedException;
import com.mugsun.cloud.gateway.filter.AccessLogFilter.End;
import com.mugsun.cloud.gateway.props.GatewayGuardProperties;
import com.mugsun.cloud.gateway.props.GatewayGuardProperties.AccessLog.Mode;
import com.mugsun.cloud.gateway.support.ClientViewResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.net.ConnectException;

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
			"10.0.0.12:8101", End.OK, "abc"))
			.isEqualTo("ip=203.0.113.7 method=GET uri=/system/user/list status=200 ms=41 ttfb=38 route=mugsun-system"
				+ " to=10.0.0.12:8101 end=ok trace=abc");
		assertThat(AccessLogFilter.line("", "GET", "/x", 499, 5, "-", null, null, End.CLOSED, "abc"))
			.isEqualTo("ip=- method=GET uri=/x status=499 ms=5 ttfb=- route=- to=- end=closed trace=abc");
		assertThat(AccessLogFilter.line("10.0.0.1", "POST", "/dead", 502, 3001, "3001", "dead", "10.0.0.9:80",
			End.ERROR, "abc"))
			.endsWith(" end=error trace=abc");
	}

	@Test
	void modeAllWritesEveryRequest(CapturedOutput output) {
		run(Mode.ALL, "/mode-all?token=secret-value", answering(HttpStatus.OK));

		assertThat(lineFor(output, "/mode-all")).contains(" status=200 ").contains(" end=ok ");
		assertThat(output.getAll()).doesNotContain("secret-value");
	}

	@Test
	void modeProblemsWritesOnlyWhatWouldBeAWarning(CapturedOutput output) {
		run(Mode.PROBLEMS, "/mode-problems-fine", answering(HttpStatus.OK));
		run(Mode.PROBLEMS, "/mode-problems-broken", answering(HttpStatus.INTERNAL_SERVER_ERROR));

		assertThat(lineFor(output, "/mode-problems-fine")).isNull();
		assertThat(lineFor(output, "/mode-problems-broken")).contains(" status=500 ");
	}

	@Test
	void modeNoneWritesNothing(CapturedOutput output) {
		run(Mode.NONE, "/mode-none", answering(HttpStatus.INTERNAL_SERVER_ERROR));

		assertThat(lineFor(output, "/mode-none")).isNull();
	}

	@Test
	void gatewayHealthChecksAreWrittenOnlyWhenTheyFail(CapturedOutput output) {
		run(Mode.ALL, "/_gateway/health", answering(HttpStatus.OK));
		assertThat(lineFor(output, "/_gateway/health")).isNull();

		run(Mode.ALL, "/_gateway/health", answering(HttpStatus.SERVICE_UNAVAILABLE));
		assertThat(lineFor(output, "/_gateway/health")).contains(" status=503 ");

		// 只是开头像，不是网关自己的地址
		run(Mode.ALL, "/_gatewayx", answering(HttpStatus.OK));
		assertThat(lineFor(output, "/_gatewayx")).contains(" status=200 ");
	}

	@Test
	void failedForwardIsWrittenWithTheStatusTheErrorHandlerWillReturn(CapturedOutput output) {
		run(Mode.ALL, "/forward-failed", exchange -> Mono.error(new ConnectException("refused")));

		assertThat(lineFor(output, "/forward-failed")).contains(" status=502 ").contains(" end=error ");
	}

	@Test
	void requestRejectedByGatewayEndsNormally(CapturedOutput output) {
		run(Mode.ALL, "/rejected",
			exchange -> Mono.error(new GatewayRejectedException(HttpStatus.FORBIDDEN, "不允许访问")));

		assertThat(lineFor(output, "/rejected")).contains(" status=403 ").contains(" end=ok ");
	}

	@Test
	void clientLeavingBeforeAnyResponseIsWrittenAs499(CapturedOutput output) {
		MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/left-early"));
		filter(Mode.ALL).filter(exchange, current -> Mono.never()).subscribe().dispose();

		assertThat(lineFor(output, "/left-early")).contains(" status=499 ").contains(" ttfb=- ")
			.contains(" end=closed ");
	}

	private static WebFilterChain answering(HttpStatus status) {
		return exchange -> {
			exchange.getResponse().setStatusCode(status);
			return Mono.empty();
		};
	}

	private static void run(Mode mode, String pathAndQuery, WebFilterChain chain) {
		MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get(pathAndQuery));
		filter(mode).filter(exchange, chain).onErrorResume(error -> Mono.empty()).block();
	}

	private static AccessLogFilter filter(Mode mode) {
		GatewayGuardProperties properties = new GatewayGuardProperties();
		properties.getAccessLog().setMode(mode);
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
