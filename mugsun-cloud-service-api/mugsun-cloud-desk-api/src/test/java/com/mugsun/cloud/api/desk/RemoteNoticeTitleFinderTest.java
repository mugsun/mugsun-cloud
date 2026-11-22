package com.mugsun.cloud.api.desk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RemoteNoticeTitleFinderTest {

	private static final String DESK = "mugsun-desk";

	/** 路径是和工作台服务约好的，改了两边就对不上 */
	private static final String PATH = "/inner/desk/notice/titles";

	/** 没有公告管理权限的普通用户 */
	private static final NoticeViewer STAFF = new NoticeViewer(7L, false);

	private final ObjectMapper objectMapper = new ObjectMapper();

	private final InnerClient client = mock(InnerClient.class);

	private final RemoteNoticeTitleFinder finder = new RemoteNoticeTitleFinder(client, DESK);

	/** 让工作台服务返回这段内容。JSON 用单引号写，省得满屏转义 */
	private void answers(String json) throws Exception {
		JsonNode data = objectMapper.readTree(json.replace('\'', '"'));
		when(client.call(anyString(), anyString(), any())).thenReturn(data);
	}

	/** 是谁在找放在请求里：内部调用只带租户，不带登录用户 */
	@Test
	void asksTheDeskServiceForTitlesTheViewerCanSee() throws Exception {
		answers("{'titles':['放假通知','放假值班表']}");

		assertThat(finder.titlesLike(STAFF, "放假", 5)).containsExactly("放假通知", "放假值班表");
		verify(client).call(DESK, PATH, Map.of("userId", 7L, "manage", false, "limit", 5, "keyword", "放假"));
	}

	/** 有公告管理权限的人找得到全部公告，这一点要告诉工作台服务：那边读不到登录态，查不了权限 */
	@Test
	void tellsTheDeskServiceThatTheViewerManagesNotices() throws Exception {
		answers("{'titles':[]}");

		finder.titlesLike(new NoticeViewer(7L, true), "放假", 5);

		verify(client).call(DESK, PATH, Map.of("userId", 7L, "manage", true, "limit", 5, "keyword", "放假"));
	}

	@Test
	void nothingFoundIsNotAFailure() throws Exception {
		answers("{'titles':[]}");

		assertThat(finder.titlesLike(STAFF, "放假", 5)).isEmpty();
	}

	/** 搜索词是空的：和单体版一样算没找到，不用去问工作台服务 */
	@Test
	void anEmptyKeywordFindsNothingWithoutAsking() {
		for (String keyword : new String[] {null, "", "  "}) {
			assertThat(finder.titlesLike(STAFF, keyword, 5)).as("搜索词「%s」", keyword).isEmpty();
		}

		verifyNoInteractions(client);
	}

	/** 工作台服务没在运行：报错，不能当成没找到。给用户的话里不带服务名和路径 */
	@Test
	void failsLoudlyWhenTheDeskServiceIsDown() {
		when(client.call(anyString(), anyString(), any()))
			.thenThrow(new InnerCallException(Kind.NO_INSTANCE, "注册中心里没有 mugsun-desk 的实例"));

		assertThatThrownBy(() -> finder.titlesLike(STAFF, "放假", 5))
			.isInstanceOf(ServiceException.class)
			.hasMessage("公告暂时搜不到，请稍后再试");
	}

	@Test
	void saysBusyWhenTheDeskServiceIsFull() {
		when(client.call(anyString(), anyString(), any()))
			.thenThrow(new InnerCallException(Kind.BUSY, "mugsun-desk 正忙，这次没有执行"));

		assertThatThrownBy(() -> finder.titlesLike(STAFF, "放假", 5))
			.isInstanceOf(ServiceException.class)
			.hasMessage("工作台服务正忙，请稍后再试");
	}

	/** 返回的内容对不上约定的格式时也报错：当成没找到的话，搜的人会以为没有这条公告 */
	@Test
	void doesNotTakeAMalformedAnswerForNothingFound() throws Exception {
		for (String answer : List.of("null", "{}", "{'titles':null}", "{'titles':'没有'}", "{'titles':[null]}",
			"{'titles':['放假通知',12]}")) {
			answers(answer);

			assertThatThrownBy(() -> finder.titlesLike(STAFF, "放假", 5)).as(answer)
				.isInstanceOf(ServiceException.class)
				.hasMessage("公告暂时搜不到，请稍后再试");
		}
	}

	/** 对方明确说不行的话原样报出去 */
	@Test
	void passesOnWhatTheDeskServiceRefused() {
		when(client.call(anyString(), anyString(), any())).thenThrow(new ServiceException("请求里没有搜索词"));

		assertThatThrownBy(() -> finder.titlesLike(STAFF, "放假", 5))
			.isInstanceOf(ServiceException.class)
			.hasMessage("请求里没有搜索词");
	}

	@Test
	void refusesAServiceNameThatCannotBeOne() {
		assertThatThrownBy(() -> new RemoteNoticeTitleFinder(client, "bad name"))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("mugsun.cloud.desk.service-id");
	}
}
