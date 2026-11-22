package com.mugsun.cloud.desk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mugsun.boot.app.AppNoticeService;
import com.mugsun.boot.app.dto.AppNoticeItemVO;
import com.mugsun.boot.app.dto.AppNoticeSummaryVO;
import com.mugsun.boot.system.service.NoticeViewer;
import com.mugsun.cloud.api.desk.RemoteAppNoticeReader;
import com.mugsun.cloud.common.inner.InnerCall;
import com.mugsun.cloud.common.inner.InnerClient;
import com.mugsun.core.tool.exception.ServiceException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AppNoticeSummaryEndpointTest {

	private static final AppNoticeSummaryVO SUMMARY = new AppNoticeSummaryVO(2, List.of(
		new AppNoticeItemVO(1902771234567890123L, "放假通知", "2026-11-20 09:30", true),
		new AppNoticeItemVO(11L, "未命名公告", "", false)));

	private static final AppNoticeSummaryVO NOTHING = new AppNoticeSummaryVO(0, List.of());

	private final ObjectMapper objectMapper = new ObjectMapper();

	private final AppNoticeService notices = mock(AppNoticeService.class);

	private final AppNoticeSummaryEndpoint endpoint = new AppNoticeSummaryEndpoint(notices);

	/** 请求的 JSON 用单引号写，省得满屏转义；返回的也换成单引号来比 */
	private String handle(String json) throws Exception {
		InnerCall call = new InnerCall("mugsun-system", "000000", objectMapper.readTree(json.replace('\'', '"')));
		return objectMapper.writeValueAsString(endpoint.handle(call)).replace('"', '\'');
	}

	/** 路径是和调用方约好的，改了两边就对不上 */
	@Test
	void listensOnTheAgreedPath() {
		assertThat(endpoint.path()).isEqualTo("/inner/desk/app/notices/summary");
	}

	/** 顺序和查出来的一样（置顶的在前，然后新的在前） */
	@Test
	void returnsTheUnreadCountAndTheRecentNotices() throws Exception {
		when(notices.summaryOf(new NoticeViewer(7L, false), 3)).thenReturn(SUMMARY);

		assertThat(handle("{'userId':7,'manage':false,'limit':3}")).isEqualTo("{'unread':2,'recent':["
			+ "{'id':1902771234567890123,'title':'放假通知','time':'2026-11-20 09:30','unread':true},"
			+ "{'id':11,'title':'未命名公告','time':'','unread':false}]}");
	}

	@Test
	void returnsAnEmptyListWhenThereAreNoNotices() throws Exception {
		when(notices.summaryOf(new NoticeViewer(7L, false), 3)).thenReturn(NOTHING);

		assertThat(handle("{'userId':7,'manage':false,'limit':3}")).isEqualTo("{'unread':0,'recent':[]}");
	}

	/** 有公告管理权限的人看得到全部公告：请求里说了有，就按有来查 */
	@Test
	void looksAsAManagerWhenTheRequestSaysSo() throws Exception {
		when(notices.summaryOf(new NoticeViewer(7L, true), 3)).thenReturn(SUMMARY);

		assertThat(handle("{'userId':7,'manage':true,'limit':3}")).startsWith("{'unread':2,");
	}

	/** 调用方的 JSON 配置可能把长整数写成字符串 */
	@Test
	void acceptsNumbersWrittenAsText() throws Exception {
		when(notices.summaryOf(new NoticeViewer(1902771234567890123L, false), 3)).thenReturn(NOTHING);

		assertThat(handle("{'userId':'1902771234567890123','manage':false,'limit':'3'}"))
			.isEqualTo("{'unread':0,'recent':[]}");
	}

	@Test
	void refusesARequestWithoutAUsableUserId() {
		for (String userId : List.of("", "'userId':null,", "'userId':'',", "'userId':'abc',", "'userId':1.5,",
			"'userId':0,", "'userId':-3,", "'userId':true,", "'userId':[7],", "'userId':99999999999999999999,")) {
			String request = "{" + userId + "'manage':false,'limit':3}";
			assertThatThrownBy(() -> handle(request)).as(request)
				.isInstanceOf(ServiceException.class).hasMessage("请求里没有用户编号");
		}

		verifyNoInteractions(notices);
	}

	/** 有没有公告管理权限必须明说：没写就当成没有的话，管理员少看到公告也没人知道 */
	@Test
	void refusesARequestThatDoesNotSayWhetherTheViewerManagesNotices() {
		for (String manage : List.of("", "'manage':null,", "'manage':'true',", "'manage':1,")) {
			String request = "{'userId':7," + manage + "'limit':3}";
			assertThatThrownBy(() -> handle(request)).as(request)
				.isInstanceOf(ServiceException.class).hasMessage("请求里没有说明有没有公告管理权限");
		}

		verifyNoInteractions(notices);
	}

	@Test
	void refusesALimitOutOfRange() {
		for (String limit : List.of("", ",'limit':null", ",'limit':0", ",'limit':-1", ",'limit':51", ",'limit':1.5",
			",'limit':'abc'", ",'limit':true")) {
			String request = "{'userId':7,'manage':false" + limit + "}";
			assertThatThrownBy(() -> handle(request)).as(request)
				.isInstanceOf(ServiceException.class).hasMessage("请求里要的条数不对");
		}

		verifyNoInteractions(notices);
	}

	/** 两端接起来：调用方读出来的和这边查出来的一样，十九位的编号一位不差，是谁在看也原样传到 */
	@Test
	void theCallingSideReadsBackWhatThisSideSent() {
		NoticeViewer viewer = new NoticeViewer(1902771234567890125L, true);
		when(notices.summaryOf(viewer, 3)).thenReturn(SUMMARY);
		InnerClient client = mock(InnerClient.class);
		when(client.call(anyString(), anyString(), any())).thenAnswer(invocation -> {
			JsonNode body = objectMapper.valueToTree(invocation.getArgument(2));
			return objectMapper.valueToTree(endpoint.handle(new InnerCall("mugsun-system", "000000", body)));
		});

		assertThat(new RemoteAppNoticeReader(client, "mugsun-desk").summaryOf(viewer, 3)).isEqualTo(SUMMARY);
	}
}
