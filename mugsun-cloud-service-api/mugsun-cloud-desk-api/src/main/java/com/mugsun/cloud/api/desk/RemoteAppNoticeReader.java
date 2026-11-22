package com.mugsun.cloud.api.desk;

import com.fasterxml.jackson.databind.JsonNode;
import com.mugsun.boot.app.AppNoticeReader;
import com.mugsun.boot.app.dto.AppNoticeItemVO;
import com.mugsun.boot.app.dto.AppNoticeSummaryVO;
import com.mugsun.boot.system.service.NoticeViewer;
import com.mugsun.cloud.common.inner.InnerClient;
import com.mugsun.cloud.common.inner.InnerPaths;

import java.util.ArrayList;
import java.util.List;

/**
 * 取手机端首页要的公告（未读条数和最近几条），转给工作台服务。
 *
 * <p>单体版里手机端首页直接调同一个进程里的 {@code AppNoticeService}。微服务版里公告在工作台服务，首页还在 system：
 * 它拿到的 {@link AppNoticeReader} 是这个类，取一次就是对工作台服务的一次内部调用，那边执行的仍然是
 * {@code AppNoticeService}。请求和响应的写法见 {@link DeskApi}。
 *
 * <p>内部调用只带租户，不带登录用户，所以是谁在看（用户编号、有没有公告管理权限）放在请求里。传谁由调用方负责：
 * 只能传当前登录用户自己的，不能拿前端传来的编号来取。租户用的是当前请求的租户，和单体版里查公告时用的是同一个。
 *
 * <p>工作台服务没在运行或者没调成时报「公告暂时取不到」，不当成没有公告：首页上显示 0 条未读会让人以为没有新公告。
 * 对方返回的内容对不上约定的格式时也这样报。
 */
public class RemoteAppNoticeReader implements AppNoticeReader {

	static final String UNAVAILABLE = "公告暂时取不到，请稍后再试";

	private static final String PATH = InnerPaths.DESK_APP_NOTICE_SUMMARY;

	private final DeskCaller desk;

	/**
	 * @param service 工作台服务的服务名，来自配置项 {@code mugsun.cloud.desk.service-id}
	 */
	public RemoteAppNoticeReader(InnerClient client, String service) {
		this.desk = new DeskCaller(client, service);
	}

	@Override
	public AppNoticeSummaryVO summaryOf(NoticeViewer viewer, int limit) {
		JsonNode data = desk.call(PATH, DeskCaller.request(viewer, limit), UNAVAILABLE);
		Long unread = data == null ? null : DeskApi.wholeNumber(data.get(DeskApi.FIELD_UNREAD));
		if (unread == null || unread < 0) {
			throw desk.malformed(PATH, "没有未读条数", UNAVAILABLE);
		}
		JsonNode recent = data.get(DeskApi.FIELD_RECENT);
		if (recent == null || !recent.isArray()) {
			throw desk.malformed(PATH, "没有公告列表", UNAVAILABLE);
		}
		List<AppNoticeItemVO> items = new ArrayList<>(recent.size());
		for (JsonNode notice : recent) {
			Long id = DeskApi.wholeNumber(notice.get(DeskApi.FIELD_ID));
			JsonNode unreadFlag = notice.get(DeskApi.FIELD_UNREAD);
			if (id == null || unreadFlag == null || !unreadFlag.isBoolean()) {
				throw desk.malformed(PATH, "有一条公告没有编号，或者没说读没读过", UNAVAILABLE);
			}
			items.add(new AppNoticeItemVO(id, text(notice, DeskApi.FIELD_TITLE), text(notice, DeskApi.FIELD_TIME),
				unreadFlag.booleanValue()));
		}
		return new AppNoticeSummaryVO(unread, items);
	}

	/** 字段没有或者是 null 时返回 null，和那边这个字段为空时一样 */
	private static String text(JsonNode notice, String field) {
		JsonNode node = notice.get(field);
		return node == null || node.isNull() ? null : node.asText();
	}
}
