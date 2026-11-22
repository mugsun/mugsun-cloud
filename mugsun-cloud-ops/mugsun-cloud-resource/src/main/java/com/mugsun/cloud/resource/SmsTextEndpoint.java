package com.mugsun.cloud.resource;

import com.fasterxml.jackson.databind.JsonNode;
import com.mugsun.boot.system.service.SmsService;
import com.mugsun.cloud.api.resource.SmsApi;
import com.mugsun.cloud.common.inner.InnerCall;
import com.mugsun.cloud.common.inner.InnerEndpoint;
import com.mugsun.cloud.common.inner.InnerPaths;
import com.mugsun.core.tool.exception.ServiceException;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 内部接口：发一条文本短信，给还在 system 服务里的双因子登录和短信通知用。
 *
 * <p>执行的是 mugsun-boot 的 {@link SmsService#sendText}，这里只负责收发。请求是 {@code {"phone": "…", "text": "…"}}，
 * 发出去了响应是空对象；没有启用的短信配置、短信平台没发出去，是 {@code SmsService} 抛的业务失败，调用方收到的是同一句话。
 * 字段名在 {@link SmsApi}。
 *
 * <p>和验证码（{@link SmsCodeEndpoint}）分成两个接口：两边各有各的同时处理上限，一批短信通知一起发的时候，
 * 不挤占登录发验证码、验验证码的名额。
 */
@Component
class SmsTextEndpoint implements InnerEndpoint {

	private final SmsService sms;

	SmsTextEndpoint(SmsService sms) {
		this.sms = sms;
	}

	@Override
	public String path() {
		return InnerPaths.RESOURCE_SMS_TEXT;
	}

	@Override
	public Object handle(InnerCall call) {
		JsonNode body = call.body();
		String phone = textOf(body, SmsApi.FIELD_PHONE);
		String text = textOf(body, SmsApi.FIELD_TEXT);
		if (phone == null || phone.isBlank()) {
			throw new ServiceException("请求里没有手机号");
		}
		if (text == null || text.isBlank()) {
			throw new ServiceException("请求里没有短信内容");
		}
		sms.sendText(phone, text);
		return Map.of();
	}

	private static String textOf(JsonNode body, String field) {
		JsonNode node = body == null ? null : body.get(field);
		return node != null && node.isTextual() ? node.textValue() : null;
	}
}
