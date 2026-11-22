package com.mugsun.cloud.resource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mugsun.boot.system.service.SmsService;
import com.mugsun.cloud.common.inner.InnerCall;
import com.mugsun.core.tool.exception.ServiceException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SmsCodeEndpointTest {

	private static final String PHONE = "13800000000";

	private static final String SEND = "{\"op\":\"send\",\"phone\":\"13800000000\"}";

	private static final String PEEK = "{\"op\":\"peek\",\"phone\":\"13800000000\"}";

	private final ObjectMapper objectMapper = new ObjectMapper();

	private final SmsService sms = mock(SmsService.class);

	private final SmsCodeEndpoint endpoint = new SmsCodeEndpoint(sms);

	/** 调用方不带具体的租户，传来的是「全部租户」 */
	private InnerCall call(String json) throws Exception {
		return new InnerCall("mugsun-system", "-1", objectMapper.readTree(json));
	}

	/** 返回的对象转成 JSON，和真正发回去的一样 */
	private String handle(String json) throws Exception {
		return objectMapper.writeValueAsString(endpoint.handle(call(json)));
	}

	private static String verifyRequest(String codeField) {
		return "{\"op\":\"verify\",\"phone\":\"13800000000\"" + codeField + "}";
	}

	/** 路径是和调用方约好的，改了两边就对不上 */
	@Test
	void listensOnTheAgreedPath() {
		assertThat(endpoint.path()).isEqualTo("/inner/resource/sms/code");
	}

	@Test
	void sendsACode() throws Exception {
		assertThat(handle(SEND)).isEqualTo("{}");

		verify(sms).sendCode(PHONE);
	}

	/** 发不出去的原因原样交给调用方：ServiceException 在内部调用里算业务失败，对面收到的是同一句话 */
	@Test
	void letsTheReasonThroughWhenTheCodeCouldNotBeSent() {
		doThrow(new ServiceException("短信通道未配置")).when(sms).sendCode(PHONE);

		assertThatThrownBy(() -> handle(SEND)).isInstanceOf(ServiceException.class).hasMessage("短信通道未配置");
	}

	@Test
	void tellsWhetherCodesAreShown() throws Exception {
		when(sms.isShowCode()).thenReturn(true, false);

		assertThat(handle("{\"op\":\"show\"}")).isEqualTo("{\"show\":true}");
		assertThat(handle("{\"op\":\"show\"}")).isEqualTo("{\"show\":false}");
	}

	/** 没开回显时不把验证码交出去：调用方没先问就来取也一样，连查都不查 */
	@Test
	void handsOutTheCodeOnlyWhenShowingIsOn() throws Exception {
		when(sms.peekCode(PHONE)).thenReturn("123456");

		when(sms.isShowCode()).thenReturn(false);
		assertThat(handle(PEEK)).isEqualTo("{}");
		verify(sms, never()).peekCode(anyString());

		when(sms.isShowCode()).thenReturn(true);
		assertThat(handle(PEEK)).isEqualTo("{\"code\":\"123456\"}");
	}

	@Test
	void saysNothingWhenThereIsNoValidCodeToShow() throws Exception {
		when(sms.isShowCode()).thenReturn(true);
		when(sms.peekCode(PHONE)).thenReturn(null);

		assertThat(handle(PEEK)).isEqualTo("{}");
	}

	@Test
	void verifiesACode() throws Exception {
		when(sms.verifyCode(PHONE, "123456")).thenReturn(true);

		assertThat(handle(verifyRequest(",\"code\":\"123456\""))).isEqualTo("{\"ok\":true}");
		assertThat(handle(verifyRequest(",\"code\":\"654321\""))).isEqualTo("{\"ok\":false}");
	}

	/** 请求里没有验证码、是空的、不是字符串：都算没通过，并且不去查库 */
	@Test
	void neverPassesAnEmptyCode() throws Exception {
		// 就算查库的那一步会放行，也轮不到它
		when(sms.verifyCode(anyString(), any())).thenReturn(true);

		assertThat(handle(verifyRequest(""))).isEqualTo("{\"ok\":false}");
		assertThat(handle(verifyRequest(",\"code\":\"\""))).isEqualTo("{\"ok\":false}");
		assertThat(handle(verifyRequest(",\"code\":\"  \""))).isEqualTo("{\"ok\":false}");
		assertThat(handle(verifyRequest(",\"code\":null"))).isEqualTo("{\"ok\":false}");
		assertThat(handle(verifyRequest(",\"code\":123456"))).isEqualTo("{\"ok\":false}");

		verify(sms, never()).verifyCode(anyString(), any());
	}

	@Test
	void refusesARequestItCannotServe() {
		assertThatThrownBy(() -> handle("{\"op\":\"drop\",\"phone\":\"13800000000\"}"))
			.isInstanceOf(ServiceException.class).hasMessageContaining("op");
		assertThatThrownBy(() -> handle("{\"phone\":\"13800000000\"}"))
			.isInstanceOf(ServiceException.class).hasMessageContaining("op");
		assertThatThrownBy(() -> handle("{\"op\":\"send\"}"))
			.isInstanceOf(ServiceException.class).hasMessage("请求里没有手机号");
		assertThatThrownBy(() -> handle("{\"op\":\"verify\",\"phone\":\" \",\"code\":\"123456\"}"))
			.isInstanceOf(ServiceException.class).hasMessage("请求里没有手机号");

		verifyNoInteractions(sms);
	}
}
