package com.mugsun.cloud.api.resource;

import com.fasterxml.jackson.databind.JsonNode;
import com.mugsun.boot.common.sms.SmsGateway;
import com.mugsun.boot.tenant.TenantContext;
import com.mugsun.cloud.common.inner.InnerCallException;
import com.mugsun.cloud.common.inner.InnerCallException.Kind;
import com.mugsun.cloud.common.inner.InnerClient;
import com.mugsun.cloud.common.inner.InnerPaths;
import com.mugsun.core.tool.exception.ServiceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 发短信、验短信验证码转给文件和短信服务。
 *
 * <p>单体版里登录、双因子和短信通知直接调同一个进程里的 {@code SmsService}。微服务版里短信在文件和短信服务，
 * 这几处还在 system：它们拿到的 {@link SmsGateway} 是这个类，每个方法对应文件和短信服务的一次内部调用，
 * 那边执行的仍然是 {@code SmsService}。请求和响应的写法见 {@link SmsApi}。
 *
 * <p>对方明确说不行的（发得太频繁、短信通道没配、短信平台没发出去），这边抛出的是同一句话。
 * 对方没在运行或者没调成时：发短信和验验证码报「短信服务暂时不可用」，不当成发出去了，也不当成验证码错了
 * （验证码错了会给这个手机号记一次登录失败）；问「回不回显验证码」和取回显的验证码，按不回显算。
 *
 * <p>短信是平台级的功能，不分租户：发验证码的时候用户还没登录，没有租户。所以调用时不带具体的租户。
 *
 * <p>日志里不记手机号、验证码和短信内容。
 */
public class RemoteSmsGateway implements SmsGateway {

	static final String UNAVAILABLE = "短信服务暂时不可用，请稍后再试";

	static final String BUSY = "短信服务正忙，请稍后再试";

	/** 发短信要等短信平台返回，比只查库的调用多等一会儿 */
	static final Duration SEND_TIMEOUT = Duration.ofSeconds(15);

	private static final Logger log = LoggerFactory.getLogger(RemoteSmsGateway.class);

	/** 和内部调用认的服务名格式一致 */
	private static final Pattern SERVICE_NAME = Pattern.compile("[A-Za-z0-9._-]{1,64}");

	private final InnerClient client;

	private final String service;

	/**
	 * @param service 文件和短信服务的服务名，来自配置项 {@code mugsun.cloud.resource.service-id}
	 */
	public RemoteSmsGateway(InnerClient client, String service) {
		String name = service == null ? "" : service.trim();
		if (!SERVICE_NAME.matcher(name).matches()) {
			throw new IllegalStateException("配置项 " + ResourceApiAutoConfiguration.SERVICE_ID_KEY
				+ " 的值不能当服务名用：" + service);
		}
		this.client = client;
		this.service = name;
	}

	@Override
	public void sendCode(String phone) {
		call(InnerPaths.RESOURCE_SMS_CODE, codeBody(SmsApi.OP_SEND, phone), SEND_TIMEOUT);
	}

	@Override
	public boolean isShowCode() {
		JsonNode data = ask(codeBody(SmsApi.OP_SHOW, null));
		return data != null && data.path(SmsApi.FIELD_SHOW).asBoolean(false);
	}

	@Override
	public String peekCode(String phone) {
		JsonNode data = ask(codeBody(SmsApi.OP_PEEK, phone));
		JsonNode code = data == null ? null : data.get(SmsApi.FIELD_CODE);
		return code != null && code.isTextual() ? code.textValue() : null;
	}

	@Override
	public boolean verifyCode(String phone, String code) {
		// 手机号或验证码是空的直接不通过，不去问
		if (phone == null || phone.isBlank() || code == null || code.isBlank()) {
			return false;
		}
		Map<String, Object> body = codeBody(SmsApi.OP_VERIFY, phone);
		body.put(SmsApi.FIELD_CODE, code);
		JsonNode data = call(InnerPaths.RESOURCE_SMS_CODE, body, null);
		return data != null && data.path(SmsApi.FIELD_OK).asBoolean(false);
	}

	@Override
	public void sendText(String phone, String text) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put(SmsApi.FIELD_PHONE, phone);
		body.put(SmsApi.FIELD_TEXT, text);
		call(InnerPaths.RESOURCE_SMS_TEXT, body, SEND_TIMEOUT);
	}

	private static Map<String, Object> codeBody(String op, String phone) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put(SmsApi.FIELD_OP, op);
		if (phone != null) {
			body.put(SmsApi.FIELD_PHONE, phone);
		}
		return body;
	}

	/**
	 * 没调成就报「暂时不可用」；对方明确说不行的（{@code ServiceException}）原样抛出去。
	 *
	 * @param timeout 等对方返回的时间上限，null 是用默认的
	 */
	private JsonNode call(String path, Map<String, Object> body, Duration timeout) {
		try {
			return TenantContext.ignore(() -> timeout == null
				? client.call(service, path, body)
				: client.call(service, path, body, timeout));
		} catch (InnerCallException e) {
			log.warn("调用 {} 的 {} 没有成功：{}", service, path, e.getMessage());
			throw new ServiceException(e.kind() == Kind.BUSY ? BUSY : UNAVAILABLE);
		}
	}

	/** 回显验证码用的两个调用：没调成返回 null，按不回显处理 */
	private JsonNode ask(Map<String, Object> body) {
		try {
			return TenantContext.ignore(() -> client.call(service, InnerPaths.RESOURCE_SMS_CODE, body));
		} catch (RuntimeException e) {
			log.warn("向 {} 问验证码回显没有成功，按不回显处理：{}", service, e.getMessage());
			return null;
		}
	}
}
