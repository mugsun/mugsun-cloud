package com.mugsun.cloud.gateway;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * 限流：同一个地址超过限额的请求不转发，返回 429；个别路径单独定限额，换写法躲不开；不转发的请求不计数。
 * 测试客户端从 127.0.0.1 连上来，这里把 127.0.0.1 配成可信代理，用 X-Forwarded-For 扮演不同地址的客户端。
 * 所有用例共用一个网关，计数也就共用：每个用例用自己的地址。限额的时长写得很长，用例跑的这几秒里不会补回令牌。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
	"spring.cloud.nacos.discovery.enabled=false",
	"spring.cloud.service-registry.auto-registration.enabled=false",
	"mugsun.gateway.trusted-proxies=127.0.0.1/32",
	"mugsun.gateway.rate-limit.limit=5/10m",
	"mugsun.gateway.rate-limit.path-limits=/system/auth=2/10m,/system/gis/tile=unlimited,/ws=1/10m",
	"mugsun.gateway.rate-limit.exempt-ips=198.51.100.0/24"})
@ExtendWith(OutputCaptureExtension.class)
class GatewayRateLimitTest extends GatewayTestBase {

	private static final int DEFAULT_LIMIT = 5;

	@Test
	void requestOverTheLimitGets429InUnifiedFormat() {
		String client = "192.0.2.10";
		pass(client, "/system/user/list", DEFAULT_LIMIT);

		from(client, "/system/user/list").exchange()
			.expectStatus().isEqualTo(429)
			.expectHeader().value("Retry-After", value -> assertThat(Integer.parseInt(value)).isBetween(1, 120))
			.expectHeader().exists("X-Trace-Id")
			.expectBody()
			.jsonPath("$.code").isEqualTo(429)
			.jsonPath("$.success").isEqualTo(false)
			.jsonPath("$.msg").isEqualTo("请求过于频繁，请稍后再试");

		assertThat(RECEIVED).hasSize(DEFAULT_LIMIT);
	}

	@Test
	void limitIsCountedAcrossAllPathsWithoutTheirOwnLimit() {
		String client = "192.0.2.11";
		pass(client, "/system/user/list", 2);
		pass(client, "/track/collect", 2);
		pass(client, "/v1/models", 1);

		from(client, "/system/dept/tree").exchange().expectStatus().isEqualTo(429);
	}

	@Test
	void otherAddressesAreNotAffected() {
		pass("192.0.2.20", "/ping", DEFAULT_LIMIT);
		from("192.0.2.20", "/ping").exchange().expectStatus().isEqualTo(429);

		pass("192.0.2.21", "/ping", DEFAULT_LIMIT);
	}

	@Test
	void pathWithItsOwnLimitIsCountedSeparately() {
		String client = "192.0.2.30";
		pass(client, "/system/auth/login", 2);
		from(client, "/system/auth/login").exchange().expectStatus().isEqualTo(429);
		from(client, "/system/auth/captcha").exchange().expectStatus().isEqualTo(429);

		// 别的路径用默认限额，不受影响
		pass(client, "/system/user/list", DEFAULT_LIMIT);
	}

	@ParameterizedTest
	@CsvSource({
		"192.0.2.31, /SYSTEM/Auth/login",
		"192.0.2.32, //system//auth/login",
		"192.0.2.33, /system;v=1/auth;x/login",
		"192.0.2.34, /%73ystem/auth/login",
		"192.0.2.35, /%2573ystem/auth/login",
		"192.0.2.36, /system%2Fauth%2Flogin"})
	void anotherSpellingOfThePathDoesNotEscapeItsLimit(String client, String path) {
		pass(client, "/system/auth/login", 2);

		from(client, path).exchange().expectStatus().isEqualTo(429);
	}

	@Test
	void unlimitedPathIsNotCounted() {
		String client = "192.0.2.40";
		pass(client, "/system/gis/tile/3/2/1.png", 30);

		// 也没有占掉默认限额
		pass(client, "/system/gis/layer/list", DEFAULT_LIMIT);
		from(client, "/system/gis/layer/list").exchange().expectStatus().isEqualTo(429);
	}

	@Test
	void exemptAddressIsNotLimited() {
		pass("198.51.100.7", "/system/user/list", 30);
		pass("198.51.100.7", "/system/auth/login", 10);
	}

	@Test
	void requestsTheGatewayAnswersItselfAreNotCounted() {
		String client = "192.0.2.50";
		for (int i = 0; i < 10; i++) {
			// 跨域预检由网关自己应答
			client().method(HttpMethod.OPTIONS).uri(raw("/system/user/list"))
				.header("X-Forwarded-For", client)
				.header("Origin", "http://app.example.com")
				.header("Access-Control-Request-Method", "GET")
				.exchange().expectStatus().isOk();
			// 网关自己的健康检查
			from(client, "/_gateway/health").exchange().expectStatus().isOk();
			// 内部接口在限流之前就被拦下
			from(client, "/inner/message/send").exchange().expectStatus().isNotFound();
		}
		assertThat(RECEIVED).isEmpty();

		pass(client, "/system/user/list", DEFAULT_LIMIT);
		from(client, "/system/user/list").exchange().expectStatus().isEqualTo(429);
	}

	@Test
	void websocketHandshakeOverTheLimitIsRefused() {
		HttpHeaders headers = new HttpHeaders();
		headers.set("X-Forwarded-For", "192.0.2.60");
		URI uri = URI.create("ws://127.0.0.1:" + gatewayPort + "/ws/message?token=abc");

		// 第一次连上以后发一句、等回一句：网关先答应客户端的握手再去连后端，等到回话才能确定后端收到了
		new ReactorNettyWebSocketClient()
			.execute(uri, headers, session -> session.send(Mono.just(session.textMessage("ping")))
				.thenMany(session.receive().take(1))
				.then())
			.block(Duration.ofSeconds(5));
		assertThat(RECEIVED).hasSize(1);

		assertThatThrownBy(() -> new ReactorNettyWebSocketClient()
			.execute(uri, headers, session -> Mono.empty()).block(Duration.ofSeconds(5)))
			.hasMessageContaining("429");
		assertThat(RECEIVED).hasSize(1);
	}

	@Test
	void rejectedRequestHasItsLineInTheAccessLog(CapturedOutput output) {
		String client = "192.0.2.70";
		pass(client, "/system/user/list", DEFAULT_LIMIT);

		from(client, "/system/user/list").header("X-Trace-Id", "rate-limit-check-1").exchange()
			.expectStatus().isEqualTo(429)
			.expectHeader().valueEquals("X-Trace-Id", "rate-limit-check-1");

		// 访问日志在请求结束时才写，可能比客户端收到响应稍晚
		await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(accessLine(output, "rate-limit-check-1"))
			.get().asString()
			.contains(" INFO ")
			.contains("ip=192.0.2.70 method=GET uri=/system/user/list status=429 ")
			.contains(" route=backend to=- end=ok "));
	}

	private void pass(String clientIp, String rawPath, int times) {
		for (int i = 0; i < times; i++) {
			from(clientIp, rawPath).exchange().expectStatus().isOk();
		}
	}

	private WebTestClient.RequestHeadersSpec<?> from(String clientIp, String rawPath) {
		return client().get().uri(raw(rawPath)).header("X-Forwarded-For", clientIp);
	}

	private static Optional<String> accessLine(CapturedOutput output, String traceId) {
		return output.getAll().lines()
			.filter(line -> line.contains("ACCESS") && line.contains("trace=" + traceId))
			.findFirst();
	}
}
