package com.mugsun.cloud.gateway;

import com.mugsun.cloud.config.NacosConfigSource;
import com.mugsun.cloud.gateway.lane.LaneConfiguration;
import com.mugsun.cloud.gateway.props.GatewayGuardProperties;
import com.mugsun.cloud.gateway.route.DynamicRouteConfiguration;
import com.mugsun.cloud.gateway.support.FakeEnvironment;
import com.mugsun.cloud.gateway.support.GatewaySwitches;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ContextConfiguration;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * 网关的开关用真正的环境变量配上值：每一项都按配的值生效。和 GatewayBlankSettingsTest 是一对，那边全部留空。
 * 部署时开关就是这样给的；为什么不写在 {@code @SpringBootTest(properties = …)} 里，见 FakeEnvironment 的说明。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
	"spring.cloud.nacos.discovery.enabled=false",
	"spring.cloud.service-registry.auto-registration.enabled=false"})
@ContextConfiguration(initializers = GatewayEnvironmentSettingsTest.Switches.class)
@ExtendWith(OutputCaptureExtension.class)
class GatewayEnvironmentSettingsTest extends GatewayTestBase {

	static class Switches extends FakeEnvironment {

		static final Map<String, String> VARIABLES = Map.ofEntries(
			// 测试里的请求都从本机直接发给网关：可信代理配一个别的地址，白名单放行本机
			Map.entry(GatewaySwitches.TRUSTED_PROXIES, "198.51.100.9/32"),
			Map.entry("MUGSUN_GATEWAY_DENY_IPS", "203.0.113.7"),
			Map.entry("MUGSUN_GATEWAY_ALLOW_IPS", "127.0.0.0/8"),
			Map.entry("MUGSUN_GATEWAY_RESTRICTED_PATHS", "/restricted"),
			Map.entry("MUGSUN_GATEWAY_RESTRICTED_ALLOW_IPS", "198.51.100.0/24"),
			Map.entry("MUGSUN_GATEWAY_RATE_LIMIT", "100/10m"),
			Map.entry("MUGSUN_GATEWAY_RATE_LIMIT_PATHS", "/limited=2/10m"),
			Map.entry("MUGSUN_GATEWAY_RATE_LIMIT_EXEMPT_IPS", "198.51.100.0/24"),
			Map.entry("MUGSUN_GATEWAY_ACCESS_LOG", "problems"),
			Map.entry("MUGSUN_GATEWAY_SLOW_MILLIS", "1500"),
			Map.entry("MUGSUN_GATEWAY_DYNAMIC_ROUTES", "true"),
			Map.entry("MUGSUN_GATEWAY_LANES", "true"));

		@Override
		protected Map<String, String> variables() {
			return VARIABLES;
		}
	}

	@Autowired
	GatewayGuardProperties properties;

	@Autowired
	ApplicationContext context;

	/** 网关加了开关，这里要给它配一个值，并在下面两个测试里补上它的值和效果 */
	@Test
	void everySwitchOfTheGatewayIsGivenAValueHere() {
		assertThat(Switches.VARIABLES.keySet()).containsExactlyInAnyOrderElementsOf(GatewaySwitches.declared().keySet());
	}

	@Test
	void everySettingHasTheValueOfItsVariable() {
		assertThat(properties.getTrustedProxies()).isEqualTo("198.51.100.9/32");
		assertThat(properties.getDenyIps()).isEqualTo("203.0.113.7");
		assertThat(properties.getAllowIps()).isEqualTo("127.0.0.0/8");
		assertThat(properties.getRestrictedPaths()).isEqualTo("/restricted");
		assertThat(properties.getRestrictedAllowIps()).isEqualTo("198.51.100.0/24");
		assertThat(properties.getRateLimit()).isEqualTo("100/10m");
		assertThat(properties.getRateLimitPaths()).isEqualTo("/limited=2/10m");
		assertThat(properties.getRateLimitExemptIps()).isEqualTo("198.51.100.0/24");
		assertThat(properties.getAccessLog()).isEqualTo(GatewayGuardProperties.AccessLogMode.PROBLEMS);
		assertThat(properties.getSlowMillis()).isEqualTo(1500);
		assertThat(properties.isDynamicRoutes()).isTrue();
		assertThat(context.getBeanNamesForType(DynamicRouteConfiguration.class)).hasSize(1);
		assertThat(properties.isLanes()).isTrue();
		assertThat(context.getBeanNamesForType(LaneConfiguration.RulesFromNacos.class)).hasSize(1);
		// 这里没有连 Nacos：两个开关都开着，也没有「去 Nacos 读哪一份配置」
		assertThat(context.getBeanNamesForType(NacosConfigSource.Watch.class)).isEmpty();
	}

	@Test
	void theSettingsAreInEffect(CapturedOutput output) {
		client().get().uri(raw("/restricted/x")).exchange().expectStatus().isForbidden();
		client().get().uri(raw("/limited")).exchange().expectStatus().isOk();
		client().get().uri(raw("/limited")).exchange().expectStatus().isOk();
		client().get().uri(raw("/limited")).exchange().expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
		assertThat(RECEIVED).extracting(Received::uri).containsExactly("/limited", "/limited");

		// 访问日志设成了 problems：成功的请求不记，转发出错的才记。先发成功的，等出错的那一行出来了再确认成功的没有记
		client().get().uri(raw("/fine")).header("X-Trace-Id", "env-settings-fine").exchange().expectStatus().isOk();
		client().get().uri(raw("/dead/x")).header("X-Trace-Id", "env-settings-dead").exchange()
			.expectStatus().isEqualTo(HttpStatus.BAD_GATEWAY);
		await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
			assertThat(output.getOut().lines()
				.filter(line -> line.contains(" ACCESS ") && line.endsWith("trace=env-settings-dead")))
				.singleElement().asString().contains(" uri=/dead/x status=502 "));
		assertThat(output.getOut().lines().filter(line -> line.contains(" ACCESS ")))
			.noneMatch(line -> line.endsWith("trace=env-settings-fine"));
	}
}
