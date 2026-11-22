package com.mugsun.cloud.gateway.error;

import com.mugsun.cloud.gateway.support.ResultWriter;
import com.mugsun.cloud.gateway.support.TraceIds;
import io.netty.channel.ConnectTimeoutException;
import io.netty.handler.timeout.ReadTimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.webflux.error.ErrorWebExceptionHandler;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.ErrorResponse;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.concurrent.TimeoutException;

/**
 * 网关自己产生的失败响应（转发出错、请求被网关拒绝）：状态码按出错原因定，响应体用后端的统一格式。
 *
 * <ul>
 *   <li>网关主动拒绝（{@link GatewayRejectedException}）：用异常里的状态码和说明文字</li>
 *   <li>注册中心里找不到可用的服务实例：503</li>
 *   <li>连不上服务，或者连接中途断了（对方关闭、重置，比如实例被强杀）：502</li>
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
			log.error("网关处理请求出错：{} {}，请求编号 {}", exchange.getRequest().getMethod(), path,
				TraceIds.of(exchange), ex);
		} else if (status.is5xxServerError()) {
			log.warn("转发失败，返回 {}：{} {}，原因：{}，请求编号 {}", status.value(), exchange.getRequest().getMethod(),
				path, ex.toString(), TraceIds.of(exchange));
		}
		if (ex instanceof ErrorResponse errorResponse) {
			exchange.getResponse().getHeaders().putAll(errorResponse.getHeaders());
		}
		String msg = ex instanceof GatewayRejectedException rejected && rejected.getReason() != null
			? rejected.getReason()
			: messageOf(status);
		return resultWriter.fail(exchange, status, msg);
	}

	/** 出错原因对应的状态码。访问日志也用它，记下的状态码和实际返回的一致 */
	public static HttpStatusCode statusOf(Throwable ex) {
		Throwable current = ex;
		for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++) {
			if (current instanceof ErrorResponse errorResponse) {
				return errorResponse.getStatusCode();
			}
			if (current instanceof ConnectTimeoutException || current instanceof ReadTimeoutException
				|| current instanceof TimeoutException || current instanceof SocketTimeoutException) {
				return HttpStatus.GATEWAY_TIMEOUT;
			}
			// 和后端之间的连接出的错都是 IOException：连不上、解析不到地址、中途被对方关闭或重置。按类型一个个列的话会漏：
			// 连接被重置时，JDK 报 SocketException，Linux 上 Netty 用 epoll 时报的是它自己的 NativeIoException。
			// 网关自己不读写文件，走到这里的 IOException 只会出在网络连接上。客户端上传到一半断开也会走到这里，
			// 那时客户端已经收不到响应，返回哪个状态码都一样
			if (current instanceof IOException) {
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
