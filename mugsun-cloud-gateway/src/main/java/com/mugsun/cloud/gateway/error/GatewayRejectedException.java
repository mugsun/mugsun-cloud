package com.mugsun.cloud.gateway.error;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.server.ResponseStatusException;

/**
 * 网关主动拒绝的请求。说明文字是写给调用方看的，会原样放进返回体的 msg，不要写内部细节。
 *
 * <p>过滤器里要拒绝请求时返回 {@code Mono.error(new GatewayRejectedException(...))}，由 {@link GatewayErrorHandler} 统一写响应；
 * 不要在过滤器里直接写响应——响应一旦写出，排在前面、还要改响应头的过滤器会报错。
 */
public class GatewayRejectedException extends ResponseStatusException {

	private final HttpHeaders headers;

	public GatewayRejectedException(HttpStatusCode status, String message) {
		this(status, message, HttpHeaders.EMPTY);
	}

	/**
	 * @param headers 要随这次拒绝一起返回的响应头，例如限流时告诉客户端过多久再试的 Retry-After
	 */
	public GatewayRejectedException(HttpStatusCode status, String message, HttpHeaders headers) {
		super(status, message);
		this.headers = HttpHeaders.readOnlyHttpHeaders(headers);
	}

	@Override
	public HttpHeaders getHeaders() {
		return headers;
	}
}
