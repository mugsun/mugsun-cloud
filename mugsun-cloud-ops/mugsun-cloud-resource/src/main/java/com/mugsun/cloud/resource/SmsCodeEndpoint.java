package com.mugsun.cloud.resource;

import com.fasterxml.jackson.databind.JsonNode;
import com.mugsun.boot.system.service.SmsService;
import com.mugsun.cloud.api.resource.SmsApi;
import com.mugsun.cloud.common.inner.InnerCall;
import com.mugsun.cloud.common.inner.InnerEndpoint;
import com.mugsun.cloud.common.inner.InnerPaths;
import com.mugsun.core.tool.exception.ServiceException;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 内部接口：短信验证码的发送、回显和校验，给还在 system 服务里的登录用。
 *
 * <p>执行的是 mugsun-boot 的 {@link SmsService}，这里只负责收发。请求和响应的写法见 {@link SmsApi}。
 * 发不出去（发得太频繁、短信通道没配、短信平台没发出去）是 {@code SmsService} 抛的业务失败，调用方收到的是同一句话。
 *
 * <p>比 {@code SmsService} 多做两处检查：没开回显时不把验证码交出去，调用方没先问就来取也一样；
 * 验证码是空的直接算没通过，不去查库。
 *
 * <p>发验证码要等短信平台返回，等的时候不占数据库连接（{@code SmsService} 的这几个方法不在事务里），
 * 所以这个服务把内部接口同时处理的上限配大了，见 {@code application.yml}。
 */
@Component
class SmsCodeEndpoint implements InnerEndpoint {

	private final SmsService sms;

	SmsCodeEndpoint(SmsService sms) {
		this.sms = sms;
	}

	@Override
	public String path() {
		return InnerPaths.RESOURCE_SMS_CODE;
	}

	@Override
	public Object handle(InnerCall call) {
		JsonNode body = call.body();
		String op = textOf(body, SmsApi.FIELD_OP);
		Map<String, Object> out = new LinkedHashMap<>();
		if (SmsApi.OP_SHOW.equals(op)) {
			out.put(SmsApi.FIELD_SHOW, sms.isShowCode());
			return out;
		}
		if (!SmsApi.OP_SEND.equals(op) && !SmsApi.OP_PEEK.equals(op) && !SmsApi.OP_VERIFY.equals(op)) {
			throw new ServiceException("请求里的 op 只能是 send、show、peek 或 verify");
		}
		String phone = textOf(body, SmsApi.FIELD_PHONE);
		if (phone == null || phone.isBlank()) {
			throw new ServiceException("请求里没有手机号");
		}
		if (SmsApi.OP_SEND.equals(op)) {
			sms.sendCode(phone);
		} else if (SmsApi.OP_PEEK.equals(op)) {
			String code = sms.isShowCode() ? sms.peekCode(phone) : null;
			if (code != null) {
				out.put(SmsApi.FIELD_CODE, code);
			}
		} else {
			String code = textOf(body, SmsApi.FIELD_CODE);
			out.put(SmsApi.FIELD_OK, code != null && !code.isBlank() && sms.verifyCode(phone, code));
		}
		return out;
	}

	private static String textOf(JsonNode body, String field) {
		JsonNode node = body == null ? null : body.get(field);
		return node != null && node.isTextual() ? node.textValue() : null;
	}
}
