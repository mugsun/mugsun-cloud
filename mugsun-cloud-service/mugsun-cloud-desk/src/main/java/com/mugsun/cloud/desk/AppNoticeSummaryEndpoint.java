package com.mugsun.cloud.desk;

import com.mugsun.boot.app.AppNoticeService;
import com.mugsun.boot.app.dto.AppNoticeItemVO;
import com.mugsun.boot.app.dto.AppNoticeSummaryVO;
import com.mugsun.boot.system.service.NoticeViewer;
import com.mugsun.cloud.api.desk.DeskApi;
import com.mugsun.cloud.common.inner.InnerCall;
import com.mugsun.cloud.common.inner.InnerEndpoint;
import com.mugsun.cloud.common.inner.InnerPaths;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 内部接口：取一个用户的公告未读条数和最近几条公告，给还在 system 服务里的手机端首页用。
 *
 * <p>执行的是 mugsun-boot 的 {@link AppNoticeService#summaryOf}，这里只负责收发，口径和手机端公告列表完全一样。
 * 请求是 {@code {"userId": 用户编号, "manage": true|false, "limit": 条数}}，响应是
 * {@code {"unread": 未读条数, "recent": [...]}}，字段名在 {@link DeskApi}。
 *
 * <p>内部调用带过来的只有租户，没有登录用户，所以是谁在看、他有没有公告管理权限都在请求里；给的是不是当前登录用户
 * 自己的，由调用方负责。这里只查库，不读登录态。
 */
@Component
class AppNoticeSummaryEndpoint implements InnerEndpoint {

	private final AppNoticeService notices;

	AppNoticeSummaryEndpoint(AppNoticeService notices) {
		this.notices = notices;
	}

	@Override
	public String path() {
		return InnerPaths.DESK_APP_NOTICE_SUMMARY;
	}

	@Override
	public Object handle(InnerCall call) {
		NoticeViewer viewer = DeskRequests.viewerOf(call.body());
		int limit = DeskRequests.limitOf(call.body());
		AppNoticeSummaryVO summary = notices.summaryOf(viewer, limit);
		List<Map<String, Object>> recent = new ArrayList<>();
		for (AppNoticeItemVO notice : summary.recent()) {
			Map<String, Object> item = new LinkedHashMap<>();
			item.put(DeskApi.FIELD_ID, notice.id());
			item.put(DeskApi.FIELD_TITLE, notice.title());
			item.put(DeskApi.FIELD_TIME, notice.time());
			item.put(DeskApi.FIELD_UNREAD, notice.unread());
			recent.add(item);
		}
		Map<String, Object> data = new LinkedHashMap<>();
		data.put(DeskApi.FIELD_UNREAD, summary.unread());
		data.put(DeskApi.FIELD_RECENT, recent);
		return data;
	}
}
