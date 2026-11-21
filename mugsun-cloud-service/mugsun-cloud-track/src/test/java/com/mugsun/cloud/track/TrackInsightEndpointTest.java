package com.mugsun.cloud.track;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mugsun.boot.common.module.ModuleInsightPort;
import com.mugsun.boot.track.TrackInsightAdapter;
import com.mugsun.cloud.common.inner.InnerCall;
import com.mugsun.core.tool.exception.ServiceException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TrackInsightEndpointTest {

	private final ObjectMapper objectMapper = new ObjectMapper();

	@SuppressWarnings("unchecked")
	private TrackInsightEndpoint endpoint(ModuleInsightPort... ports) {
		ObjectProvider<ModuleInsightPort> provider = mock(ObjectProvider.class);
		when(provider.orderedStream()).thenAnswer(invocation -> Stream.of(ports));
		return new TrackInsightEndpoint(provider, objectMapper);
	}

	/** 路径是和 ai 服务约好的，改了两边就对不上 */
	@Test
	void listensOnTheAgreedPath() {
		assertThat(endpoint().path()).isEqualTo("/inner/track/insight/snapshot");
	}

	/** boot 的 TrackInsightAdapter 报的模块名要和这里找的一致；调用方指定的应用要原样交给它 */
	@Test
	@SuppressWarnings("unchecked")
	void findsTheAdapterShippedWithBootAndPassesTheAppKeyOn() {
		TrackInsightAdapter adapter = mock(TrackInsightAdapter.class);
		when(adapter.module()).thenCallRealMethod();
		when(adapter.snapshot(any())).thenReturn(Map.of("source", "track_app"));
		ObjectNode body = objectMapper.createObjectNode().put("appKey", "web-console");

		Object out = endpoint(adapter).handle(new InnerCall("mugsun-ai", "000000", body));

		assertThat(out).isEqualTo(Map.of("source", "track_app"));
		ArgumentCaptor<Map<String, Object>> request = ArgumentCaptor.forClass(Map.class);
		verify(adapter).snapshot(request.capture());
		assertThat(request.getValue()).containsEntry("appKey", "web-console");
	}

	@Test
	void usesTheMonolithWordingWhenTheModuleIsNotThere() {
		InnerCall call = new InnerCall("mugsun-ai", "000000", objectMapper.createObjectNode());

		assertThatThrownBy(() -> endpoint().handle(call))
			.isInstanceOf(ServiceException.class)
			.hasMessage("埋点模块未装配，无法查询埋点数据");
	}
}
