package com.mugsun.cloud.gateway;

import com.mugsun.cloud.gateway.props.GatewayGuardProperties;
import com.mugsun.cloud.gateway.route.DynamicRouteConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ApplicationContext;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * 网关的开关一项都没配、但变量本身在，值是空的。编排文件把变量逐个传进容器时就是这样：没配的那些传进来的是空字符串，
 * 不是「没有这个变量」，application.yml 里冒号后面的默认值这时不起作用。结果要和不设一样：
 * 访问日志照默认记，没有任何限制，不限流，动态路由不启用。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
	"spring.cloud.nacos.discovery.enabled=false",
	"spring.cloud.service-registry.auto-registration.enabled=false",
	"MUGSUN_OPENAPI_TRUSTED_PROXIES=",
	"MUGSUN_GATEWAY_ACCESS_LOG=",
	"MUGSUN_GATEWAY_SLOW_MILLIS=",
	"MUGSUN_GATEWAY_DENY_IPS=",
	"MUGSUN_GATEWAY_ALLOW_IPS=",
	"MUGSUN_GATEWAY_RESTRICTED_PATHS=",
	"MUGSUN_GATEWAY_RESTRICTED_ALLOW_IPS=",
	"MUGSUN_GATEWAY_RATE_LIMIT=",
	"MUGSUN_GATEWAY_RATE_LIMIT_PATHS=",
	"MUGSUN_GATEWAY_RATE_LIMIT_EXEMPT_IPS=",
	"MUGSUN_GATEWAY_DYNAMIC_ROUTES="})
@ExtendWith(OutputCaptureExtension.class)
class GatewayBlankSettingsTest extends GatewayTestBase {

	@Autowired
	GatewayGuardProperties properties;

	@Autowired
	ApplicationContext context;

	@Test
	void blankSettingsFallBackToTheDefaults() {
		assertThat(properties.getAccessLog().getMode()).isEqualTo(GatewayGuardProperties.AccessLog.Mode.ALL);
		assertThat(properties.getAccessLog().getSlowMillis()).isEqualTo(3000);
		assertThat(properties.getDynamicRoutes().isEnabled()).isFalse();
		assertThat(context.getBeanNamesForType(DynamicRouteConfiguration.class)).isEmpty();
	}

	@Test
	void nothingIsRestrictedOrLimitedAndEveryRequestIsLogged(CapturedOutput output) {
		int requests = 50;
		for (int i = 1; i <= requests; i++) {
			client().get().uri(raw("/actuator/health")).header("X-Trace-Id", "blank-settings-" + i)
				.exchange().expectStatus().isOk();
		}

		assertThat(RECEIVED).hasSize(requests);
		// 访问日志在响应写完之后才记，客户端可能先一步拿到响应，所以等一会儿
		await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
			assertThat(output.getOut().lines()
				.filter(line -> line.contains(" ACCESS ") && line.endsWith("trace=blank-settings-" + requests)))
				.singleElement().asString().contains(" uri=/actuator/health status=200 "));
	}
}
