package com.mugsun.cloud.gateway;

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
import org.springframework.test.context.ContextConfiguration;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * 网关的开关一项都没配、但环境变量本身在，值是空的。编排文件把变量逐个传进容器时就是这样：没配的那些传进来的是空字符串，
 * 不是「没有这个变量」，application.yml 里冒号后面的默认值这时不起作用。结果要和不设一样：
 * 网关起得来，访问日志照默认记，没有任何限制，不限流，动态路由不启用。
 *
 * <p>变量名从 application.yml 里找（见 GatewaySwitches），不在这里列：以后加的开关自动算进来。
 * 开关都配上值的情况见 GatewayEnvironmentSettingsTest。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
	"spring.cloud.nacos.discovery.enabled=false",
	"spring.cloud.service-registry.auto-registration.enabled=false"})
@ContextConfiguration(initializers = GatewayBlankSettingsTest.BlankSwitches.class)
@ExtendWith(OutputCaptureExtension.class)
class GatewayBlankSettingsTest extends GatewayTestBase {

	/** 网关的开关全设成空的 */
	static class BlankSwitches extends FakeEnvironment {

		@Override
		protected Map<String, String> variables() {
			Map<String, String> blank = new LinkedHashMap<>();
			GatewaySwitches.declared().keySet().forEach(variable -> blank.put(variable, ""));
			return blank;
		}
	}

	@Autowired
	GatewayGuardProperties properties;

	@Autowired
	ApplicationContext context;

	@Test
	void theSwitchesWereFoundInTheConfigurationFile() {
		assertThat(GatewaySwitches.declared().keySet()).contains(GatewaySwitches.TRUSTED_PROXIES,
			"MUGSUN_GATEWAY_ACCESS_LOG", "MUGSUN_GATEWAY_SLOW_MILLIS", "MUGSUN_GATEWAY_DENY_IPS",
			"MUGSUN_GATEWAY_RATE_LIMIT", "MUGSUN_GATEWAY_DYNAMIC_ROUTES", "MUGSUN_GATEWAY_LANES");
		assertThat(context.getEnvironment().getProperty("MUGSUN_GATEWAY_ACCESS_LOG")).isEmpty();
	}

	@Test
	void blankSettingsFallBackToTheDefaults() {
		assertThat(properties.getAccessLog()).isEqualTo(GatewayGuardProperties.AccessLogMode.ALL);
		assertThat(properties.getSlowMillis()).isEqualTo(3000);
		assertThat(properties.isDynamicRoutes()).isFalse();
		assertThat(context.getBeanNamesForType(DynamicRouteConfiguration.class)).isEmpty();
		assertThat(properties.isLanes()).isFalse();
		assertThat(context.getBeanNamesForType(LaneConfiguration.RulesFromNacos.class)).isEmpty();
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
