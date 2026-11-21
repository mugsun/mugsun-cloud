package com.mugsun.cloud.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mugsun.boot.common.module.TrackAskIntent;
import com.mugsun.boot.common.module.TrackAskLlmGateway;
import com.mugsun.cloud.common.inner.InnerCall;
import com.mugsun.core.tool.exception.ServiceException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TrackAskIntentEndpointTest {

	private final ObjectMapper objectMapper = new ObjectMapper();

	private final TrackAskLlmGateway gateway = mock(TrackAskLlmGateway.class);

	@SuppressWarnings("unchecked")
	private TrackAskIntentEndpoint endpoint(TrackAskLlmGateway local) {
		ObjectProvider<TrackAskLlmGateway> provider = mock(ObjectProvider.class);
		when(provider.getIfAvailable()).thenReturn(local);
		return new TrackAskIntentEndpoint(provider);
	}

	private InnerCall call(String json) throws Exception {
		return new InnerCall("mugsun-track", "000000", objectMapper.readTree(json));
	}

	/** 路径是和 track 服务约好的，改了两边就对不上 */
	@Test
	void listensOnTheAgreedPath() {
		assertThat(endpoint(gateway).path()).isEqualTo("/inner/ai/track-ask/intent");
	}

	@Test
	void asksTheLocalGatewayAndReturnsItsAnswerFieldByField() throws Exception {
		Map<String, Object> params = new LinkedHashMap<>();
		params.put("days", 7);
		params.put("eventName", null);
		params.put("windowHours", 24L);
		params.put("steps", "view,click,pay");
		when(gateway.extract("最近一周的漏斗", 7)).thenReturn(new TrackAskIntent("funnel", params, null));

		Object out = endpoint(gateway).handle(call("{\"question\":\"最近一周的漏斗\",\"daysHint\":7}"));

		assertThat(objectMapper.writeValueAsString(out)).isEqualTo("{\"intent\":\"funnel\",\"params\":{\"days\":7,"
			+ "\"eventName\":null,\"windowHours\":24,\"steps\":\"view,click,pay\"},\"warning\":null}");
	}

	@Test
	void theDaysHintIsOptional() throws Exception {
		when(gateway.extract(any(), any())).thenReturn(new TrackAskIntent("overview", Map.of(), null));

		endpoint(gateway).handle(call("{\"question\":\"概览\"}"));
		endpoint(gateway).handle(call("{\"question\":\"概览\",\"daysHint\":null}"));

		verify(gateway, times(2)).extract("概览", null);
	}

	/** 模型认不出意图时，那句提示要跟着回去，track 服务会给用户看 */
	@Test
	void anUnsupportedAnswerKeepsItsWarning() throws Exception {
		when(gateway.extract(any(), any())).thenReturn(TrackAskIntent.unsupported("模型未返回意图"));

		Object out = endpoint(gateway).handle(call("{\"question\":\"随便问问\"}"));

		assertThat(objectMapper.writeValueAsString(out))
			.isEqualTo("{\"intent\":\"unsupported\",\"params\":{},\"warning\":\"模型未返回意图\"}");
	}

	@Test
	void noAnswerStaysNoAnswer() throws Exception {
		when(gateway.extract(any(), any())).thenReturn(null);

		assertThat(endpoint(gateway).handle(call("{\"question\":\"概览\"}"))).isNull();
	}

	@Test
	void refusesARequestItCannotRead() {
		List<String> bad = List.of(
			"{}",
			"{\"question\":null}",
			"{\"question\":\"   \"}",
			"{\"question\":7}",
			"{\"question\":[\"概览\"]}",
			"[\"概览\"]",
			"{\"question\":\"概览\",\"daysHint\":\"7\"}",
			"{\"question\":\"概览\",\"daysHint\":1.5}",
			"{\"question\":\"概览\",\"daysHint\":3000000000}");
		for (String json : bad) {
			assertThatThrownBy(() -> endpoint(gateway).handle(call(json)))
				.as(json)
				.isInstanceOf(ServiceException.class);
		}
		verifyNoInteractions(gateway);
	}

	@Test
	void saysSoWhenTheAiModuleIsNotThere() {
		assertThatThrownBy(() -> endpoint(null).handle(call("{\"question\":\"概览\"}")))
			.isInstanceOf(ServiceException.class)
			.hasMessage("AI 模块未装配，无法解析提问");
	}

	/** 模块被关掉、没配默认模型、模型调不通，都由本地实现抛出，这里不加工 */
	@Test
	void letsTheGatewaysFailureThrough() {
		ServiceException business = new ServiceException("还没有配置默认的对话模型");
		when(gateway.extract(any(), any())).thenThrow(business);

		assertThatThrownBy(() -> endpoint(gateway).handle(call("{\"question\":\"概览\"}"))).isSameAs(business);
	}
}
