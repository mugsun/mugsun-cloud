package com.mugsun.cloud.gateway;

import com.mugsun.cloud.gateway.support.GatewayClients;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 网关转发测试的公共部分：起一个假的后端服务，记下它收到的请求；网关不连注册中心，路由直接指向这个假后端。
 */
abstract class GatewayTestBase {

	/** 假后端收到的一次请求 */
	record Received(String uri, Map<String, List<String>> headers) {

		List<String> header(String name) {
			return headers.getOrDefault(name, List.of());
		}
	}

	static final List<Received> RECEIVED = new CopyOnWriteArrayList<>();

	/** /slow-stream/ 下的流式接口，第一段内容要等这么久 */
	static final Duration SLOW_STREAM_DELAY = Duration.ofMillis(400);

	private static final DisposableServer BACKEND = HttpServer.create().host("127.0.0.1").port(0)
		.handle((request, response) -> {
			Map<String, List<String>> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
			request.requestHeaders()
				.forEach(entry -> headers.computeIfAbsent(entry.getKey(), key -> new ArrayList<>()).add(entry.getValue()));
			RECEIVED.add(new Received(request.uri(), headers));
			if (request.uri().startsWith("/ws/")) {
				return response.sendWebsocket((inbound, outbound) ->
					outbound.sendString(inbound.receive().asString().map(text -> "echo:" + text)));
			}
			// 真实的后端服务会把收到的请求编号原样写进响应头；/foreign/ 下模拟一个有自己一套编号的服务
			String traceId = request.requestHeaders().get("X-Trace-Id");
			if (request.uri().startsWith("/foreign/")) {
				response.header("X-Trace-Id", "backend-own-id");
			} else if (traceId != null) {
				response.header("X-Trace-Id", traceId);
			}
			// 真实的后端服务带着和单体版一样的跨域过滤器，会自己写跨域响应头。这里照样写一份，
			// 并故意多写一个网关规则里没有的头，用来确认后端写的这份不会返回给客户端
			String origin = request.requestHeaders().get("Origin");
			if (origin != null) {
				response.header("Access-Control-Allow-Origin", origin);
				response.header("Access-Control-Allow-Credentials", "true");
				response.addHeader("Vary", "Origin");
			}
			if (request.uri().startsWith("/slow-stream/")) {
				// 流式接口：响应头马上发，第一段内容过一会儿才有（比如在等大模型）
				return response.header("Content-Type", "text/event-stream").sendHeaders()
					.sendString(Mono.delay(SLOW_STREAM_DELAY).map(tick -> "data: first\n\n"));
			}
			return response.header("Content-Type", "application/json").sendString(Mono.just("{\"code\":200}"));
		})
		.bindNow();

	@LocalServerPort
	int gatewayPort;

	static int backendPort() {
		return BACKEND.port();
	}

	@DynamicPropertySource
	static void routes(DynamicPropertyRegistry registry) {
		String prefix = "spring.cloud.gateway.server.webflux.routes";
		registry.add(prefix + "[0].id", () -> "dead");
		registry.add(prefix + "[0].uri", () -> "http://127.0.0.1:" + closedPort());
		registry.add(prefix + "[0].predicates[0]", () -> "Path=/dead/**");
		registry.add(prefix + "[1].id", () -> "unregistered");
		registry.add(prefix + "[1].uri", () -> "lb://no-such-service");
		registry.add(prefix + "[1].predicates[0]", () -> "Path=/unregistered/**");
		registry.add(prefix + "[2].id", () -> "backend");
		registry.add(prefix + "[2].uri", () -> "http://127.0.0.1:" + BACKEND.port());
		registry.add(prefix + "[2].predicates[0]", () -> "Path=/**");
	}

	@BeforeEach
	void clearReceived() {
		RECEIVED.clear();
	}

	WebTestClient client() {
		return GatewayClients.onPort(gatewayPort);
	}

	/** 按原样发送路径，不让客户端再编码或整理一遍 */
	URI raw(String pathAndQuery) {
		return URI.create("http://127.0.0.1:" + gatewayPort + pathAndQuery);
	}

	private static int closedPort() {
		try (ServerSocket socket = new ServerSocket(0)) {
			return socket.getLocalPort();
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}
}
