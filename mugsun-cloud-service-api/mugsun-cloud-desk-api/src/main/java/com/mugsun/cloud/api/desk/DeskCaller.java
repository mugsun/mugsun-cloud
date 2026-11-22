package com.mugsun.cloud.api.desk;

import com.fasterxml.jackson.databind.JsonNode;
import com.mugsun.boot.system.service.NoticeViewer;
import com.mugsun.cloud.common.inner.InnerCallException;
import com.mugsun.cloud.common.inner.InnerCallException.Kind;
import com.mugsun.cloud.common.inner.InnerClient;
import com.mugsun.core.tool.exception.ServiceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 对工作台服务发内部调用时，两个转发类共用的部分：认服务名、在请求里写上是谁在看、把没调成变成给用户看的话。
 */
final class DeskCaller {

	static final String BUSY = "工作台服务正忙，请稍后再试";

	private static final Logger log = LoggerFactory.getLogger(DeskCaller.class);

	/** 和内部调用认的服务名格式一致 */
	private static final Pattern SERVICE_NAME = Pattern.compile("[A-Za-z0-9._-]{1,64}");

	private final InnerClient client;

	private final String service;

	/**
	 * @param service 工作台服务的服务名，来自配置项 {@code mugsun.cloud.desk.service-id}
	 */
	DeskCaller(InnerClient client, String service) {
		String name = service == null ? "" : service.trim();
		if (!SERVICE_NAME.matcher(name).matches()) {
			throw new IllegalStateException("配置项 " + DeskApiAutoConfiguration.SERVICE_ID_KEY
				+ " 的值不能当服务名用：" + service);
		}
		this.client = client;
		this.service = name;
	}

	/** 两个接口的请求里都有的三项：谁在看、他有没有公告管理权限、最多要几条 */
	static Map<String, Object> request(NoticeViewer viewer, int limit) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put(DeskApi.FIELD_USER_ID, viewer.userId());
		body.put(DeskApi.FIELD_MANAGE, viewer.manage());
		body.put(DeskApi.FIELD_LIMIT, limit);
		return body;
	}

	/**
	 * 调一次。没调成时原因只记日志，给用户的话里不带服务名和路径。
	 *
	 * @param unavailable 没调成时给用户看的话
	 */
	JsonNode call(String path, Map<String, Object> body, String unavailable) {
		try {
			return client.call(service, path, body);
		} catch (InnerCallException e) {
			log.warn("调用 {} 的 {} 没有成功：{}", service, path, e.getMessage());
			throw new ServiceException(e.kind() == Kind.BUSY ? BUSY : unavailable);
		}
	}

	/**
	 * 对方返回的内容对不上约定的格式：记下哪里不对，按没调成报。
	 *
	 * @param what 哪里不对，接在「返回的内容里」后面
	 */
	ServiceException malformed(String path, String what, String unavailable) {
		log.warn("{} 的 {} 返回的内容里{}，不采信", service, path, what);
		return new ServiceException(unavailable);
	}
}
