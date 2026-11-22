package com.mugsun.cloud.api.flow;

import com.fasterxml.jackson.databind.JsonNode;
import com.mugsun.boot.app.AppTodoReader;
import com.mugsun.boot.app.dto.AppTodoItemVO;
import com.mugsun.cloud.common.inner.InnerCallException;
import com.mugsun.cloud.common.inner.InnerCallException.Kind;
import com.mugsun.cloud.common.inner.InnerClient;
import com.mugsun.cloud.common.inner.InnerPaths;
import com.mugsun.core.tool.exception.ServiceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 取一个用户的待办，转给审批流服务。
 *
 * <p>单体版里手机端首页直接调同一个进程里的 {@code AppTodoService}。微服务版里审批流是单独的服务，首页还在 system：
 * 它拿到的 {@link AppTodoReader} 是这个类，取一次待办就是对审批流服务的一次内部调用，那边执行的仍然是
 * {@code AppTodoService}。请求和响应的写法见 {@link FlowApi}。
 *
 * <p>内部调用只带租户，不带登录用户，所以用户编号放在请求里。传哪个用户由调用方负责：只能传当前登录用户自己的编号，
 * 不能拿前端传来的编号来取。租户用的是当前请求的租户，和单体版里查待办时用的是同一个。
 *
 * <p>审批流服务没在运行或者没调成时报「待办暂时取不到」，不当成没有待办：首页上显示 0 条待办会让人以为没有要办的事。
 * 对方返回的内容对不上约定的格式时也这样报。
 */
public class RemoteAppTodoReader implements AppTodoReader {

	static final String UNAVAILABLE = "待办暂时取不到，请稍后再试";

	static final String BUSY = "审批流服务正忙，请稍后再试";

	private static final Logger log = LoggerFactory.getLogger(RemoteAppTodoReader.class);

	/** 和内部调用认的服务名格式一致 */
	private static final Pattern SERVICE_NAME = Pattern.compile("[A-Za-z0-9._-]{1,64}");

	private final InnerClient client;

	private final String service;

	/**
	 * @param service 审批流服务的服务名，来自配置项 {@code mugsun.cloud.flow.service-id}
	 */
	public RemoteAppTodoReader(InnerClient client, String service) {
		String name = service == null ? "" : service.trim();
		if (!SERVICE_NAME.matcher(name).matches()) {
			throw new IllegalStateException("配置项 " + FlowApiAutoConfiguration.SERVICE_ID_KEY
				+ " 的值不能当服务名用：" + service);
		}
		this.client = client;
		this.service = name;
	}

	@Override
	public List<AppTodoItemVO> listOf(long userId) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put(FlowApi.FIELD_USER_ID, userId);
		JsonNode data;
		try {
			data = client.call(service, InnerPaths.FLOW_APP_TODOS, body);
		} catch (InnerCallException e) {
			log.warn("调用 {} 的 {} 没有成功：{}", service, InnerPaths.FLOW_APP_TODOS, e.getMessage());
			throw new ServiceException(e.kind() == Kind.BUSY ? BUSY : UNAVAILABLE);
		}
		JsonNode todos = data == null ? null : data.get(FlowApi.FIELD_TODOS);
		if (todos == null || !todos.isArray()) {
			log.warn("{} 的 {} 返回的内容里没有待办列表，不采信", service, InnerPaths.FLOW_APP_TODOS);
			throw new ServiceException(UNAVAILABLE);
		}
		List<AppTodoItemVO> items = new ArrayList<>(todos.size());
		for (JsonNode todo : todos) {
			if (!todo.hasNonNull(FlowApi.FIELD_TASK_ID)) {
				log.warn("{} 的 {} 返回的待办里有一条没有任务编号，不采信", service, InnerPaths.FLOW_APP_TODOS);
				throw new ServiceException(UNAVAILABLE);
			}
			items.add(new AppTodoItemVO(
				todo.path(FlowApi.FIELD_TASK_ID).asLong(),
				todo.path(FlowApi.FIELD_INSTANCE_ID).asLong(),
				text(todo, FlowApi.FIELD_TITLE),
				text(todo, FlowApi.FIELD_FLOW_NAME),
				text(todo, FlowApi.FIELD_NODE_NAME),
				text(todo, FlowApi.FIELD_CREATE_TIME)));
		}
		return items;
	}

	/** 字段没有或者是 null 时返回 null，和那边查出来这个字段为空时一样 */
	private static String text(JsonNode todo, String field) {
		JsonNode node = todo.get(field);
		return node == null || node.isNull() ? null : node.asText();
	}
}
