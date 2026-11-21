package com.mugsun.cloud.ai;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.JsonNodeFeature;
import com.fasterxml.jackson.databind.node.NullNode;
import com.mugsun.boot.common.module.ModuleInsightPort;
import com.mugsun.cloud.common.inner.InnerCallException;
import com.mugsun.cloud.common.inner.InnerCallException.Kind;
import com.mugsun.cloud.common.inner.InnerClient;
import com.mugsun.cloud.common.inner.ServiceNames;
import com.mugsun.core.tool.exception.ServiceException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RemoteInsightPortsTest {

	private static final String GIS_PATH = "/inner/gis/insight/snapshot";

	private static final String TRACK_PATH = "/inner/track/insight/snapshot";

	private final ObjectMapper objectMapper = new ObjectMapper();

	private final InnerClient client = mock(InnerClient.class);

	private final ServiceNames names = new ServiceNames("mugsun-gis", "mugsun-track", "mugsun-ai");

	private final RemoteInsightPorts config = new RemoteInsightPorts();

	private ModuleInsightPort gis() {
		return config.gisInsightPort(names, client, objectMapper);
	}

	private ModuleInsightPort track() {
		return config.trackInsightPort(names, client, objectMapper);
	}

	/** 和 InnerClient 读响应的方式一样：小数按 BigDecimal 读，末尾的 0 留着 */
	private JsonNode tree(String json) throws Exception {
		return objectMapper.reader()
			.with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
			.without(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES)
			.readTree(json);
	}

	private void clientFailsWith(RuntimeException failure) {
		when(client.call(anyString(), anyString(), any())).thenThrow(failure);
	}

	/** AiWeaveController 按这两个名字找实现 */
	@Test
	void reportTheModulesTheControllerLooksFor() {
		assertThat(gis().module()).isEqualTo("gis");
		assertThat(track().module()).isEqualTo("track");
	}

	@Test
	void asksTheGisServiceAndHandsBackAPlainMap() throws Exception {
		Map<String, Object> request = Map.of("question", "有几个图层");
		when(client.call("mugsun-gis", GIS_PATH, request)).thenReturn(tree("{\"source\":\"gis_layer\",\"layerCount\":2,"
			+ "\"featureCount\":15,\"layers\":[{\"id\":\"1234567890123456789\",\"name\":\"道路\",\"kind\":\"line\","
			+ "\"featureCount\":10}]}"));

		Map<String, Object> out = gis().snapshot(request);

		assertThat(out).isInstanceOf(LinkedHashMap.class);
		assertThat(out.keySet()).containsExactly("source", "layerCount", "featureCount", "layers");
		assertThat(out).containsEntry("source", "gis_layer").containsEntry("layerCount", 2).containsEntry("featureCount", 15);
		Map<String, Object> layer = new LinkedHashMap<>();
		layer.put("id", "1234567890123456789");
		layer.put("name", "道路");
		layer.put("kind", "line");
		layer.put("featureCount", 10);
		assertThat(out.get("layers")).isEqualTo(List.of(layer));
	}

	@Test
	void asksTheTrackServiceWithWhatTheUserSent() throws Exception {
		Map<String, Object> request = new LinkedHashMap<>();
		request.put("appKey", "web-console");
		request.put("question", "最近访问量怎么样");
		when(client.call("mugsun-track", TRACK_PATH, request)).thenReturn(tree("{\"source\":\"track_app\",\"appCount\":1}"));

		Map<String, Object> out = track().snapshot(request);

		assertThat(out).containsEntry("source", "track_app").containsEntry("appCount", 1);
		verify(client).call("mugsun-track", TRACK_PATH, request);
	}

	@Test
	void sendsAnEmptyObjectWhenThereIsNoRequest() throws Exception {
		when(client.call("mugsun-gis", GIS_PATH, Map.of())).thenReturn(tree("{\"layerCount\":0}"));

		assertThat(gis().snapshot(null)).containsEntry("layerCount", 0);
	}

	/** 比率这类小数要原样转给前端，不能在这一跳丢位数 */
	@Test
	void keepsEveryDigitOfDecimals() throws Exception {
		when(client.call(anyString(), anyString(), any()))
			.thenReturn(tree("{\"overview\":{\"bounceRate\":0.10,\"avg\":12345678901234567890.123456789}}"));

		Map<String, Object> out = track().snapshot(Map.of());

		Map<?, ?> overview = (Map<?, ?>) out.get("overview");
		assertThat(overview.get("bounceRate")).isEqualTo(new BigDecimal("0.10"));
		assertThat(overview.get("avg")).isEqualTo(new BigDecimal("12345678901234567890.123456789"));
	}

	/**
	 * 和单体版没装那个模块时一样，告诉用户查不了，而不是报一个看不懂的错。
	 * 注册中心里没有实例，可能是没部署，也可能是正在重启或者挂了，所以话里不说「没有部署」
	 */
	@Test
	void saysSoWhenTheOtherServiceHasNoInstance() {
		clientFailsWith(new InnerCallException(Kind.NO_INSTANCE, "注册中心里没有 mugsun-gis 的实例"));

		assertThatThrownBy(() -> gis().snapshot(Map.of()))
			.isInstanceOf(ServiceException.class)
			.hasMessage("地理信息服务现在不在线，无法查询图层。请稍后再试，一直不行请联系管理员");
		assertThatThrownBy(() -> track().snapshot(Map.of()))
			.isInstanceOf(ServiceException.class)
			.hasMessage("埋点服务现在不在线，无法查询埋点数据。请稍后再试，一直不行请联系管理员");
	}

	@Test
	void saysSoWhenInnerCallsAreNotSwitchedOn() {
		clientFailsWith(new InnerCallException(Kind.NOT_CONFIGURED, "没有配置 mugsun.cloud.inner.secret，内部调用停用"));

		assertThatThrownBy(() -> gis().snapshot(Map.of()))
			.isInstanceOf(ServiceException.class)
			.hasMessage("服务之间的内部调用没有开通，无法查询图层，请联系管理员");
	}

	/** 这些消息会给用户看：服务名、路径、状态码、异常类名都不能带出去 */
	@Test
	void hidesTheDetailsOfEveryOtherFailure() {
		List<RuntimeException> failures = List.of(
			new InnerCallException(Kind.REJECTED, "mugsun-gis 拒绝了内部调用 " + GIS_PATH),
			new InnerCallException(Kind.FAILED, "调用 mugsun-gis 的 " + GIS_PATH + " 失败：HTTP 500"),
			new IllegalArgumentException("服务名或内部接口路径不合规：mugsun-gis " + GIS_PATH),
			new IllegalStateException("java.net.ConnectException: Connection refused"));
		for (RuntimeException failure : failures) {
			InnerClient failing = mock(InnerClient.class);
			when(failing.call(anyString(), anyString(), any())).thenThrow(failure);
			ModuleInsightPort port = config.gisInsightPort(names, failing, objectMapper);

			assertThatThrownBy(() -> port.snapshot(Map.of()))
				.as(failure.toString())
				.isInstanceOf(ServiceException.class)
				.hasMessage("地理信息服务暂时不可用，请稍后再试");
		}
	}

	/** 对方自己的业务提示（比如模块被系统参数关掉了）和单体版一样原样给用户 */
	@Test
	void passesTheOtherSidesOwnMessageOn() {
		ServiceException business = new ServiceException("埋点模块未启用");
		clientFailsWith(business);

		assertThatThrownBy(() -> track().snapshot(Map.of())).isSameAs(business);
	}

	@Test
	void refusesAReplyThatIsNotAnObject() throws Exception {
		for (JsonNode reply : List.of(NullNode.getInstance(), tree("[1,2]"), tree("\"ok\""))) {
			InnerClient odd = mock(InnerClient.class);
			when(odd.call(anyString(), anyString(), any())).thenReturn(reply);
			ModuleInsightPort port = config.trackInsightPort(names, odd, objectMapper);

			assertThatThrownBy(() -> port.snapshot(Map.of()))
				.as(reply.toString())
				.isInstanceOf(ServiceException.class)
				.hasMessage("埋点服务暂时不可用，请稍后再试");
		}
	}
}
