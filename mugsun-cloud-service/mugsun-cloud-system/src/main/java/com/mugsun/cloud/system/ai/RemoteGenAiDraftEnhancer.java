package com.mugsun.cloud.system.ai;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mugsun.boot.gen.GenAiDraftEnhancer;
import com.mugsun.boot.gen.entity.GenColumn;
import com.mugsun.boot.gen.entity.GenTable;
import com.mugsun.cloud.common.inner.InnerClient;
import com.mugsun.cloud.common.inner.InnerPaths;
import com.mugsun.cloud.common.inner.ServiceNames;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 代码生成的「规则建模」请 ai 服务用大模型出建模草稿。
 *
 * <p>单体版里 {@code AiModelService} 拿到的是 AI 模块的 {@code GenAiDraftEnhancerImpl}：装了 AI 模块就先让大模型出草稿，
 * 没装、或者大模型没给出能用的结果，就按规则解析用户写的描述。那个实现遇到任何失败都只是「没有结果」，不报错。
 * 这里也一样：ai 服务没有部署、调不通、返回的内容读不出来，都当作没有结果，由 {@code AiModelService} 改用规则解析。
 *
 * <p>草稿只是给用户编辑的候选，不落库也不建表；用户确认时的检查仍然在 {@code GenModelingController} 里。
 */
@Component
class RemoteGenAiDraftEnhancer implements GenAiDraftEnhancer {

	private static final Logger log = LoggerFactory.getLogger(RemoteGenAiDraftEnhancer.class);

	private static final TypeReference<List<GenColumn>> COLUMNS = new TypeReference<>() {
	};

	private final InnerClient client;

	private final ServiceNames names;

	private final ObjectMapper objectMapper;

	RemoteGenAiDraftEnhancer(InnerClient client, ServiceNames names, ObjectMapper objectMapper) {
		this.client = client;
		this.names = names;
		this.objectMapper = objectMapper;
	}

	@Override
	public Optional<Map<String, Object>> draftCandidate(String naturalLanguage) {
		JsonNode data = ask("candidate", naturalLanguage);
		if (data == null || !data.path("present").asBoolean(false)) {
			return Optional.empty();
		}
		JsonNode tableNode = data.get("table");
		JsonNode columnsNode = data.get("columns");
		if (tableNode == null || !tableNode.isObject() || columnsNode == null || !columnsNode.isArray()) {
			log.warn("{} 返回的建模草稿缺少表或字段，改用规则解析", names.ai());
			return Optional.empty();
		}
		try {
			GenTable table = objectMapper.treeToValue(tableNode, GenTable.class);
			List<GenColumn> columns = objectMapper.readerFor(COLUMNS).readValue(columnsNode);
			Map<String, Object> candidate = new LinkedHashMap<>();
			candidate.put("table", table);
			candidate.put("columns", columns);
			return Optional.of(candidate);
		} catch (IOException | RuntimeException e) {
			log.warn("{} 返回的建模草稿读不出来，改用规则解析：{}", names.ai(), e.toString());
			return Optional.empty();
		}
	}

	@Override
	public Optional<String> enhanceDescription(String naturalLanguage) {
		JsonNode data = ask("description", naturalLanguage);
		if (data == null || !data.path("present").asBoolean(false)) {
			return Optional.empty();
		}
		JsonNode description = data.get("description");
		return description != null && description.isTextual() ? Optional.of(description.textValue()) : Optional.empty();
	}

	/** 调不通返回 null */
	private JsonNode ask(String op, String text) {
		String ai = names.ai();
		if (!client.reachable(ai)) {
			return null;
		}
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("op", op);
		body.put("text", text);
		try {
			return client.callSlow(ai, InnerPaths.AI_GEN_DRAFT, body);
		} catch (RuntimeException e) {
			log.warn("请 {} 生成建模草稿没有成功，改用规则解析：{}", ai, e.getMessage());
			return null;
		}
	}
}
