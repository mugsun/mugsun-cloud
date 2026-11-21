package com.mugsun.cloud.track;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import com.mugsun.boot.common.module.TrackAskIntent;
import com.mugsun.cloud.common.inner.InnerCallException;
import com.mugsun.cloud.common.inner.InnerCallException.Kind;
import com.mugsun.cloud.common.inner.InnerClient;
import com.mugsun.cloud.common.inner.ServiceNames;
import com.mugsun.core.tool.exception.ServiceException;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RemoteTrackAskLlmGatewayTest {

	private static final String AI = "mugsun-ai";

	private static final String PATH = "/inner/ai/track-ask/intent";

	private final ObjectMapper objectMapper = new ObjectMapper();

	private final InnerClient client = mock(InnerClient.class);

	private final RemoteTrackAskLlmGateway gateway = new RemoteTrackAskLlmGateway(client,
		new ServiceNames("mugsun-gis", "mugsun-track", AI), objectMapper);

	private void aiIsUp() {
		when(client.reachable(AI)).thenReturn(true);
	}

	private void aiReplies(String json) throws Exception {
		aiIsUp();
		when(client.callSlow(anyString(), anyString(), any())).thenReturn(objectMapper.readTree(json));
	}

	/** 和单体版没装 AI 模块一样：不解析、不报错，TrackAskService 拿到 null 就直接用规则解析，不加提示 */
	@Test
	void returnsNothingWithoutCallingWhenTheAiServiceIsNotThere() {
		when(client.reachable(AI)).thenReturn(false);

		assertThat(gateway.extract("最近访问量", 7)).isNull();

		verify(client, never()).callSlow(anyString(), anyString(), any());
		verify(client, never()).call(anyString(), anyString(), any());
	}

	/** 大模型慢，要用等得久的那种调用；路径和字段名是和 ai 服务约好的 */
	@Test
	void sendsTheQuestionAndTheDaysHintToTheAiService() throws Exception {
		aiReplies("{\"intent\":\"overview\",\"params\":{},\"warning\":null}");

		gateway.extract("最近访问量", 7);

		Map<String, Object> body = new LinkedHashMap<>();
		body.put("question", "最近访问量");
		body.put("daysHint", 7);
		verify(client).callSlow(AI, PATH, body);
	}

	@Test
	void sendsANullDaysHintWhenThereIsNone() throws Exception {
		aiReplies("{\"intent\":\"overview\",\"params\":{},\"warning\":null}");

		gateway.extract("概览", null);

		Map<String, Object> body = new LinkedHashMap<>();
		body.put("question", "概览");
		body.put("daysHint", null);
		verify(client).callSlow(AI, PATH, body);
	}

	@Test
	void rebuildsTheIntentWithItsParams() throws Exception {
		aiReplies("{\"intent\":\"funnel\",\"params\":{\"days\":7,\"eventName\":null,\"dimType\":null,\"dimKey\":null,"
			+ "\"steps\":\"view,click,pay\",\"windowHours\":24,\"fingerprint\":null,\"routePath\":\"/home\",\"limit\":10},"
			+ "\"warning\":null}");

		TrackAskIntent intent = gateway.extract("最近一周的漏斗", 7);

		assertThat(intent.intent()).isEqualTo("funnel");
		assertThat(intent.unsupported()).isFalse();
		assertThat(intent.warning()).isNull();
		assertThat(intent.params().keySet()).containsExactly("days", "eventName", "dimType", "dimKey", "steps",
			"windowHours", "fingerprint", "routePath", "limit");
		assertThat(intent.params()).containsEntry("days", 7).containsEntry("steps", "view,click,pay")
			.containsEntry("windowHours", 24).containsEntry("routePath", "/home").containsEntry("limit", 10)
			.containsEntry("eventName", null);
	}

	/** 模型认不出意图时，TrackAskService 要拿到「不支持」和那句提示，再去试规则解析 */
	@Test
	void keepsAnUnsupportedAnswerAndItsWarning() throws Exception {
		aiReplies("{\"intent\":\"unsupported\",\"params\":{},\"warning\":\"模型未返回意图\"}");

		TrackAskIntent intent = gateway.extract("随便问问", null);

		assertThat(intent.unsupported()).isTrue();
		assertThat(intent.warning()).isEqualTo("模型未返回意图");
		assertThat(intent.params()).isEmpty();
	}

	@Test
	void noAnswerFromTheAiServiceStaysNoAnswer() {
		aiIsUp();
		when(client.callSlow(anyString(), anyString(), any())).thenReturn(NullNode.getInstance());

		assertThat(gateway.extract("概览", null)).isNull();
	}

	/** 刚查完还在、发请求时已经下线：也按没有部署处理，不提示「模型调用失败」 */
	@Test
	void treatsAServiceThatJustWentAwayAsNotThere() {
		for (Kind kind : List.of(Kind.NO_INSTANCE, Kind.NOT_CONFIGURED)) {
			InnerClient gone = mock(InnerClient.class);
			when(gone.reachable(AI)).thenReturn(true);
			when(gone.callSlow(anyString(), anyString(), any())).thenThrow(new InnerCallException(kind, "不在了"));
			RemoteTrackAskLlmGateway remote = new RemoteTrackAskLlmGateway(gone,
				new ServiceNames("mugsun-gis", "mugsun-track", AI), objectMapper);

			assertThat(remote.extract("概览", null)).as(kind.name()).isNull();
		}
	}

	/** 这些异常要抛给 TrackAskService：它会改用规则解析，并提示用户「模型调用失败」，和单体版一样 */
	@Test
	void letsRealFailuresThrough() {
		List<RuntimeException> failures = List.of(
			new InnerCallException(Kind.FAILED, "调用 mugsun-ai 的 " + PATH + " 失败：HTTP 500"),
			new InnerCallException(Kind.REJECTED, "mugsun-ai 拒绝了内部调用 " + PATH),
			new ServiceException("还没有配置默认的对话模型"));
		for (RuntimeException failure : failures) {
			InnerClient failing = mock(InnerClient.class);
			when(failing.reachable(AI)).thenReturn(true);
			when(failing.callSlow(anyString(), anyString(), any())).thenThrow(failure);
			RemoteTrackAskLlmGateway remote = new RemoteTrackAskLlmGateway(failing,
				new ServiceNames("mugsun-gis", "mugsun-track", AI), objectMapper);

			assertThatThrownBy(() -> remote.extract("概览", null)).isSameAs(failure);
		}
	}

	@Test
	void refusesAReplyItCannotRead() throws Exception {
		for (String json : List.of("[1,2]", "\"funnel\"", "{}", "{\"intent\":7}", "{\"intent\":null,\"params\":{}}")) {
			InnerClient odd = mock(InnerClient.class);
			when(odd.reachable(AI)).thenReturn(true);
			when(odd.callSlow(anyString(), anyString(), any())).thenReturn(objectMapper.readTree(json));
			RemoteTrackAskLlmGateway remote = new RemoteTrackAskLlmGateway(odd,
				new ServiceNames("mugsun-gis", "mugsun-track", AI), objectMapper);

			assertThatThrownBy(() -> remote.extract("概览", null))
				.as(json)
				.isInstanceOf(IllegalStateException.class);
		}
	}

	/** 参数不是对象时按没有参数处理，意图照样可用 */
	@Test
	void toleratesMissingParams() throws Exception {
		aiReplies("{\"intent\":\"overview\"}");

		TrackAskIntent intent = gateway.extract("概览", null);

		assertThat(intent.intent()).isEqualTo("overview");
		assertThat(intent.params()).isEmpty();
		assertThat(intent.warning()).isNull();
	}
}
