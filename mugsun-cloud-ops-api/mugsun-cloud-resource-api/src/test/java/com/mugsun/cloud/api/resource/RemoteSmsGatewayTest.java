package com.mugsun.cloud.api.resource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mugsun.boot.tenant.TenantContext;
import com.mugsun.cloud.common.inner.InnerCallException;
import com.mugsun.cloud.common.inner.InnerCallException.Kind;
import com.mugsun.cloud.common.inner.InnerClient;
import com.mugsun.core.tool.exception.ServiceException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RemoteSmsGatewayTest {

	private static final String RESOURCE = "mugsun-resource";

	/** 路径是和文件和短信服务约好的，改了两边就对不上 */
	private static final String CODE_PATH = "/inner/resource/sms/code";

	private static final String TEXT_PATH = "/inner/resource/sms/text";

	private static final String PHONE = "13800000000";

	private final ObjectMapper objectMapper = new ObjectMapper();

	private final InnerClient client = mock(InnerClient.class);

	private final RemoteSmsGateway gateway = new RemoteSmsGateway(client, RESOURCE);

	private JsonNode json(String text) throws Exception {
		return objectMapper.readTree(text);
	}

	private static Map<String, Object> body(String... pairs) {
		Map<String, Object> body = new LinkedHashMap<>();
		for (int i = 0; i < pairs.length; i += 2) {
			body.put(pairs[i], pairs[i + 1]);
		}
		return body;
	}

	/** 发验证码是一次内部调用，等的时间比默认的长：那边要等短信平台返回 */
	@Test
	void sendsTheCodeThroughTheResourceService() throws Exception {
		when(client.call(anyString(), anyString(), any(), any(Duration.class))).thenReturn(json("{}"));

		gateway.sendCode(PHONE);

		verify(client).call(RESOURCE, CODE_PATH, body("op", "send", "phone", PHONE), RemoteSmsGateway.SEND_TIMEOUT);
	}

	/** 对方说不行的话原样给用户：发得太频繁、通道没配，都和单体版是同一句 */
	@Test
	void passesOnWhatTheResourceServiceRefused() {
		when(client.call(anyString(), anyString(), any(), any(Duration.class)))
			.thenThrow(new ServiceException("发送过于频繁，请 60 秒后再试"));

		assertThatThrownBy(() -> gateway.sendCode(PHONE))
			.isInstanceOf(ServiceException.class)
			.hasMessage("发送过于频繁，请 60 秒后再试");
	}

	/** 对方没在运行：报错，不能当成发出去了。给用户的话里不带服务名和路径 */
	@Test
	void failsLoudlyWhenTheCodeCouldNotBeSent() {
		when(client.call(anyString(), anyString(), any(), any(Duration.class)))
			.thenThrow(new InnerCallException(Kind.NO_INSTANCE, "注册中心里没有 mugsun-resource 的实例"));

		assertThatThrownBy(() -> gateway.sendCode(PHONE))
			.isInstanceOf(ServiceException.class)
			.hasMessage("短信服务暂时不可用，请稍后再试");
	}

	@Test
	void saysBusyWhenTheResourceServiceIsFull() {
		when(client.call(anyString(), anyString(), any(), any(Duration.class)))
			.thenThrow(new InnerCallException(Kind.BUSY, "mugsun-resource 的 /inner/resource/sms/code 正忙，这次没有执行"));

		assertThatThrownBy(() -> gateway.sendCode(PHONE))
			.isInstanceOf(ServiceException.class)
			.hasMessage("短信服务正忙，请稍后再试");
	}

	@Test
	void verifiesTheCodeOnTheResourceService() throws Exception {
		when(client.call(anyString(), anyString(), any())).thenReturn(json("{\"ok\":true}"), json("{\"ok\":false}"));

		assertThat(gateway.verifyCode(PHONE, "123456")).isTrue();
		assertThat(gateway.verifyCode(PHONE, "654321")).isFalse();

		verify(client).call(RESOURCE, CODE_PATH, body("op", "verify", "phone", PHONE, "code", "123456"));
		verify(client).call(RESOURCE, CODE_PATH, body("op", "verify", "phone", PHONE, "code", "654321"));
	}

	/** 没填验证码、没填手机号都不能通过，也不用去问 */
	@Test
	void neverPassesAnEmptyCodeOrPhone() {
		assertThat(gateway.verifyCode(PHONE, null)).isFalse();
		assertThat(gateway.verifyCode(PHONE, "")).isFalse();
		assertThat(gateway.verifyCode(PHONE, "  ")).isFalse();
		assertThat(gateway.verifyCode(null, "123456")).isFalse();
		assertThat(gateway.verifyCode(" ", "123456")).isFalse();

		verifyNoInteractions(client);
	}

	/** 对方的回答里读不出「通过」时不算通过 */
	@Test
	void doesNotPassOnAnAnswerItCannotRead() throws Exception {
		when(client.call(anyString(), anyString(), any()))
			.thenReturn(json("{}"), json("null"), json("{\"ok\":\"yes\"}"), null);

		assertThat(gateway.verifyCode(PHONE, "123456")).isFalse();
		assertThat(gateway.verifyCode(PHONE, "123456")).isFalse();
		assertThat(gateway.verifyCode(PHONE, "123456")).isFalse();
		assertThat(gateway.verifyCode(PHONE, "123456")).isFalse();
	}

	/** 验的时候对方不在：报「暂时不可用」。说成验证码错了的话，会给这个手机号白记一次登录失败 */
	@Test
	void doesNotCallItAWrongCodeWhenTheResourceServiceIsAway() {
		when(client.call(anyString(), anyString(), any()))
			.thenThrow(new InnerCallException(Kind.FAILED, "连不上 mugsun-resource"));

		assertThatThrownBy(() -> gateway.verifyCode(PHONE, "123456"))
			.isInstanceOf(ServiceException.class)
			.hasMessage("短信服务暂时不可用，请稍后再试");
	}

	@Test
	void asksWhetherCodesAreShown() throws Exception {
		when(client.call(anyString(), anyString(), any())).thenReturn(json("{\"show\":true}"), json("{\"show\":false}"));

		assertThat(gateway.isShowCode()).isTrue();
		assertThat(gateway.isShowCode()).isFalse();

		verify(client, times(2)).call(RESOURCE, CODE_PATH, body("op", "show"));
	}

	@Test
	void fetchesTheCodeToShow() throws Exception {
		when(client.call(anyString(), anyString(), any())).thenReturn(json("{\"code\":\"123456\"}"), json("{}"));

		assertThat(gateway.peekCode(PHONE)).isEqualTo("123456");
		assertThat(gateway.peekCode(PHONE)).isNull();

		verify(client, times(2)).call(RESOURCE, CODE_PATH, body("op", "peek", "phone", PHONE));
	}

	/** 问不到就按不回显算：验证码已经发出去了，这一步不该让请求失败 */
	@Test
	void showsNothingWhenItCannotAsk() {
		when(client.call(anyString(), anyString(), any()))
			.thenThrow(new InnerCallException(Kind.BREAKER_OPEN, "前几次调用接连失败，暂时不调它"));

		assertThat(gateway.isShowCode()).isFalse();
		assertThat(gateway.peekCode(PHONE)).isNull();
	}

	@Test
	void sendsTextThroughTheResourceService() throws Exception {
		when(client.call(anyString(), anyString(), any(), any(Duration.class))).thenReturn(json("{}"));

		gateway.sendText(PHONE, "您的登录验证码是 123456，5 分钟内有效");

		verify(client).call(RESOURCE, TEXT_PATH, body("phone", PHONE, "text", "您的登录验证码是 123456，5 分钟内有效"),
			RemoteSmsGateway.SEND_TIMEOUT);
	}

	/** 通知调度要拿到真实的结果：没发出去就得报错，它才会记失败、再重试 */
	@Test
	void failsLoudlyWhenTheTextCouldNotBeSent() {
		when(client.call(anyString(), anyString(), any(), any(Duration.class)))
			.thenThrow(new InnerCallException(Kind.NOT_CONFIGURED, "没有配置 mugsun.cloud.inner.secret，内部调用停用"));

		assertThatThrownBy(() -> gateway.sendText(PHONE, "通知"))
			.isInstanceOf(ServiceException.class)
			.hasMessage("短信服务暂时不可用，请稍后再试");
	}

	/** 发验证码时用户还没登录，没有租户。内部调用在没有租户上下文时不发请求，所以要在「不分租户」里调 */
	@Test
	void callsWithoutNeedingATenant() throws Exception {
		AtomicReference<Object> tenants = new AtomicReference<>("没有调用");
		when(client.call(anyString(), anyString(), any(), any(Duration.class))).thenAnswer(invocation -> {
			// 没有上下文时这一句会抛异常；null 表示「不加租户条件」
			tenants.set(TenantContext.resolveTenantIds());
			return json("{}");
		});

		gateway.sendCode(PHONE);

		assertThat(tenants.get()).isNull();
		assertThat(TenantContext.getRaw()).as("调用完要恢复成原来的样子").isNull();
	}

	/** 服务名配错了要在启动时就报出来，不要等到有人发短信才发现 */
	@Test
	void refusesAServiceNameThatCannotBeUsed() {
		assertThatThrownBy(() -> new RemoteSmsGateway(client, "mugsun resource"))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("mugsun.cloud.resource.service-id");
		assertThatThrownBy(() -> new RemoteSmsGateway(client, null))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("mugsun.cloud.resource.service-id");
	}
}
