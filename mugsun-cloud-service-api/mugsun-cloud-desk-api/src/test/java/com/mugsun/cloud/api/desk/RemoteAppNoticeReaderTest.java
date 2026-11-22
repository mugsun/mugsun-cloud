package com.mugsun.cloud.api.desk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mugsun.boot.app.dto.AppNoticeItemVO;
import com.mugsun.boot.app.dto.AppNoticeSummaryVO;
import com.mugsun.boot.system.service.NoticeViewer;
import com.mugsun.cloud.common.inner.InnerCallException;
import com.mugsun.cloud.common.inner.InnerCallException.Kind;
import com.mugsun.cloud.common.inner.InnerClient;
import com.mugsun.core.tool.exception.ServiceException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RemoteAppNoticeReaderTest {

	private static final String DESK = "mugsun-desk";

	/** 路径是和工作台服务约好的，改了两边就对不上 */
	private static final String PATH = "/inner/desk/app/notices/summary";

	/** 没有公告管理权限的普通用户 */
	private static final NoticeViewer STAFF = new NoticeViewer(7L, false);

	private final ObjectMapper objectMapper = new ObjectMapper();

	private final InnerClient client = mock(InnerClient.class);

	private final RemoteAppNoticeReader reader = new RemoteAppNoticeReader(client, DESK);

	/** 让工作台服务返回这段内容。JSON 用单引号写，省得满屏转义 */
	private void answers(String json) throws Exception {
		JsonNode data = objectMapper.readTree(json.replace('\'', '"'));
		when(client.call(anyString(), anyString(), any())).thenReturn(data);
	}

	/** 是谁在看放在请求里：内部调用只带租户，不带登录用户 */
	@Test
	void asksTheDeskServiceForWhatTheViewerCanSee() throws Exception {
		answers("{'unread':2,'recent':["
			+ "{'id':12,'title':'放假通知','time':'2026-11-20 09:30','unread':true},"
			+ "{'id':11,'title':'未命名公告','time':'','unread':false}]}");

		AppNoticeSummaryVO summary = reader.summaryOf(STAFF, 3);

		assertThat(summary).isEqualTo(new AppNoticeSummaryVO(2, List.of(
			new AppNoticeItemVO(12L, "放假通知", "2026-11-20 09:30", true),
			new AppNoticeItemVO(11L, "未命名公告", "", false))));
		verify(client).call(DESK, PATH, Map.of("userId", 7L, "manage", false, "limit", 3));
	}

	/** 有公告管理权限的人看得到全部公告，这一点要告诉工作台服务：那边读不到登录态，查不了权限 */
	@Test
	void tellsTheDeskServiceThatTheViewerManagesNotices() throws Exception {
		answers("{'unread':0,'recent':[]}");

		reader.summaryOf(new NoticeViewer(7L, true), 3);

		verify(client).call(DESK, PATH, Map.of("userId", 7L, "manage", true, "limit", 3));
	}

	/** 编号有十九位，写成数字或者字符串传过来都要读得出来，一位都不能差；未读条数写成字符串也认 */
	@Test
	void readsLongIdsExactlyWhetherSentAsNumbersOrText() throws Exception {
		answers("{'unread':'5','recent':["
			+ "{'id':'1902771234567890123','title':'放假通知','time':'2026-11-20 09:30','unread':true},"
			+ "{'id':1902771234567890125,'title':'值班安排','time':'2026-11-20 09:31','unread':false}]}");

		assertThat(reader.summaryOf(STAFF, 3)).isEqualTo(new AppNoticeSummaryVO(5, List.of(
			new AppNoticeItemVO(1902771234567890123L, "放假通知", "2026-11-20 09:30", true),
			new AppNoticeItemVO(1902771234567890125L, "值班安排", "2026-11-20 09:31", false))));
	}

	@Test
	void noNoticesIsNotAFailure() throws Exception {
		answers("{'unread':0,'recent':[]}");

		assertThat(reader.summaryOf(STAFF, 3)).isEqualTo(new AppNoticeSummaryVO(0, List.of()));
	}

	/** 工作台服务没在运行：报错，不能当成没有公告。给用户的话里不带服务名和路径 */
	@Test
	void failsLoudlyWhenTheDeskServiceIsDown() {
		when(client.call(anyString(), anyString(), any()))
			.thenThrow(new InnerCallException(Kind.NO_INSTANCE, "注册中心里没有 mugsun-desk 的实例"));

		assertThatThrownBy(() -> reader.summaryOf(STAFF, 3))
			.isInstanceOf(ServiceException.class)
			.hasMessage("公告暂时取不到，请稍后再试");
	}

	@Test
	void saysBusyWhenTheDeskServiceIsFull() {
		when(client.call(anyString(), anyString(), any()))
			.thenThrow(new InnerCallException(Kind.BUSY, "mugsun-desk 正忙，这次没有执行"));

		assertThatThrownBy(() -> reader.summaryOf(STAFF, 3))
			.isInstanceOf(ServiceException.class)
			.hasMessage("工作台服务正忙，请稍后再试");
	}

	/** 返回的内容对不上约定的格式时也报错：当成没有的话，首页会显示 0 条未读 */
	@Test
	void doesNotTakeAMalformedAnswerForNoNotices() throws Exception {
		for (String answer : List.of("null", "{}", "{'recent':[]}", "{'unread':null,'recent':[]}",
			"{'unread':'很多','recent':[]}", "{'unread':-1,'recent':[]}", "{'unread':1.5,'recent':[]}",
			"{'unread':0}", "{'unread':0,'recent':null}", "{'unread':0,'recent':'没有'}",
			"{'unread':0,'recent':[{'title':'放假通知','unread':true}]}",
			"{'unread':0,'recent':[{'id':'abc','title':'放假通知','unread':true}]}",
			"{'unread':0,'recent':[{'id':12,'title':'放假通知'}]}",
			"{'unread':0,'recent':[{'id':12,'title':'放假通知','unread':'true'}]}",
			"{'unread':0,'recent':[12]}")) {
			answers(answer);

			assertThatThrownBy(() -> reader.summaryOf(STAFF, 3)).as(answer)
				.isInstanceOf(ServiceException.class)
				.hasMessage("公告暂时取不到，请稍后再试");
		}
	}

	/** 对方明确说不行的话原样报出去 */
	@Test
	void passesOnWhatTheDeskServiceRefused() {
		when(client.call(anyString(), anyString(), any())).thenThrow(new ServiceException("请求里没有用户编号"));

		assertThatThrownBy(() -> reader.summaryOf(STAFF, 3))
			.isInstanceOf(ServiceException.class)
			.hasMessage("请求里没有用户编号");
	}

	@Test
	void refusesAServiceNameThatCannotBeOne() {
		assertThatThrownBy(() -> new RemoteAppNoticeReader(client, "bad name"))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("mugsun.cloud.desk.service-id");
	}
}
