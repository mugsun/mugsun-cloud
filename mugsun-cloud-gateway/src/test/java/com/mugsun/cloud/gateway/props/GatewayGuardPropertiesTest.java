package com.mugsun.cloud.gateway.props;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 配置项怎么从文字变成设置：写了值的照值，留空的保持默认值，写错的不接受。
 * 这里只看转换这一步；变量留空时整个网关的表现见 GatewayBlankSettingsTest。
 */
class GatewayGuardPropertiesTest {

	private static GatewayGuardProperties bind(Map<String, String> settings) {
		return new Binder(new MapConfigurationPropertySource(settings))
			.bindOrCreate("mugsun.gateway", GatewayGuardProperties.class);
	}

	@Test
	void nothingConfiguredMeansTheDefaults() {
		GatewayGuardProperties properties = bind(Map.of());

		assertThat(properties.getAccessLog().getMode()).isEqualTo(GatewayGuardProperties.AccessLog.Mode.ALL);
		assertThat(properties.getAccessLog().getSlowMillis()).isEqualTo(3000);
		assertThat(properties.getDynamicRoutes().isEnabled()).isFalse();
	}

	@Test
	void configuredValuesAreUsed() {
		GatewayGuardProperties properties = bind(Map.of(
			"mugsun.gateway.access-log.mode", "problems",
			"mugsun.gateway.access-log.slow-millis", "1500",
			"mugsun.gateway.dynamic-routes.enabled", "true"));

		assertThat(properties.getAccessLog().getMode()).isEqualTo(GatewayGuardProperties.AccessLog.Mode.PROBLEMS);
		assertThat(properties.getAccessLog().getSlowMillis()).isEqualTo(1500);
		assertThat(properties.getDynamicRoutes().isEnabled()).isTrue();
	}

	/** 0 是一个有意义的值（不按耗时区分），不能被当成没配而换回默认值 */
	@Test
	void zeroSlowMillisStaysZero() {
		GatewayGuardProperties properties = bind(Map.of("mugsun.gateway.access-log.slow-millis", "0"));

		assertThat(properties.getAccessLog().getSlowMillis()).isZero();
	}

	@Test
	void blankValuesKeepTheDefaults() {
		GatewayGuardProperties properties = bind(Map.of(
			"mugsun.gateway.access-log.mode", "",
			"mugsun.gateway.access-log.slow-millis", "",
			"mugsun.gateway.dynamic-routes.enabled", ""));

		assertThat(properties.getAccessLog().getMode()).isEqualTo(GatewayGuardProperties.AccessLog.Mode.ALL);
		assertThat(properties.getAccessLog().getSlowMillis()).isEqualTo(3000);
		assertThat(properties.getDynamicRoutes().isEnabled()).isFalse();
	}

	/** 留空当成没配，写错仍然不接受：网关启动时会停下来，错误信息里有这一项的名字 */
	@ParameterizedTest
	@CsvSource({
		"mugsun.gateway.access-log.mode, sometimes",
		"mugsun.gateway.access-log.slow-millis, 3s",
		"mugsun.gateway.dynamic-routes.enabled, maybe"})
	void wrongValuesAreRefused(String name, String value) {
		assertThatThrownBy(() -> bind(Map.of(name, value)))
			.isInstanceOf(BindException.class)
			.hasMessageContaining(name);
	}
}
