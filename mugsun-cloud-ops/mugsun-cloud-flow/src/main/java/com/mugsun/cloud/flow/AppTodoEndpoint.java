package com.mugsun.cloud.flow;

import com.fasterxml.jackson.databind.JsonNode;
import com.mugsun.boot.app.AppTodoService;
import com.mugsun.boot.app.dto.AppTodoItemVO;
import com.mugsun.cloud.api.flow.FlowApi;
import com.mugsun.cloud.common.inner.InnerCall;
import com.mugsun.cloud.common.inner.InnerEndpoint;
import com.mugsun.cloud.common.inner.InnerPaths;
import com.mugsun.core.tool.exception.ServiceException;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 内部接口：取一个用户的待办，给还在 system 服务里的手机端首页用（首页要显示待办数和前几条待办）。
 *
 * <p>执行的是 mugsun-boot 的 {@link AppTodoService#listOf}，这里只负责收发，口径和手机端待办列表完全一样。
 * 请求是 {@code {"userId": 用户编号}}，响应是 {@code {"todos": [...]}}，字段名在 {@link FlowApi}。
 *
 * <p>内部调用带过来的只有租户，没有登录用户，所以用户编号在请求里；传的是不是当前登录用户自己的编号，由调用方负责。
 * 这里只查库，不读登录态。
 */
@Component
class AppTodoEndpoint implements InnerEndpoint {

	private static final String NO_USER = "请求里没有用户编号";

	private final AppTodoService todos;

	AppTodoEndpoint(AppTodoService todos) {
		this.todos = todos;
	}

	@Override
	public String path() {
		return InnerPaths.FLOW_APP_TODOS;
	}

	@Override
	public Object handle(InnerCall call) {
		long userId = userIdOf(call.body());
		List<Map<String, Object>> items = new ArrayList<>();
		for (AppTodoItemVO todo : todos.listOf(userId)) {
			Map<String, Object> item = new LinkedHashMap<>();
			item.put(FlowApi.FIELD_TASK_ID, todo.taskId());
			item.put(FlowApi.FIELD_INSTANCE_ID, todo.instanceId());
			item.put(FlowApi.FIELD_TITLE, todo.title());
			item.put(FlowApi.FIELD_FLOW_NAME, todo.flowName());
			item.put(FlowApi.FIELD_NODE_NAME, todo.nodeName());
			item.put(FlowApi.FIELD_CREATE_TIME, todo.createTime());
			items.add(item);
		}
		return Map.of(FlowApi.FIELD_TODOS, items);
	}

	/** 用户编号可以是数字，也可以是写成字符串的数字（调用方的 JSON 配置可能把长整数写成字符串）；必须是正整数 */
	private static long userIdOf(JsonNode body) {
		JsonNode node = body == null ? null : body.get(FlowApi.FIELD_USER_ID);
		long userId = 0;
		if (node != null && node.isIntegralNumber() && node.canConvertToLong()) {
			userId = node.longValue();
		} else if (node != null && node.isTextual()) {
			try {
				userId = Long.parseLong(node.textValue().trim());
			} catch (NumberFormatException e) {
				throw new ServiceException(NO_USER);
			}
		}
		if (userId <= 0) {
			throw new ServiceException(NO_USER);
		}
		return userId;
	}
}
