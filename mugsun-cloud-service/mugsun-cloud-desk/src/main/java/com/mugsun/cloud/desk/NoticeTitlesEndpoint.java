package com.mugsun.cloud.desk;

import com.mugsun.boot.system.service.NoticeReadService;
import com.mugsun.boot.system.service.NoticeViewer;
import com.mugsun.cloud.api.desk.DeskApi;
import com.mugsun.cloud.common.inner.InnerCall;
import com.mugsun.cloud.common.inner.InnerEndpoint;
import com.mugsun.cloud.common.inner.InnerPaths;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 内部接口：按标题找一个用户看得到的公告，给还在 system 服务里的全局搜索（顶栏的搜索框）用。
 *
 * <p>执行的是 mugsun-boot 的 {@link NoticeReadService#titlesLike}，这里只负责收发。
 * 请求是 {@code {"userId": 用户编号, "manage": true|false, "limit": 条数, "keyword": "搜索词"}}，
 * 响应是 {@code {"titles": ["…"]}}，字段名在 {@link DeskApi}。
 *
 * <p>内部调用带过来的只有租户，没有登录用户，所以是谁在找、他有没有公告管理权限都在请求里；给的是不是当前登录用户
 * 自己的，由调用方负责。这里只查库，不读登录态。
 */
@Component
class NoticeTitlesEndpoint implements InnerEndpoint {

	private final NoticeReadService notices;

	NoticeTitlesEndpoint(NoticeReadService notices) {
		this.notices = notices;
	}

	@Override
	public String path() {
		return InnerPaths.DESK_NOTICE_TITLES;
	}

	@Override
	public Object handle(InnerCall call) {
		NoticeViewer viewer = DeskRequests.viewerOf(call.body());
		int limit = DeskRequests.limitOf(call.body());
		String keyword = DeskRequests.keywordOf(call.body());
		return Map.of(DeskApi.FIELD_TITLES, notices.titlesLike(viewer, keyword, limit));
	}
}
