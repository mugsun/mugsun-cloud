package com.mugsun.cloud.gateway;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * 请求编号和访问日志：编号传给后端、写进响应头，客户端带来的编号只在写法合法时沿用；
 * 每个请求记一行访问日志，不带查询串，转发失败的按警告记。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
	"spring.cloud.nacos.discovery.enabled=false",
	"spring.cloud.nacos.config.enabled=false",
	"spring.cloud.service-registry.auto-registration.enabled=false"})
@ExtendWith(OutputCaptureExtension.class)
class GatewayAccessLogTest extends GatewayTestBase {

	private static final String GENERATED = "[0-9a-f]{32}";
	private static final String HEADER = "X-Trace-Id";

	@Test
	void gatewayAssignsTraceIdAndPassesItOn() {
		HttpHeaders headers = get("/system/user/list", null, 200);

		List<String> passedOn = RECEIVED.get(0).header(HEADER);
		assertThat(passedOn).hasSize(1);
		assertThat(passedOn.get(0)).matches(GENERATED);
		assertThat(headers.get(HEADER)).containsExactly(passedOn.get(0));
	}

	@ParameterizedTest
	@ValueSource(strings = {"order-20261121-0001", "0123456789012345678901234567890123456789012345678901234567890123"})
	void acceptableTraceIdFromClientIsKept(String traceId) {
		HttpHeaders headers = get("/ping", traceId, 200);

		assertThat(RECEIVED.get(0).header(HEADER)).containsExactly(traceId);
		assertThat(headers.get(HEADER)).containsExactly(traceId);
	}

	@ParameterizedTest
	@ValueSource(strings = {"has space", "a/b", "semi;colon", "under_score",
		"0123456789012345678901234567890123456789012345678901234567890123x"})
	void unacceptableTraceIdFromClientIsReplaced(String traceId) {
		HttpHeaders headers = get("/ping", traceId, 200);

		List<String> passedOn = RECEIVED.get(0).header(HEADER);
		assertThat(passedOn).hasSize(1);
		assertThat(passedOn.get(0)).matches(GENERATED);
		assertThat(headers.get(HEADER)).containsExactly(passedOn.get(0));
	}

	@Test
	void severalTraceIdsFromClientAreReplacedByOne() {
		client().get().uri(raw("/ping")).header(HEADER, "first-id", "second-id").exchange().expectStatus().isOk();

		List<String> passedOn = RECEIVED.get(0).header(HEADER);
		assertThat(passedOn).hasSize(1);
		assertThat(passedOn.get(0)).matches(GENERATED);
	}

	@Test
	void traceIdInResponseIsTheGatewaysEvenIfBackendWritesItsOwn() {
		HttpHeaders headers = get("/foreign/anything", "client-id-1", 200);

		assertThat(headers.get(HEADER)).containsExactly("client-id-1");
	}

	@Test
	void responsesProducedByGatewayCarryTraceId() {
		assertThat(get("/dead/anything", null, 502).get(HEADER)).singleElement().asString().matches(GENERATED);
		assertThat(get("/inner/message/send", "client-id-2", 404).get(HEADER)).containsExactly("client-id-2");

		HttpHeaders preflight = client().method(HttpMethod.OPTIONS).uri(raw("/system/user"))
			.header("Origin", "http://app.example.com")
			.header("Access-Control-Request-Method", "POST")
			.exchange().expectStatus().isOk().expectBody().returnResult().getResponseHeaders();
		assertThat(preflight.get(HEADER)).singleElement().asString().matches(GENERATED);
	}

	@Test
	void websocketHandshakeCarriesTraceId() {
		AtomicReference<String> reply = new AtomicReference<>();
		AtomicReference<List<String>> handshake = new AtomicReference<>();
		new ReactorNettyWebSocketClient()
			.execute(URI.create("ws://127.0.0.1:" + gatewayPort + "/ws/message?token=abc"), session -> {
				handshake.set(session.getHandshakeInfo().getHeaders().getOrEmpty(HEADER));
				return session.send(Mono.just(session.textMessage("ping")))
					.thenMany(session.receive().take(1).map(WebSocketMessage::getPayloadAsText).doOnNext(reply::set))
					.then();
			})
			.block(Duration.ofSeconds(5));

		assertThat(reply.get()).isEqualTo("echo:ping");
		List<String> passedOn = RECEIVED.get(0).header(HEADER);
		assertThat(passedOn).hasSize(1);
		assertThat(passedOn.get(0)).matches(GENERATED);
		assertThat(handshake.get()).containsExactly(passedOn.get(0));
	}

