package com.mugsun.cloud.gateway.support;

import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyExtractors;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 测试里访问网关用的客户端。
 *
 * <p>收到响应时先把响应体整个读进普通内存，再交给测试。{@code WebTestClient} 连真实的端口时，
 * 只断言状态码和响应头并不会去读响应体；没读的那部分占着的缓冲区没有人释放，{@link BufferLeakGuard} 会让测试不通过。
 * 只看状态码的测试很多，所以在这里统一读掉，不靠每个测试记得去读。
 *
 * <p>代价是要等响应体全部到齐，测试才拿得到响应：要边收边看的测试（比如看流式响应的第一段什么时候到）不能用它，
 * 要自己建客户端，并且把响应体读完或者取消。
 */
public final class GatewayClients {

	private GatewayClients() {
	}

	/** 访问本机这个端口上的网关 */
	public static WebTestClient onPort(int port) {
		return WebTestClient.bindToServer()
			.baseUrl("http://127.0.0.1:" + port)
			.filter(GatewayClients::readWholeBody)
			.build();
	}

	private static Mono<ClientResponse> readWholeBody(ClientRequest request, ExchangeFunction next) {
		return next.exchange(request)
			.flatMap(response -> DataBufferUtils.join(response.body(BodyExtractors.toDataBuffers()))
				.map(GatewayClients::copyAndRelease)
				.defaultIfEmpty(new byte[0])
				.map(body -> inMemory(response, body)));
	}

	private static byte[] copyAndRelease(DataBuffer buffer) {
		try {
			byte[] bytes = new byte[buffer.readableByteCount()];
			buffer.read(bytes);
			return bytes;
		} finally {
			DataBufferUtils.release(buffer);
		}
	}

	/** 同一个响应，内容换成放在普通内存里的：测试读不读它，都没有东西要释放 */
	private static ClientResponse inMemory(ClientResponse response, byte[] body) {
		Flux<DataBuffer> content = Flux.defer(() -> body.length == 0
			? Flux.empty()
			: Flux.just(DefaultDataBufferFactory.sharedInstance.wrap(body)));
		return ClientResponse.create(response.statusCode(), response.strategies())
			.headers(headers -> headers.addAll(response.headers().asHttpHeaders()))
			.cookies(cookies -> cookies.addAll(response.cookies()))
			.body(content)
			.request(response.request())
			.build();
	}
}
