package com.mugsun.cloud.gateway.support;

import com.mugsun.cloud.gateway.props.GatewayGuardProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.boot.autoconfigure.web.ServerProperties.ForwardHeadersStrategy;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;

import java.net.InetSocketAddress;

import static org.assertj.core.api.Assertions.assertThat;
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

	@Test
	void trustedProxyThatOnlyLooksLikeAnAddressIsIgnored() {
		GatewayGuardProperties properties = new GatewayGuardProperties();
		properties.setTrustedProxies("10.0.0.300, 192.0.2.10");
		ClientViewResolver resolver = new ClientViewResolver(properties, server(ForwardHeadersStrategy.NONE));

		// 在什么名字都解析得出地址的网络里（测试里的 FakeIpDns 模仿的就是这种），10.0.0.300 会被查成另一个地址。
		// 那个地址不能因此成了可信代理：它带来的 X-Forwarded-For 不算数
		assertThat(resolver.resolve(request(FakeIpDns.ANSWER, "203.0.113.5")).clientIp()).isEqualTo(FakeIpDns.ANSWER);
		// 写对的那一条照常生效
		assertThat(resolver.resolve(request("192.0.2.10", "203.0.113.5")).clientIp()).isEqualTo("203.0.113.5");
	}

	private static ServerHttpRequest request(String remote, String forwardedFor) {
		return MockServerHttpRequest.get("http://gateway/ping")
			.remoteAddress(new InetSocketAddress(remote, 40000))
			.header("X-Forwarded-For", forwardedFor)
			.build();
	}

	private static ServerProperties server(ForwardHeadersStrategy strategy) {
		ServerProperties properties = new ServerProperties();
		properties.setForwardHeadersStrategy(strategy);
		return properties;
	}
}