	@Test
	void accessLineTellsWhereTheRequestWentWithoutTheQuery(CapturedOutput output) {
		get("/system/user/list?token=secret-value-1&page=query-marker-1", "log-check-1", 200);

		assertThat(accessLine(output, "log-check-1"))
			.contains(" INFO ")
			.contains("ip=127.0.0.1 method=GET uri=/system/user/list status=200 ms=")
			.contains(" route=backend to=127.0.0.1:" + backendPort() + " lane=- end=ok trace=log-check-1");
		// 日志写出去之前 token= 后面的值会被换成星号（LogSecretsMasking），只找令牌的值看不出查询串有没有被记下来，
		// 所以另带一个参数：查询串要是被记了，它会原样出现
		assertThat(output.getAll()).doesNotContain("secret-value-1").doesNotContain("query-marker-1");
	}

	@Test
	void websocketIsLoggedWithoutItsToken(CapturedOutput output) {
		new ReactorNettyWebSocketClient()
			.execute(URI.create("ws://127.0.0.1:" + gatewayPort + "/ws/message?token=secret-value-2&v=query-marker-2"),
				session -> session.send(Mono.just(session.textMessage("ping")))
					.thenMany(session.receive().take(1))
					.then())
			.block(Duration.ofSeconds(5));

		String traceId = RECEIVED.get(0).header(HEADER).get(0);
		assertThat(accessLine(output, traceId))
			.contains(" method=GET uri=/ws/message status=101 ")
			.contains(" route=backend to=127.0.0.1:" + backendPort() + " ");
		// 另带的那个参数用来判断查询串有没有被记下来，理由见上一个测试
		assertThat(output.getAll()).doesNotContain("secret-value-2").doesNotContain("query-marker-2");
	}

	@Test
	void failedForwardIsWrittenAsWarning(CapturedOutput output) {
		get("/dead/anything", "log-check-3", 502);
		assertThat(accessLine(output, "log-check-3"))
			.contains(" WARN ")
			.contains(" uri=/dead/anything status=502 ")
			.contains(" route=dead to=127.0.0.1:")
			.contains(" end=error ");
		// 出错原因在另一行日志里，带着同一个编号
		assertThat(output.getOut()).contains("请求编号 log-check-3");

		get("/unregistered/anything", "log-check-4", 503);
		assertThat(accessLine(output, "log-check-4"))
			.contains(" WARN ")
			.contains(" status=503 ")
			.contains(" route=unregistered to=- lane=- end=error ");
	}

	@Test
	void requestRejectedByGatewayIsLoggedButNotAsWarning(CapturedOutput output) {
		get("/inner/message/send", "log-check-5", 404);

		assertThat(accessLine(output, "log-check-5"))
			.contains(" INFO ")
			.contains(" uri=/inner/message/send status=404 ")
			.contains(" route=backend to=- lane=- end=ok ");
	}

	@Test
	void ttfbOfStreamingResponseIsTheWaitForItsFirstChunk(CapturedOutput output) {
		get("/slow-stream/chat", "log-check-8", 200);

		Matcher ttfb = Pattern.compile(" ttfb=(\\d+) ").matcher(accessLine(output, "log-check-8"));
		assertThat(ttfb.find()).isTrue();
		assertThat(Long.parseLong(ttfb.group(1))).isGreaterThanOrEqualTo(SLOW_STREAM_DELAY.toMillis() - 50);
	}

	@Test
	void successfulHealthChecksAreNotLogged(CapturedOutput output) {
		get("/_gateway/health", "log-check-6", 200);
		get("/ping", "log-check-7", 200);

		accessLine(output, "log-check-7");
		assertThat(output.getOut()).doesNotContain("trace=log-check-6");
	}

	private HttpHeaders get(String pathAndQuery, String traceId, int expectedStatus) {
		WebTestClient.RequestHeadersSpec<?> request = client().get().uri(raw(pathAndQuery));
		if (traceId != null) {
			request.header(HEADER, traceId);
		}
		return request.exchange().expectStatus().isEqualTo(expectedStatus).expectBody().returnResult()
			.getResponseHeaders();
	}

	/** 访问日志在响应写完之后才记，客户端可能先一步拿到响应，所以等一会儿 */
	private static String accessLine(CapturedOutput output, String traceId) {
		AtomicReference<String> found = new AtomicReference<>();
		await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
			Optional<String> line = output.getOut().lines()
				.filter(text -> text.contains(" ACCESS ") && text.endsWith("trace=" + traceId))
				.findFirst();
			assertThat(line).as("访问日志里 trace=%s 的那一行", traceId).isPresent();
			found.set(line.get());
		});
		return found.get();
	}
}
