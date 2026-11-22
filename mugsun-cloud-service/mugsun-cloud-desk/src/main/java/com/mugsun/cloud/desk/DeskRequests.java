package com.mugsun.cloud.desk;

import com.fasterxml.jackson.databind.JsonNode;
import com.mugsun.boot.system.service.NoticeViewer;
import com.mugsun.cloud.api.desk.DeskApi;
import com.mugsun.core.tool.exception.ServiceException;

/**
 * 读内部接口的请求里约好的几项：谁在看、他有没有公告管理权限、最多要几条、搜索词。两个内部接口共用。
 *
 * <p>内部调用带过来的只有租户，没有登录用户，所以是谁在看写在请求里；给的是不是当前登录用户自己的、权限查得对不对，
 * 由调用方负责。这里只管请求写得对不对：缺了哪一项、写得不对，都直接回绝，不替调用方猜一个值。
 */
final class DeskRequests {

	private static final String NO_USER = "请求里没有用户编号";

	private static final String NO_MANAGE = "请求里没有说明有没有公告管理权限";

	private static final String BAD_LIMIT = "请求里要的条数不对";

	private static final String NO_KEYWORD = "请求里没有搜索词";

	private DeskRequests() {
	}

	/** 谁在看：用户编号必须是正整数；有没有公告管理权限必须明说，没写的不当成没有 */
	static NoticeViewer viewerOf(JsonNode body) {
		Long userId = DeskApi.wholeNumber(field(body, DeskApi.FIELD_USER_ID));
		if (userId == null || userId <= 0) {
			throw new ServiceException(NO_USER);
		}
		JsonNode manage = field(body, DeskApi.FIELD_MANAGE);
		if (manage == null || !manage.isBoolean()) {
			throw new ServiceException(NO_MANAGE);
		}
		return new NoticeViewer(userId, manage.booleanValue());
	}

	/** 最多要几条：1 到 {@link DeskApi#MAX_LIMIT} */
	static int limitOf(JsonNode body) {
		Long limit = DeskApi.wholeNumber(field(body, DeskApi.FIELD_LIMIT));
		if (limit == null || limit < 1 || limit > DeskApi.MAX_LIMIT) {
			throw new ServiceException(BAD_LIMIT);
		}
		return limit.intValue();
	}

	/** 搜索词：必须是文字，不能是空的。调用方遇到空词不会发起调用 */
	static String keywordOf(JsonNode body) {
		JsonNode keyword = field(body, DeskApi.FIELD_KEYWORD);
		if (keyword == null || !keyword.isTextual() || keyword.textValue().isBlank()) {
			throw new ServiceException(NO_KEYWORD);
		}
		return keyword.textValue();
	}

	private static JsonNode field(JsonNode body, String name) {
		return body == null ? null : body.get(name);
	}
}
