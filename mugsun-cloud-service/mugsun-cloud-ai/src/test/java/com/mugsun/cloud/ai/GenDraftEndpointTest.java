package com.mugsun.cloud.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mugsun.boot.gen.GenAiDraftEnhancer;
import com.mugsun.boot.gen.entity.GenColumn;
import com.mugsun.boot.gen.entity.GenTable;
import com.mugsun.cloud.common.inner.InnerCall;
import com.mugsun.core.tool.exception.ServiceException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class GenDraftEndpointTest {

	private final ObjectMapper objectMapper = new ObjectMapper();

	private final GenAiDraftEnhancer enhancer = mock(GenAiDraftEnhancer.class);

	@SuppressWarnings("unchecked")
	private GenDraftEndpoint endpoint(GenAiDraftEnhancer local) {
		ObjectProvider<GenAiDraftEnhancer> provider = mock(ObjectProvider.class);
		when(provider.getIfAvailable()).thenReturn(local);
		return new GenDraftEndpoint(provider);
	}

	private InnerCall call(String json) throws Exception {
		return new InnerCall("mugsun-system", "000000", objectMapper.readTree(json));
	}

	/** 路径是和 system 服务约好的，改了两边就对不上 */
	@Test
	void listensOnTheAgreedPath() {
		assertThat(endpoint(enhancer).path()).isEqualTo("/inner/ai/gen/draft");
	}

	@Test
	@SuppressWarnings("unchecked")
	void returnsTheCandidateTableAndColumns() throws Exception {
		GenTable table = new GenTable();
		table.setTableName("biz_order");
		GenColumn column = new GenColumn();
		column.setColumnName("amount");
		List<GenColumn> columns = List.of(column);
		Map<String, Object> candidate = new LinkedHashMap<>();
		candidate.put("table", table);
		candidate.put("columns", columns);
		when(enhancer.draftCandidate("订单表，有金额")).thenReturn(Optional.of(candidate));

		Map<String, Object> out = (Map<String, Object>) endpoint(enhancer)
			.handle(call("{\"op\":\"candidate\",\"text\":\"订单表，有金额\"}"));

		assertThat(out.keySet()).containsExactly("present", "table", "columns");
		assertThat(out.get("present")).isEqualTo(true);
		assertThat(out.get("table")).isSameAs(table);
		assertThat(out.get("columns")).isSameAs(columns);
		verify(enhancer, never()).enhanceDescription("订单表，有金额");
	}

	@Test
	void saysWhenThereIsNoCandidate() throws Exception {
		when(enhancer.draftCandidate("说不清楚")).thenReturn(Optional.empty());

		Object out = endpoint(enhancer).handle(call("{\"op\":\"candidate\",\"text\":\"说不清楚\"}"));

		assertThat(objectMapper.writeValueAsString(out)).isEqualTo("{\"present\":false}");
	}

	@Test
	void returnsTheRewrittenDescription() throws Exception {
		when(enhancer.enhanceDescription("订单表")).thenReturn(Optional.of("biz_order 订单；amount 金额 小数"));
		when(enhancer.enhanceDescription("说不清楚")).thenReturn(Optional.empty());

		Object hit = endpoint(enhancer).handle(call("{\"op\":\"description\",\"text\":\"订单表\"}"));
		Object miss = endpoint(enhancer).handle(call("{\"op\":\"description\",\"text\":\"说不清楚\"}"));

		assertThat(objectMapper.writeValueAsString(hit))
			.isEqualTo("{\"present\":true,\"description\":\"biz_order 订单；amount 金额 小数\"}");
		assertThat(objectMapper.writeValueAsString(miss)).isEqualTo("{\"present\":false}");
	}

	@Test
	void refusesARequestItCannotRead() {
		List<String> bad = List.of(
			"{}",
			"{\"op\":\"candidate\"}",
			"{\"op\":\"candidate\",\"text\":\"  \"}",
			"{\"op\":\"candidate\",\"text\":7}",
			"{\"text\":\"订单表\"}",
			"{\"op\":\"delete\",\"text\":\"订单表\"}",
			"{\"op\":\"CANDIDATE\",\"text\":\"订单表\"}",
			"[\"candidate\",\"订单表\"]");
		for (String json : bad) {
			assertThatThrownBy(() -> endpoint(enhancer).handle(call(json)))
				.as(json)
				.isInstanceOf(ServiceException.class);
		}
		verifyNoInteractions(enhancer);
	}

	@Test
	void saysSoWhenTheAiModuleIsNotThere() {
		assertThatThrownBy(() -> endpoint(null).handle(call("{\"op\":\"candidate\",\"text\":\"订单表\"}")))
			.isInstanceOf(ServiceException.class)
			.hasMessage("AI 模块未装配，无法生成建模草稿");
	}
}
