package com.mugsun.cloud.gateway.filter;

import io.netty.channel.ConnectTimeoutException;
import io.netty.handler.timeout.ReadTimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.client.DefaultServiceInstance;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.List;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 哪些失败算「没连上」（可以换实例重试），以及换实例时怎么挑。转发的完整过程在 GatewayNextInstanceTest 里测。
 */
class NextInstanceOnConnectFailureFilterTest {

	@Test
	void failuresBeforeTheConnectionIsEstablishedCanBeRetried() {
		assertThat(NextInstanceOnConnectFailureFilter.neverConnected(new ConnectException("Connection refused"))).isTrue();
		assertThat(NextInstanceOnConnectFailureFilter.neverConnected(new ConnectTimeoutException("connection timed out")))
			.isTrue();
		assertThat(NextInstanceOnConnectFailureFilter.neverConnected(new NoRouteToHostException("No route to host")))
			.isTrue();
		assertThat(NextInstanceOnConnectFailureFilter.neverConnected(new UnknownHostException("no-such-host"))).isTrue();
		// 实际收到的异常外面常常还包着一两层
		assertThat(NextInstanceOnConnectFailureFilter.neverConnected(
			new IllegalStateException(new RuntimeException(new ConnectException("Connection refused"))))).isTrue();
	}

	@Test
	void failuresAfterConnectingAreNotRetried() {
		assertThat(NextInstanceOnConnectFailureFilter.neverConnected(new IOException("Connection reset by peer")))
			.isFalse();
		assertThat(NextInstanceOnConnectFailureFilter.neverConnected(new SocketException("Broken pipe"))).isFalse();
		assertThat(NextInstanceOnConnectFailureFilter.neverConnected(ReadTimeoutException.INSTANCE)).isFalse();
		assertThat(NextInstanceOnConnectFailureFilter.neverConnected(new ResponseStatusException(
			HttpStatus.GATEWAY_TIMEOUT, "Response took longer than timeout", new TimeoutException()))).isFalse();
		assertThat(NextInstanceOnConnectFailureFilter.neverConnected(new IllegalStateException("别的错"))).isFalse();
	}

	@Test
	void picksAnInstanceAtADifferentAddress() {
		ServiceInstance first = instance("10.0.0.1", 8080);
		ServiceInstance samePortOtherHost = instance("10.0.0.2", 8080);
		ServiceInstance sameHostOtherPort = instance("10.0.0.1", 8081);

		for (int i = 0; i < 20; i++) {
			assertThat(NextInstanceOnConnectFailureFilter.another(
				List.of(first, samePortOtherHost, instance("10.0.0.1", 8080), sameHostOtherPort), first))
				.isIn(samePortOtherHost, sameHostOtherPort);
		}
	}

	@Test
	void nothingToSwitchToWhenItIsTheOnlyInstance() {
		ServiceInstance first = instance("10.0.0.1", 8080);

		assertThat(NextInstanceOnConnectFailureFilter.another(List.of(first), first)).isNull();
		assertThat(NextInstanceOnConnectFailureFilter.another(List.of(instance("10.0.0.1", 8080)), first)).isNull();
		assertThat(NextInstanceOnConnectFailureFilter.another(List.of(), first)).isNull();
	}

	private static ServiceInstance instance(String host, int port) {
		return new DefaultServiceInstance(host + ":" + port, "mugsun-system", host, port, false);
	}
}
