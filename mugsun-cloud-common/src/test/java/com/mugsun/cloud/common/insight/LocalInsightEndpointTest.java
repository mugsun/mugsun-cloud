package com.mugsun.cloud.common.insight;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import com.mugsun.boot.common.module.ModuleInsightPort;
import com.mugsun.cloud.common.inner.InnerCall;
import com.mugsun.core.tool.exception.ServiceException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalInsightEndpointTest {

	private static final String PATH = "/inner/gis/insight/snapshot";

	private static final String MISSING = "地理信息模块未装配，无法查询图层";

	private final ObjectMapper objectMapper = new ObjectMapper();

	/** 记下收到的请求，返回固定的结果 */
	private static final class FakePort implements ModuleInsightPort {

		private final String module;

		private final Map<String, Object> result;

		private final List<Map<String, Object>> received = new ArrayList<>();

		private RuntimeException failure;

		FakePort(String module, Map<String, Object> result) {
			this.module = module;
			this.result = result;
		}

		@Override
		public String module() {
			return module;
		}

		@Override
		public Map<String, Object> snapshot(Map<String, Object> request) {
			received.add(request);
			if (failure != null) {
				throw failure;
			}
			return result;
		}
	}

	private LocalInsightEndpoint endpoint(ModuleInsightPort... ports) {
		return new LocalInsightEndpoint(PATH, "gis", MISSING, () -> List.of(ports), objectMapper);
	}

	private InnerCall call(String json) throws Exception {
		return new InnerCall("mugsun-ai", "000000", objectMapper.readTree(json));
	}

	@Test
	void reportsThePathItWasGiven() {
		assertThat(endpoint().path()).isEqualTo(PATH);
	}

	@Test
	void asksThePortOfItsOwnModuleAndReturnsWhatItSays() throws Exception {
		Map<String, Object> gisResult = new LinkedHashMap<>();
		gisResult.put("source", "gis_layer");
		gisResult.put("layerCount", 3L);
		FakePort track = new FakePort("track", Map.of("source", "track_app"));
		FakePort gis = new FakePort("gis", gisResult);

		Object out = endpoint(track, gis).handle(call("{\"question\":\"有几个图层\"}"));

		assertThat(out).isSameAs(gisResult);
		assertThat(track.received).isEmpty();
		assertThat(gis.received).hasSize(1);
	}

	@Test
	void handsTheRequestOverAsAPlainMap() throws Exception {
		FakePort gis = new FakePort("gis", Map.of());

		endpoint(gis).handle(call("{\"appKey\":\"web\",\"days\":7,\"nested\":{\"a\":[1,2]}}"));

		Map<String, Object> request = gis.received.get(0);
		assertThat(request).containsEntry("appKey", "web").containsEntry("days", 7);
		assertThat(request.get("nested")).isEqualTo(Map.of("a", List.of(1, 2)));
	}

	@Test
	void anEmptyObjectBecomesAnEmptyMap() throws Exception {
		FakePort gis = new FakePort("gis", Map.of());

		endpoint(gis).handle(call("{}"));

		assertThat(gis.received.get(0)).isEmpty();
	}

	@Test
	void saysTheModuleIsMissingWhenNoPortMatches() {
		FakePort track = new FakePort("track", Map.of());

		assertThatThrownBy(() -> endpoint(track).handle(call("{}")))
			.isInstanceOf(ServiceException.class)
			.hasMessage(MISSING);
		assertThatThrownBy(() -> endpoint().handle(call("{}")))
			.isInstanceOf(ServiceException.class)
			.hasMessage(MISSING);
		assertThat(track.received).isEmpty();
	}

	@Test
	void refusesABodyThatIsNotAnObject() {
		FakePort gis = new FakePort("gis", Map.of());
		LocalInsightEndpoint endpoint = endpoint(gis);

		for (String json : List.of("[1,2]", "\"text\"", "7", "null")) {
			assertThatThrownBy(() -> endpoint.handle(call(json)))
				.as(json)
				.isInstanceOf(ServiceException.class);
		}
		JsonNode javaNull = null;
		assertThatThrownBy(() -> endpoint.handle(new InnerCall("mugsun-ai", "000000", javaNull)))
			.isInstanceOf(ServiceException.class);
		assertThatThrownBy(() -> endpoint.handle(new InnerCall("mugsun-ai", "000000", NullNode.getInstance())))
			.isInstanceOf(ServiceException.class);
		assertThat(gis.received).isEmpty();
	}

	@Test
	void letsThePortsOwnFailureThrough() {
		FakePort gis = new FakePort("gis", Map.of());
		gis.failure = new ServiceException("地理信息模块未启用");

		assertThatThrownBy(() -> endpoint(gis).handle(call("{}")))
			.isSameAs(gis.failure);
	}
}
