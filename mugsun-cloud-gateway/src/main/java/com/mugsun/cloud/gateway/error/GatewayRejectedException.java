package com.mugsun.cloud.gateway.error;

import org.springframework.http.HttpStatusCode;
import org.springframework.web.server.ResponseStatusException;

/**
 * 网关主动拒绝的请求。说明文字是写给调用方看的，会原样放进返回体的 msg，不要写内部细节。
 *
 * <p>过滤器里要拒绝请求时返回 {@code Mono.error(new GatewayRejectedException(...))}，由 {@link GatewayErrorHandler} 统一写响应；
 * 不要在过滤器里直接写响应——响应一旦写出，排在前面、还要改响应头的过滤器会报错。
 */
public class GatewayRejectedException extends ResponseStatusException {

	public GatewayRejectedException(HttpStatusCode status, String message) {
		super(status, message);
	}
}
