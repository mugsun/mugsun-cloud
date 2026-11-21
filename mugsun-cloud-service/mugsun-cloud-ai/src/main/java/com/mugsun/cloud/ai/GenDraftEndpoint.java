package com.mugsun.cloud.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.mugsun.boot.gen.GenAiDraftEnhancer;
import com.mugsun.cloud.common.inner.InnerCall;
import com.mugsun.cloud.common.inner.InnerEndpoint;
import com.mugsun.cloud.common.inner.InnerPaths;
import com.mugsun.core.tool.exception.ServiceException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 内部接口：用大模型把一句话的建表描述变成代码生成的建模草稿，给 system 服务的「规则建模」用。
 *
 * <p>出草稿用的是 mugsun-boot 的 {@code GenAiDraftEnhancerImpl}，这里只负责收发。草稿只是候选，不落库也不建表：
 * 要不要采用、表名字段名合不合规，由 system 服务在用户确认时再检查。
 *
 * <p>请求：{@code {"op": "candidate" 或 "description", "text": "描述"}}，分别对应 {@link GenAiDraftEnhancer} 的两个方法。
 * 响应：{@code candidate} 是 {@code {"present": true, "table": {…}, "columns": […]}}，
 * {@code description} 是 {@code {"present": true, "description": "…"}}；没有结果时只有 {@code {"present": false}}。
 */
@Component
class GenDraftEndpoint implements InnerEndpoint {

	static final String OP_CANDIDATE = "candidate";

	static final String OP_DESCRIPTION = "description";

	private final ObjectProvider<GenAiDraftEnhancer> enhancer;

	GenDraftEndpoint(ObjectProvider<GenAiDraftEnhancer> enhancer) {
		this.enhancer = enhancer;
	}

	@Override
	public String path() {
		return InnerPaths.AI_GEN_DRAFT;
	}

	@Override
	public Object handle(InnerCall call) {
		JsonNode body = call.body();
		String op = textOf(body, "op");
		String text = textOf(body, "text");
		if (text == null || text.isBlank()) {
			throw new ServiceException("请求里没有建表的描述");
		}
		if (!OP_CANDIDATE.equals(op) && !OP_DESCRIPTION.equals(op)) {
			throw new ServiceException("请求里的 op 只能是 candidate 或 description");
		}
		GenAiDraftEnhancer local = enhancer.getIfAvailable();
		if (local == null) {
			throw new ServiceException("AI 模块未装配，无法生成建模草稿");
		}
		Map<String, Object> out = new LinkedHashMap<>();
		if (OP_CANDIDATE.equals(op)) {
			Optional<Map<String, Object>> candidate = local.draftCandidate(text);
			out.put("present", candidate.isPresent());
			if (candidate.isPresent()) {
				out.put("table", candidate.get().get("table"));
				out.put("columns", candidate.get().get("columns"));
			}
		} else {
			Optional<String> description = local.enhanceDescription(text);
			out.put("present", description.isPresent());
			description.ifPresent(value -> out.put("description", value));
		}
		return out;
	}

	private static String textOf(JsonNode body, String field) {
		JsonNode node = body == null ? null : body.get(field);
		return node != null && node.isTextual() ? node.textValue() : null;
	}
}
