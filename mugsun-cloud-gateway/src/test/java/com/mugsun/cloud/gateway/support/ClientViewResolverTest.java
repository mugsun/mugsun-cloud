package com.mugsun.cloud.gateway.support;

import com.mugsun.cloud.gateway.props.GatewayGuardProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.boot.autoconfigure.web.ServerProperties.ForwardHeadersStrategy;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClientViewResolverTest {

	@Test
	void startsWhenForwardHeadersStrategyIsNone() {
		assertThatCode(() -> new ClientViewResolver(new GatewayGuardProperties(), server(ForwardHeadersStrategy.NONE)))
			.doesNotThrowAnyException();
	}

	@ParameterizedTest
	@EnumSource(value = ForwardHeadersStrategy.class, names = {"NATIVE", "FRAMEWORK"})
	void refusesToStartWhenContainerTrustsForwardedHeaders(ForwardHeadersStrategy strategy) {
		assertThatThrownBy(() -> new ClientViewResolver(new GatewayGuardProperties(), server(strategy)))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("server.forward-headers-strategy");
	}

	@Test
	void refusesToStartWhenStrategyIsNotSet() {
		assertThatThrownBy(() -> new ClientViewResolver(new GatewayGuardProperties(), server(null)))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("server.forward-headers-strategy");
	}

	private static ServerProperties server(ForwardHeadersStrategy strategy) {
		ServerProperties properties = new ServerProperties();
		properties.setForwardHeadersStrategy(strategy);
		return properties;
	}
}
