package com.mugsun.cloud.gis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mugsun.boot.common.module.ModuleInsightPort;
import com.mugsun.boot.gis.GisInsightAdapter;
import com.mugsun.cloud.common.inner.InnerCall;
import com.mugsun.core.tool.exception.ServiceException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GisInsightEndpointTest {

	private final ObjectMapper objectMapper = new ObjectMapper();

	@SuppressWarnings("unchecked")
	private GisInsightEndpoint endpoint(ModuleInsightPort... ports) {
		ObjectProvider<ModuleInsightPort> provider = mock(ObjectProvider.class);
		when(provider.orderedStream()).thenAnswer(invocation -> Stream.of(ports));
		return new GisInsightEndpoint(provider, objectMapper);
	}

	private InnerCall emptyCall() {
		return new InnerCall("mugsun-ai", "000000", objectMapper.createObjectNode());
	}

	/** 路径是和 ai 服务约好的，改了两边就对不上 */
	@Test
	void listensOnTheAgreedPath() {
		assertThat(endpoint().path()).isEqualTo("/inner/gis/insight/snapshot");
	}

	/** boot 的 GisInsightAdapter 报的模块名要和这里找的一致，否则接口永远找不到它 */
	@Test
	void findsTheAdapterShippedWithBoot() {
		GisInsightAdapter adapter = mock(GisInsightAdapter.class);
		when(adapter.module()).thenCallRealMethod();
		when(adapter.snapshot(any())).thenReturn(Map.of("source", "gis_layer"));

		Object out = endpoint(adapter).handle(emptyCall());

		assertThat(out).isEqualTo(Map.of("source", "gis_layer"));
	}

	@Test
	void usesTheMonolithWordingWhenTheModuleIsNotThere() {
		assertThatThrownBy(() -> endpoint().handle(emptyCall()))
			.isInstanceOf(ServiceException.class)
			.hasMessage("地理信息模块未装配，无法查询图层");
	}
}
