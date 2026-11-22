package com.mugsun.cloud.gateway.support;

import com.mugsun.cloud.gateway.props.GatewayGuardProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.web.server.autoconfigure.ServerProperties;
import org.springframework.boot.web.server.autoconfigure.ServerProperties.ForwardHeadersStrategy;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;

import java.net.InetSocketAddress;
import java.util.List;

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

	@ParameterizedTest
	@ValueSource(strings = {"10.0.0.300", "10.0.0.0/33", "nginx", "proxy.example.com", "10.*.1.*", "g::1"})
	void refusesToStartWhenATrustedProxyIsWrittenWrongly(String wrong) {
		GatewayGuardProperties properties = new GatewayGuardProperties();
		properties.setTrustedProxies("192.0.2.10, " + wrong);

		// 忽略写错的那一条的话，代理不被采信，所有请求的客户端地址都成了代理的地址。
		// 10.0.0.300 在测试里解析得出地址（见 FakeIpDns），也不能因此被收进名单
		assertThatThrownBy(() -> new ClientViewResolver(properties, server(ForwardHeadersStrategy.NONE)))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("mugsun.gateway.trusted-proxies")
			.hasMessageContaining("MUGSUN_OPENAPI_TRUSTED_PROXIES")
			.hasMessageContaining("不合法：" + wrong + "。");
	}

	@Test
	void trustedProxiesAreAListOfAddressesAndRanges() {
		GatewayGuardProperties properties = new GatewayGuardProperties();
		properties.setTrustedProxies(" 192.0.2.10, 10.0.0.0/8;2001:db8::/32\n172.16.* ");
		ClientViewResolver resolver = new ClientViewResolver(properties, server(ForwardHeadersStrategy.NONE));

		for (String proxy : List.of("192.0.2.10", "10.9.9.9", "2001:db8::7", "172.16.3.4")) {
			assertThat(resolver.resolve(request(proxy, "203.0.113.5")).clientIp()).as(proxy).isEqualTo("203.0.113.5");
		}
		// 不在名单里的地址带来的 X-Forwarded-For 不算数
		assertThat(resolver.resolve(request("192.0.2.11", "203.0.113.5")).clientIp()).isEqualTo("192.0.2.11");
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
