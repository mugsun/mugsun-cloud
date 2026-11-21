package com.mugsun.cloud.gateway.support;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 网关自己产生的失败响应（服务连不上、路径被拦下），格式和后端的统一返回体 {@code R} 一致，前端不用区分是谁返回的。
 */
@Component
public class ResultWriter {

	private final ObjectMapper objectMapper;

	public ResultWriter(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	public Mono<Void> fail(ServerWebExchange exchange, HttpStatusCode status, String msg) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("code", status.value());
		body.put("success", false);
		body.put("data", null);
		body.put("msg", msg);
		byte[] bytes;
		try {
			bytes = objectMapper.writeValueAsBytes(body);
		} catch (JsonProcessingException e) {
			return Mono.error(e);
		}
		ServerHttpResponse response = exchange.getResponse();
		response.setStatusCode(status);
		response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
		response.getHeaders().setContentLength(bytes.length);
		return response.writeWith(Mono.just(response.bufferFactory().wrap(bytes)));
	}
}
