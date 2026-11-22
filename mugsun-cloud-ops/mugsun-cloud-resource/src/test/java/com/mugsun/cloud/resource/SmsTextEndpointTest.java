package com.mugsun.cloud.resource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mugsun.boot.system.service.SmsService;
import com.mugsun.cloud.common.inner.InnerCall;
import com.mugsun.core.tool.exception.ServiceException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class SmsTextEndpointTest {

	private static final String PHONE = "13800000000";

	private static final String REQUEST = "{\"phone\":\"13800000000\",\"text\":\"您有一条新的待办\"}";

	private final ObjectMapper objectMapper = new ObjectMapper();

	private final SmsService sms = mock(SmsService.class);

	private final SmsTextEndpoint endpoint = new SmsTextEndpoint(sms);

	private String handle(String json) throws Exception {
		InnerCall call = new InnerCall("mugsun-system", "-1", objectMapper.readTree(json));
		return objectMapper.writeValueAsString(endpoint.handle(call));
	}

	/** 路径是和调用方约好的，改了两边就对不上 */
	@Test
	void listensOnTheAgreedPath() {
		assertThat(endpoint.path()).isEqualTo("/inner/resource/sms/text");
	}

	@Test
	void sendsTheText() throws Exception {
		assertThat(handle(REQUEST)).isEqualTo("{}");

		verify(sms).sendText(PHONE, "您有一条新的待办");
	}

	/** 通知调度要拿到真实的结果：没发出去的原因原样交给调用方，不能吞掉 */
	@Test
	void letsTheReasonThroughWhenTheTextCouldNotBeSent() {
		doThrow(new ServiceException("无启用短信配置")).when(sms).sendText(PHONE, "您有一条新的待办");

		assertThatThrownBy(() -> handle(REQUEST)).isInstanceOf(ServiceException.class).hasMessage("无启用短信配置");
	}

	@Test
	void refusesARequestWithoutPhoneOrText() {
		assertThatThrownBy(() -> handle("{\"text\":\"您有一条新的待办\"}"))
			.isInstanceOf(ServiceException.class).hasMessage("请求里没有手机号");
		assertThatThrownBy(() -> handle("{\"phone\":\"13800000000\"}"))
			.isInstanceOf(ServiceException.class).hasMessage("请求里没有短信内容");
		assertThatThrownBy(() -> handle("{\"phone\":\"13800000000\",\"text\":\" \"}"))
			.isInstanceOf(ServiceException.class).hasMessage("请求里没有短信内容");
		assertThatThrownBy(() -> handle("{}"))
			.isInstanceOf(ServiceException.class).hasMessage("请求里没有手机号");

		verifyNoInteractions(sms);
	}
}
