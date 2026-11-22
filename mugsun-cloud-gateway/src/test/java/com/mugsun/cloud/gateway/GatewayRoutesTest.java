package com.mugsun.cloud.gateway;

import com.mugsun.cloud.gateway.route.DynamicRouteLoader;
import com.mugsun.cloud.gateway.route.DynamicRouteLocator;
import com.mugsun.cloud.gateway.support.GatewayClients;
import com.mugsun.cloud.gateway.support.NacosConfigSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 配置文件里的路由表：哪些路径转给哪个服务。用的是 application.yml 里真实的路由，
 * 注册中心换成写死的实例列表，每个服务由一个假后端扮演，假后端把自己的服务名和收到的路径原样返回。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
	"spring.cloud.nacos.discovery.enabled=false",
	"spring.cloud.service-registry.auto-registration.enabled=false"})
class GatewayRoutesTest {

	private static final DisposableServer SYSTEM = backend("mugsun-system");

	private static final DisposableServer GIS = backend("mugsun-gis");

	private static final DisposableServer TRACK = backend("mugsun-track");

	private static final DisposableServer AI = backend("mugsun-ai");

	@LocalServerPort
	int gatewayPort;

	@Autowired
	ApplicationContext context;

	@DynamicPropertySource
	static void instances(DynamicPropertyRegistry registry) {
		String prefix = "spring.cloud.discovery.client.simple.instances.";
		registry.add(prefix + "mugsun-system[0].uri", () -> "http://127.0.0.1:" + SYSTEM.port());
		registry.add(prefix + "mugsun-gis[0].uri", () -> "http://127.0.0.1:" + GIS.port());
		registry.add(prefix + "mugsun-track[0].uri", () -> "http://127.0.0.1:" + TRACK.port());
		registry.add(prefix + "mugsun-ai[0].uri", () -> "http://127.0.0.1:" + AI.port());
	}

	@ParameterizedTest
	@CsvSource({
		"/system/gis, mugsun-gis",
		"/system/gis/, mugsun-gis",
		"/system/gis/status, mugsun-gis",
		"/system/gis/layer/page?pageNum=1&pageSize=10, mugsun-gis",
		"/system/gis/tile/xyz/3/1/2.png, mugsun-gis",
		"/system/gisx/layer, mugsun-system",
		"/track, mugsun-track",
		"/track/collect, mugsun-track",
		"/track/config?appKey=demo, mugsun-track",
		"/system/track, mugsun-track",
		"/system/track/apps, mugsun-track",
		"/system/track/error-issues/page?pageNum=1&pageSize=10, mugsun-track",
		"/system/trackx/apps, mugsun-system",
		"/tracking/collect, mugsun-system",
		"/system/ai, mugsun-ai",
		"/system/ai/, mugsun-ai",
		"/system/ai/model/page?pageNum=1&pageSize=10, mugsun-ai",
		"/system/ai/weave/gisAsk, mugsun-ai",
		"/system/aix/model, mugsun-system",
		"/v1, mugsun-ai",
		"/v1/models, mugsun-ai",
		"/v1/chat/completions, mugsun-ai",
		"/v10/models, mugsun-system",
		"/api/v1/models, mugsun-system",
		"/ai/model, mugsun-system",
		"/system/user/page, mugsun-system",
		"/gis/layer, mugsun-system",
		"/auth/login, mugsun-system",
		"/actuator/health, mugsun-system",
		"/, mugsun-system"
	})
	void pathGoesToService(String path, String service) {
		GatewayClients.onPort(gatewayPort)
			.get().uri(URI.create("http://127.0.0.1:" + gatewayPort + path))
			.exchange()
			.expectStatus().isOk()
			.expectBody(String.class).isEqualTo(service + " " + path);
	}

	/** 动态路由默认关着：相关的对象一个都不创建，路由只有上面这张表 */
	@Test
	void dynamicRoutesAreOffByDefault() {
		assertThat(context.getBeansOfType(DynamicRouteLocator.class)).isEmpty();
		assertThat(context.getBeansOfType(DynamicRouteLoader.class)).isEmpty();
		assertThat(context.getBeansOfType(NacosConfigSource.Watch.class)).isEmpty();
	}

	private static DisposableServer backend(String service) {
		return HttpServer.create().host("127.0.0.1").port(0)
			.handle((request, response) -> response.sendString(Mono.just(service + " " + request.uri())))
			.bindNow();
	}
}
