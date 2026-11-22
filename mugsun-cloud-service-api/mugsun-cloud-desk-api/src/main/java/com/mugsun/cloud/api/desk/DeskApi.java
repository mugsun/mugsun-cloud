package com.mugsun.cloud.api.desk;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 工作台内部接口里两端约好的东西：请求和响应的字段名，和数字怎么读。
 *
 * <p>调用的一端是 {@link RemoteAppNoticeReader} 和 {@link RemoteNoticeTitleFinder}，接收的一端是工作台服务的
 * {@code AppNoticeSummaryEndpoint} 和 {@code NoticeTitlesEndpoint}，两边都从这里取，免得写得不一样。
 * 路径登记在公共层的 {@code InnerPaths}。
 *
 * <p>两个接口的请求里都要说明是谁在看：{@code userId} 是用户编号，{@code manage} 是这个人有没有公告管理权限
 * （有的话本租户的公告都看得到，没有的话只看得到发给所有人的、发给他本人的和发给他所在部门的）。
 * 内部调用只带租户，不带登录用户，所以这两样由调用方给：只能给当前登录用户自己的，权限也由调用方查好。
 * {@code limit} 是最多要几条，1 到 {@link #MAX_LIMIT}。
 *
 * <p>首页要的公告（{@code InnerPaths.DESK_APP_NOTICE_SUMMARY}）：请求
 * {@code {"userId": 用户编号, "manage": true|false, "limit": 条数}}；响应
 * {@code {"unread": 未读条数, "recent": [{"id": …, "title": "…", "time": "…", "unread": true|false}]}}，
 * 置顶的在前，然后新的在前，一条也没有时 {@code recent} 是空数组。每一条的内容和手机端公告列表里的一条相同。
 *
 * <p>按标题找公告（{@code InnerPaths.DESK_NOTICE_TITLES}）：请求比上面多一项 {@code "keyword": "搜索词"}；
 * 响应 {@code {"titles": ["…", "…"]}}，新的在前，没找到时是空数组。
 *
 * <p>编号和条数写成数字或者写成字符串形式的数字，两端都认：各服务的 JSON 配置可能把长整数写成字符串。
 */
public final class DeskApi {

	/** 一次最多要几条。现在的两处调用要的是 3 条和 5 条 */
	public static final int MAX_LIMIT = 50;

	public static final String FIELD_USER_ID = "userId";

	public static final String FIELD_MANAGE = "manage";

	public static final String FIELD_LIMIT = "limit";

	public static final String FIELD_KEYWORD = "keyword";

	/** 在首页要的公告里出现两次：最外层的是未读条数，每一条里的是这一条读没读过 */
	public static final String FIELD_UNREAD = "unread";

	public static final String FIELD_RECENT = "recent";

	public static final String FIELD_ID = "id";

	public static final String FIELD_TITLE = "title";

	public static final String FIELD_TIME = "time";

	public static final String FIELD_TITLES = "titles";

	private DeskApi() {
	}

	/**
	 * 读一个整数：写成数字或者写成字符串形式的数字都认。
	 *
	 * @return 读不出来（没有这一项、是小数、超出长整数的范围、不是数字的字）时返回 null
	 */
	public static Long wholeNumber(JsonNode node) {
		if (node == null) {
			return null;
		}
		if (node.isIntegralNumber() && node.canConvertToLong()) {
			return node.longValue();
		}
		if (node.isTextual()) {
			try {
				return Long.parseLong(node.textValue().trim());
			} catch (NumberFormatException e) {
				return null;
			}
		}
		return null;
	}
}
