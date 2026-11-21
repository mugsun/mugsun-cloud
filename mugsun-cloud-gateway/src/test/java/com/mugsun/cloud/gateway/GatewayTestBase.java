package com.mugsun.cloud.gateway;

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
			// 真实的后端服务带着和单体版一样的跨域过滤器，会自己写跨域响应头。这里照样写一份，
			// 并故意多写一个网关规则里没有的头，用来确认后端写的这份不会返回给客户端
			String origin = request.requestHeaders().get("Origin");
			if (origin != null) {
				response.header("Access-Control-Allow-Origin", origin);
				response.header("Access-Control-Allow-Credentials", "true");
				response.addHeader("Vary", "Origin");
			}
			return response.header("Content-Type", "application/json").sendString(Mono.just("{\"code\":200}"));
		})
		.bindNow();

	@LocalServerPort
	int gatewayPort;

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
		return WebTestClient.bindToServer().baseUrl("http://127.0.0.1:" + gatewayPort).build();
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
