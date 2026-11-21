package com.mugsun.cloud.gateway.error;

import com.mugsun.cloud.gateway.support.ResultWriter;
import io.netty.channel.ConnectTimeoutException;
import io.netty.handler.timeout.ReadTimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.reactive.error.ErrorWebExceptionHandler;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.ErrorResponse;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.PrematureCloseException;

import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.UnknownHostException;
import java.util.concurrent.TimeoutException;

/**
 * 网关自己产生的失败响应（转发出错、请求被网关拒绝）：状态码按出错原因定，响应体用后端的统一格式。
 *
 * <ul>
 *   <li>网关主动拒绝（{@link GatewayRejectedException}）：用异常里的状态码和说明文字</li>
 *   <li>注册中心里找不到可用的服务实例：503</li>
 *   <li>连不上服务或连接被服务中途断开：502</li>
 *   <li>连接或响应超时：504</li>
 *   <li>其他没预料到的错误：500</li>
 * </ul>
 */
@Component
@Order(-2)
public class GatewayErrorHandler implements ErrorWebExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(GatewayErrorHandler.class);
	private static final int MAX_CAUSE_DEPTH = 8;

	private final ResultWriter resultWriter;

	public GatewayErrorHandler(ResultWriter resultWriter) {
		this.resultWriter = resultWriter;
	}

	@Override
	public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
		if (exchange.getResponse().isCommitted()) {
			return Mono.error(ex);
		}
		HttpStatusCode status = statusOf(ex);
		String path = exchange.getRequest().getURI().getRawPath();
		if (status.value() == HttpStatus.INTERNAL_SERVER_ERROR.value()) {
			log.error("网关处理请求出错：{} {}", exchange.getRequest().getMethod(), path, ex);
		} else if (status.is5xxServerError()) {
			log.warn("转发失败，返回 {}：{} {}，原因：{}", status.value(), exchange.getRequest().getMethod(), path,
				ex.toString());
		}
		if (ex instanceof ErrorResponse errorResponse) {
			exchange.getResponse().getHeaders().putAll(errorResponse.getHeaders());
		}
		String msg = ex instanceof GatewayRejectedException rejected && rejected.getReason() != null
			? rejected.getReason()
			: messageOf(status);
		return resultWriter.fail(exchange, status, msg);
	}

	static HttpStatusCode statusOf(Throwable ex) {
		Throwable current = ex;
		for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++) {
			if (current instanceof ErrorResponse errorResponse) {
				return errorResponse.getStatusCode();
			}
			if (current instanceof ConnectTimeoutException || current instanceof ReadTimeoutException
				|| current instanceof TimeoutException) {
				return HttpStatus.GATEWAY_TIMEOUT;
			}
			if (current instanceof ConnectException || current instanceof PrematureCloseException
				|| current instanceof UnknownHostException || current instanceof NoRouteToHostException) {
				return HttpStatus.BAD_GATEWAY;
			}
			current = current.getCause();
		}
		return HttpStatus.INTERNAL_SERVER_ERROR;
	}

	static String messageOf(HttpStatusCode status) {
		int code = status.value();
		if (code == 404) {
			return "资源不存在";
		}
		if (code == 413) {
			return "请求内容过大";
		}
		if (code == 502 || code == 503) {
			return "服务暂时不可用，请稍后再试";
		}
		if (code == 504) {
			return "服务响应超时，请稍后再试";
		}
		if (status.is4xxClientError()) {
			return "请求无效";
		}
		return "服务器内部异常";
	}
}
