package com.mugsun.cloud.develop;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.mugsun.boot.gen.entity.GenColumn;
import com.mugsun.boot.gen.entity.GenTable;
import com.mugsun.cloud.common.inner.InnerCallException;
import com.mugsun.cloud.common.inner.InnerCallException.Kind;
import com.mugsun.cloud.common.inner.InnerClient;
import com.mugsun.cloud.common.inner.ServiceNames;
import com.mugsun.core.tool.exception.ServiceException;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RemoteGenAiDraftEnhancerTest {

	private static final String AI = "mugsun-ai";

	private static final String PATH = "/inner/ai/gen/draft";

	/** 和 Spring Boot 配出来的一样：认识时间类型，遇到不认识的字段不报错 */
	private final ObjectMapper objectMapper = JsonMapper.builder()
		.findAndAddModules()
		.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
		.build();

	private final InnerClient client = mock(InnerClient.class);

	private final RemoteGenAiDraftEnhancer enhancer = enhancer(client);

	private RemoteGenAiDraftEnhancer enhancer(InnerClient innerClient) {
		return new RemoteGenAiDraftEnhancer(innerClient, new ServiceNames("mugsun-gis", "mugsun-track", AI), objectMapper);
	}

	private void aiReplies(Object reply) throws Exception {
		when(client.reachable(AI)).thenReturn(true);
		// 先转成文本再读回来，和真正走一趟网络一样
		JsonNode tree = objectMapper.readTree(reply instanceof String json ? json : objectMapper.writeValueAsString(reply));
		when(client.callSlow(anyString(), anyString(), any())).thenReturn(tree);
	}

	private static Map<String, Object> body(String op, String text) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("op", op);
		body.put("text", text);
		return body;
	}

	/** 照 mugsun-boot 的 GenAiDraftEnhancerImpl 写入的字段造一份草稿 */
	private static Map<String, Object> candidateLikeTheAiModuleBuilds() {
		GenTable table = new GenTable();
		table.setTableName("biz_order");
		table.setTableComment("订单");
		table.setEntityName("BizOrder");
		table.setBusinessName("order");
		table.setModuleName("system");
		table.setFunctionName("订单");
		table.setFunctionAuthor("mugsun");
		table.setBasePackage("com.mugsun.boot");
		table.setGenType("zip");
		table.setTplCategory("crud");
		Map<String, Object> candidate = new LinkedHashMap<>();
		candidate.put("table", table);
		candidate.put("columns", List.of(
			column("id", "主键", "Long", "input", 1, 0, 0, "EQ", 1),
			column("amount", "金额", "BigDecimal", "number", 0, 1, 1, "EQ", 2),
			column("remark", "备注", "String", "textarea", 0, 1, 1, "LIKE", 3)));
		return candidate;
	}

	private static GenColumn column(String name, String comment, String javaType, String htmlType, int pk, int list,
									int edit, String queryType, int sort) {
		GenColumn c = new GenColumn();
		c.setColumnName(name);
		c.setColumnComment(comment);
		c.setJavaType(javaType);
		c.setJavaField(name);
		c.setHtmlType(htmlType);
		c.setIsPk(pk);
		c.setIsIncrement(0);
		c.setIsRequired(0);
		c.setIsInsert(1);
		c.setIsEdit(edit);
		c.setIsList(list);
		c.setIsQuery(0);
		c.setQueryType(queryType);
		c.setSort(sort);
		return c;
	}

	/** 和单体版没装 AI 模块一样：没有结果，AiModelService 改用规则解析 */
	@Test
	void givesNothingWithoutCallingWhenTheAiServiceIsNotThere() {
		when(client.reachable(AI)).thenReturn(false);

		assertThat(enhancer.draftCandidate("订单表")).isEmpty();
		assertThat(enhancer.enhanceDescription("订单表")).isEmpty();

		verify(client, never()).callSlow(anyString(), anyString(), any());
	}

	/** 前端拿到的草稿要和单体版一样：类型还是 GenTable 和 GenColumn，转成 JSON 一个字不差 */
	@Test
	@SuppressWarnings("unchecked")
	void rebuildsTheCandidateExactlyAsTheAiModuleMadeIt() throws Exception {
		Map<String, Object> original = candidateLikeTheAiModuleBuilds();
		Map<String, Object> reply = new LinkedHashMap<>();
		reply.put("present", true);
		reply.putAll(original);
		aiReplies(reply);

		Optional<Map<String, Object>> candidate = enhancer.draftCandidate("订单表，有金额和备注");

		verify(client).callSlow(AI, PATH, body("candidate", "订单表，有金额和备注"));
		assertThat(candidate).isPresent();
		assertThat(candidate.get().keySet()).containsExactly("table", "columns");
		assertThat(candidate.get().get("table")).isInstanceOf(GenTable.class);
		GenTable table = (GenTable) candidate.get().get("table");
		assertThat(table.getTableName()).isEqualTo("biz_order");
		assertThat(table.getEntityName()).isEqualTo("BizOrder");
		assertThat(table.getTplCategory()).isEqualTo("crud");
		List<GenColumn> columns = (List<GenColumn>) candidate.get().get("columns");
		assertThat(columns).hasSize(3).allSatisfy(column -> assertThat(column).isInstanceOf(GenColumn.class));
		assertThat(columns.get(1).getColumnName()).isEqualTo("amount");
		assertThat(columns.get(1).getJavaType()).isEqualTo("BigDecimal");
		assertThat(columns.get(2).getQueryType()).isEqualTo("LIKE");
		assertThat(columns.get(0).getIsPk()).isEqualTo(1);
		assertThat(objectMapper.writeValueAsString(candidate.get())).isEqualTo(objectMapper.writeValueAsString(original));
	}

	@Test
	void givesNothingWhenTheModelProducedNoCandidate() throws Exception {
		aiReplies("{\"present\":false}");

		assertThat(enhancer.draftCandidate("说不清楚")).isEmpty();
	}

	/** ai 服务新加了字段、这边还没升级时，不认识的字段忽略掉，草稿照样可用 */
	@Test
	void ignoresFieldsItDoesNotKnow() throws Exception {
		aiReplies("{\"present\":true,\"table\":{\"tableName\":\"biz_order\",\"addedLater\":1},"
			+ "\"columns\":[{\"columnName\":\"amount\",\"addedLater\":true}],\"extra\":\"x\"}");

		Optional<Map<String, Object>> candidate = enhancer.draftCandidate("订单表");

		assertThat(candidate).isPresent();
		assertThat(((GenTable) candidate.get().get("table")).getTableName()).isEqualTo("biz_order");
	}

	@Test
	void givesNothingWhenTheCandidateCannotBeRead() throws Exception {
		List<String> odd = List.of(
			"{\"present\":true}",
			"{\"present\":true,\"table\":{\"tableName\":\"biz_order\"}}",
			"{\"present\":true,\"table\":\"biz_order\",\"columns\":[]}",
			"{\"present\":true,\"table\":{\"tableName\":\"biz_order\"},\"columns\":{\"a\":1}}",
			"{\"present\":true,\"table\":{\"tableName\":{\"nested\":1}},\"columns\":[]}",
			"{\"present\":true,\"table\":{\"tableName\":\"biz_order\"},\"columns\":[\"amount\"]}",
			"[1,2]",
			"null");
		for (String json : odd) {
			InnerClient oddClient = mock(InnerClient.class);
			when(oddClient.reachable(AI)).thenReturn(true);
			when(oddClient.callSlow(anyString(), anyString(), any())).thenReturn(objectMapper.readTree(json));

			assertThat(enhancer(oddClient).draftCandidate("订单表")).as(json).isEmpty();
		}
	}

	@Test
	void returnsTheRewrittenDescription() throws Exception {
		aiReplies("{\"present\":true,\"description\":\"biz_order 订单；amount 金额 小数\"}");

		Optional<String> description = enhancer.enhanceDescription("订单表");

		verify(client).callSlow(AI, PATH, body("description", "订单表"));
		assertThat(description).contains("biz_order 订单；amount 金额 小数");
	}

	@Test
	void givesNoDescriptionWhenThereIsNone() throws Exception {
		for (String json : List.of("{\"present\":false}", "{\"present\":true}", "{\"present\":true,\"description\":7}",
			"{\"description\":\"没有 present\"}", "null")) {
			InnerClient oddClient = mock(InnerClient.class);
			when(oddClient.reachable(AI)).thenReturn(true);
			when(oddClient.callSlow(anyString(), anyString(), any())).thenReturn(objectMapper.readTree(json));

			assertThat(enhancer(oddClient).enhanceDescription("订单表")).as(json).isEmpty();
		}
	}

	/** 单体版的实现遇到任何失败都不报错，只是没有结果；这里调不通也一样，建模接口不能因为 ai 服务出问题而报错 */
	@Test
	void neverFailsWhenTheCallDoes() {
		List<RuntimeException> failures = List.of(
			new InnerCallException(Kind.FAILED, "调用 mugsun-ai 的 " + PATH + " 失败：HTTP 500"),
			new InnerCallException(Kind.REJECTED, "mugsun-ai 拒绝了内部调用 " + PATH),
			new InnerCallException(Kind.NO_INSTANCE, "注册中心里没有 mugsun-ai 的实例"),
			new ServiceException("AI 模块未装配，无法生成建模草稿"),
			new IllegalStateException("意料之外"));
		for (RuntimeException failure : failures) {
			InnerClient failing = mock(InnerClient.class);
			when(failing.reachable(AI)).thenReturn(true);
			when(failing.callSlow(anyString(), anyString(), any())).thenThrow(failure);

			assertThat(enhancer(failing).draftCandidate("订单表")).as(failure.toString()).isEmpty();
			assertThat(enhancer(failing).enhanceDescription("订单表")).as(failure.toString()).isEmpty();
		}
	}
}
