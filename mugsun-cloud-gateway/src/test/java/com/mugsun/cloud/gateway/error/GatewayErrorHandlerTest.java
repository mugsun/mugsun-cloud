package com.mugsun.cloud.gateway.error;

import io.netty.channel.ConnectTimeoutException;
import io.netty.handler.timeout.ReadTimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

/** 转发出错时返回哪个状态码。 */
class GatewayErrorHandlerTest {

	@Test
	void brokenConnectionToBackendIsBadGateway() {
		assertThat(GatewayErrorHandler.statusOf(new ConnectException("Connection refused"))).isEqualTo(HttpStatus.BAD_GATEWAY);
		assertThat(GatewayErrorHandler.statusOf(new UnknownHostException("mugsun-system"))).isEqualTo(HttpStatus.BAD_GATEWAY);
		assertThat(GatewayErrorHandler.statusOf(new NoRouteToHostException("No route to host"))).isEqualTo(HttpStatus.BAD_GATEWAY);
		// 实例被强杀时，正在等它应答的请求看到的是连接被重置：JDK 的报法，和 Linux 上 Netty 用 epoll 时的报法
		assertThat(GatewayErrorHandler.statusOf(new SocketException("Connection reset"))).isEqualTo(HttpStatus.BAD_GATEWAY);
		assertThat(GatewayErrorHandler.statusOf(new IOException("recvAddress(..) failed: Connection reset by peer")))
			.isEqualTo(HttpStatus.BAD_GATEWAY);
		assertThat(GatewayErrorHandler.statusOf(new IllegalStateException(new SocketException("Broken pipe"))))
			.isEqualTo(HttpStatus.BAD_GATEWAY);
	}

	@Test
	void timeoutsAreGatewayTimeout() {
		assertThat(GatewayErrorHandler.statusOf(new ConnectTimeoutException("connection timed out after 3000 ms")))
			.isEqualTo(HttpStatus.GATEWAY_TIMEOUT);
		assertThat(GatewayErrorHandler.statusOf(ReadTimeoutException.INSTANCE)).isEqualTo(HttpStatus.GATEWAY_TIMEOUT);
		assertThat(GatewayErrorHandler.statusOf(new TimeoutException())).isEqualTo(HttpStatus.GATEWAY_TIMEOUT);
		assertThat(GatewayErrorHandler.statusOf(new SocketTimeoutException("Read timed out"))).isEqualTo(HttpStatus.GATEWAY_TIMEOUT);
	}

	/** 带着状态码的异常按它自己的状态码，不看它的起因 */
	@Test
	void statusCarriedByTheExceptionWins() {
		assertThat(GatewayErrorHandler.statusOf(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE)))
			.isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
		assertThat(GatewayErrorHandler.statusOf(
			new ResponseStatusException(HttpStatus.NOT_FOUND, "no route", new IOException("x")))).isEqualTo(HttpStatus.NOT_FOUND);
	}

	@Test
	void anythingElseIsInternalError() {
		assertThat(GatewayErrorHandler.statusOf(new IllegalStateException("bug"))).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
		assertThat(GatewayErrorHandler.statusOf(new NullPointerException())).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
	}
}
