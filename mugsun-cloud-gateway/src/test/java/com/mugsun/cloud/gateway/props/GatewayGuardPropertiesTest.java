package com.mugsun.cloud.gateway.props;

import com.mugsun.cloud.gateway.support.GatewaySwitches;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 配置项怎么从文字变成设置：写了值的照值，留空的保持默认值，写错的不接受。还有两条写法上的规矩：配置项不分组，
 * 环境变量按配置项起名。这里不启动网关；用真正的环境变量启动网关的测试是 GatewayBlankSettingsTest（变量都留空）
 * 和 GatewayEnvironmentSettingsTest（变量都有值）。
 */
class GatewayGuardPropertiesTest {

	private static GatewayGuardProperties bind(Map<String, String> settings) {
		return new Binder(new MapConfigurationPropertySource(settings))
			.bindOrCreate("mugsun.gateway", GatewayGuardProperties.class);
	}

	@Test
	void nothingConfiguredMeansTheDefaults() {
		GatewayGuardProperties properties = bind(Map.of());

		assertThat(properties.getAccessLog()).isEqualTo(GatewayGuardProperties.AccessLogMode.ALL);
		assertThat(properties.getSlowMillis()).isEqualTo(3000);
		assertThat(properties.isDynamicRoutes()).isFalse();
		assertThat(properties.getDynamicRoutesDataId()).isEqualTo("mugsun-gateway-routes.yaml");
		assertThat(properties.getDynamicRoutesGroup()).isEmpty();
		assertThat(properties.isLanes()).isFalse();
		assertThat(properties.getLanesDataId()).isEqualTo("mugsun-gateway-lanes.yaml");
		assertThat(properties.getLanesGroup()).isEmpty();
	}

	@Test
	void configuredValuesAreUsed() {
		GatewayGuardProperties properties = bind(Map.of(
			"mugsun.gateway.access-log", "problems",
			"mugsun.gateway.slow-millis", "1500",
			"mugsun.gateway.dynamic-routes", "true",
			"mugsun.gateway.lanes", "true",
			"mugsun.gateway.lanes-data-id", "lanes-of-test.yaml",
			"mugsun.gateway.lanes-group", "TEST_GROUP"));

		assertThat(properties.getAccessLog()).isEqualTo(GatewayGuardProperties.AccessLogMode.PROBLEMS);
		assertThat(properties.getSlowMillis()).isEqualTo(1500);
		assertThat(properties.isDynamicRoutes()).isTrue();
		assertThat(properties.isLanes()).isTrue();
		assertThat(properties.getLanesDataId()).isEqualTo("lanes-of-test.yaml");
		assertThat(properties.getLanesGroup()).isEqualTo("TEST_GROUP");
	}

	/** 0 是一个有意义的值（不按耗时区分），不能被当成没配而换回默认值 */
	@Test
	void zeroSlowMillisStaysZero() {
		GatewayGuardProperties properties = bind(Map.of("mugsun.gateway.slow-millis", "0"));

		assertThat(properties.getSlowMillis()).isZero();
	}

	@Test
	void blankValuesKeepTheDefaults() {
		GatewayGuardProperties properties = bind(Map.of(
			"mugsun.gateway.access-log", "",
			"mugsun.gateway.slow-millis", "",
			"mugsun.gateway.dynamic-routes", "",
			"mugsun.gateway.lanes", "",
			"mugsun.gateway.lanes-group", ""));

		assertThat(properties.getAccessLog()).isEqualTo(GatewayGuardProperties.AccessLogMode.ALL);
		assertThat(properties.getSlowMillis()).isEqualTo(3000);
		assertThat(properties.isDynamicRoutes()).isFalse();
		assertThat(properties.isLanes()).isFalse();
		assertThat(properties.getLanesGroup()).isEmpty();
	}

	/** 留空当成没配，写错仍然不接受：网关启动时会停下来，错误信息里有这一项的名字 */
	@ParameterizedTest
	@CsvSource({
		"mugsun.gateway.access-log, sometimes",
		"mugsun.gateway.slow-millis, 3s",
		"mugsun.gateway.dynamic-routes, maybe",
		"mugsun.gateway.lanes, maybe"})
	void wrongValuesAreRefused(String name, String value) {
		assertThatThrownBy(() -> bind(Map.of(name, value)))
			.isInstanceOf(BindException.class)
			.hasMessageContaining(name);
	}

	/** 配置项不分组：每一项都是一个值，不是装着几项的对象。原因见 GatewayGuardProperties 的说明 */
	@Test
	void everySettingIsASingleValueNotAGroup() {
		List<Method> getters = Arrays.stream(GatewayGuardProperties.class.getDeclaredMethods())
			.filter(method -> method.getParameterCount() == 0
				&& (method.getName().startsWith("get") || method.getName().startsWith("is")))
			.toList();

		assertThat(getters).hasSizeGreaterThanOrEqualTo(14).allSatisfy(getter -> {
			Class<?> type = getter.getReturnType();
			assertThat(type == String.class || type.isPrimitive() || type.isEnum())
				.as("%s 返回的是 %s：网关的配置项只能是文字、数字、布尔或枚举，不能分组", getter.getName(), type.getSimpleName())
				.isTrue();
		});
	}

	/**
	 * application.yml 里，给网关的配置项给值的环境变量，名字就是配置项的名字换成大写加下划线，只有可信代理那一个沿用单体版的名字。
	 * Spring Boot 本来就会按这个规则把环境变量对到配置项上；名字对不上时，一个变量可能经两条路各设一个配置项。
	 */
	@Test
	void aSwitchIsNamedAfterItsSetting() {
		Map<String, String> settingOfVariable = new LinkedHashMap<>(GatewaySwitches.declared());

		assertThat(settingOfVariable.remove(GatewaySwitches.TRUSTED_PROXIES)).isEqualTo("trusted-proxies");
		assertThat(settingOfVariable).hasSizeGreaterThanOrEqualTo(10).allSatisfy((variable, setting) ->
			assertThat(variable).isEqualTo("MUGSUN_GATEWAY_" + setting.toUpperCase(Locale.ROOT).replace('-', '_')));
	}
}
