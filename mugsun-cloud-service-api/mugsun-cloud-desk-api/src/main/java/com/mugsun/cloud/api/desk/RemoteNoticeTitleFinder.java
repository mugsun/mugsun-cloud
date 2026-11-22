package com.mugsun.cloud.api.desk;

import com.fasterxml.jackson.databind.JsonNode;
import com.mugsun.boot.system.service.NoticeTitleFinder;
import com.mugsun.boot.system.service.NoticeViewer;
import com.mugsun.cloud.common.inner.InnerClient;
import com.mugsun.cloud.common.inner.InnerPaths;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 按标题找公告，转给工作台服务。
 *
 * <p>单体版里顶栏的全局搜索直接调同一个进程里的 {@code NoticeReadService}。微服务版里公告在工作台服务，全局搜索还在 system：
 * 它拿到的 {@link NoticeTitleFinder} 是这个类，找一次就是对工作台服务的一次内部调用，那边执行的仍然是
 * {@code NoticeReadService}。请求和响应的写法见 {@link DeskApi}。
 *
 * <p>内部调用只带租户，不带登录用户，所以是谁在找（用户编号、有没有公告管理权限）放在请求里。传谁由调用方负责：
 * 只能传当前登录用户自己的。搜索词是空的不发起调用，直接算没找到，和 {@code NoticeReadService} 对空词的处理一样。
 *
 * <p>工作台服务没在运行或者没调成时报「公告暂时搜不到」，不当成没找到：搜的人会以为没有这条公告。
 * 对方返回的内容对不上约定的格式时也这样报。
 */
public class RemoteNoticeTitleFinder implements NoticeTitleFinder {

	static final String UNAVAILABLE = "公告暂时搜不到，请稍后再试";

	private static final String PATH = InnerPaths.DESK_NOTICE_TITLES;

	private final DeskCaller desk;

	/**
	 * @param service 工作台服务的服务名，来自配置项 {@code mugsun.cloud.desk.service-id}
	 */
	public RemoteNoticeTitleFinder(InnerClient client, String service) {
		this.desk = new DeskCaller(client, service);
	}

	@Override
	public List<String> titlesLike(NoticeViewer viewer, String keyword, int limit) {
		if (keyword == null || keyword.isBlank()) {
			return List.of();
		}
		Map<String, Object> body = DeskCaller.request(viewer, limit);
		body.put(DeskApi.FIELD_KEYWORD, keyword);
		JsonNode data = desk.call(PATH, body, UNAVAILABLE);
		JsonNode titles = data == null ? null : data.get(DeskApi.FIELD_TITLES);
		if (titles == null || !titles.isArray()) {
			throw desk.malformed(PATH, "没有标题列表", UNAVAILABLE);
		}
		List<String> found = new ArrayList<>(titles.size());
		for (JsonNode title : titles) {
			if (!title.isTextual()) {
				throw desk.malformed(PATH, "有一个标题不是文字", UNAVAILABLE);
			}
			found.add(title.textValue());
		}
		return found;
	}
}
