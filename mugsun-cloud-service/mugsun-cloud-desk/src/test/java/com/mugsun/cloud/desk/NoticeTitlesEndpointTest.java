package com.mugsun.cloud.desk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mugsun.boot.system.service.NoticeReadService;
import com.mugsun.boot.system.service.NoticeViewer;
import com.mugsun.cloud.api.desk.RemoteNoticeTitleFinder;
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

class NoticeTitlesEndpointTest {

	private final ObjectMapper objectMapper = new ObjectMapper();

	private final NoticeReadService notices = mock(NoticeReadService.class);

	private final NoticeTitlesEndpoint endpoint = new NoticeTitlesEndpoint(notices);

	/** 请求的 JSON 用单引号写，省得满屏转义；返回的也换成单引号来比 */
	private String handle(String json) throws Exception {
		InnerCall call = new InnerCall("mugsun-system", "000000", objectMapper.readTree(json.replace('\'', '"')));
		return objectMapper.writeValueAsString(endpoint.handle(call)).replace('"', '\'');
	}

	/** 路径是和调用方约好的，改了两边就对不上 */
	@Test
	void listensOnTheAgreedPath() {
		assertThat(endpoint.path()).isEqualTo("/inner/desk/notice/titles");
	}

	/** 顺序和查出来的一样（新的在前） */
	@Test
	void returnsTheMatchingTitlesInOrder() throws Exception {
		when(notices.titlesLike(new NoticeViewer(7L, false), "放假", 5)).thenReturn(List.of("放假通知", "放假值班表"));

		assertThat(handle("{'userId':7,'manage':false,'limit':5,'keyword':'放假'}"))
			.isEqualTo("{'titles':['放假通知','放假值班表']}");
	}

	@Test
	void returnsAnEmptyListWhenNothingMatches() throws Exception {
		when(notices.titlesLike(new NoticeViewer(7L, false), "放假", 5)).thenReturn(List.of());

		assertThat(handle("{'userId':7,'manage':false,'limit':5,'keyword':'放假'}")).isEqualTo("{'titles':[]}");
	}

	/** 有公告管理权限的人找得到全部公告：请求里说了有，就按有来找 */
	@Test
	void looksAsAManagerWhenTheRequestSaysSo() throws Exception {
		when(notices.titlesLike(new NoticeViewer(7L, true), "放假", 5)).thenReturn(List.of("放假通知"));

		assertThat(handle("{'userId':7,'manage':true,'limit':5,'keyword':'放假'}")).isEqualTo("{'titles':['放假通知']}");
	}

	@Test
	void refusesARequestWithoutASearchWord() {
		for (String keyword : List.of("", ",'keyword':null", ",'keyword':''", ",'keyword':'  '", ",'keyword':12",
			",'keyword':['放假']")) {
			String request = "{'userId':7,'manage':false,'limit':5" + keyword + "}";
			assertThatThrownBy(() -> handle(request)).as(request)
				.isInstanceOf(ServiceException.class).hasMessage("请求里没有搜索词");
		}

		verifyNoInteractions(notices);
	}

	/** 谁在找、要几条的要求，和取首页公告的那个接口是同一套 */
	@Test
	void refusesARequestThatDoesNotSayWhoIsLookingOrHowMany() {
		assertThatThrownBy(() -> handle("{'manage':false,'limit':5,'keyword':'放假'}"))
			.isInstanceOf(ServiceException.class).hasMessage("请求里没有用户编号");
		assertThatThrownBy(() -> handle("{'userId':7,'limit':5,'keyword':'放假'}"))
			.isInstanceOf(ServiceException.class).hasMessage("请求里没有说明有没有公告管理权限");
		assertThatThrownBy(() -> handle("{'userId':7,'manage':false,'limit':500,'keyword':'放假'}"))
			.isInstanceOf(ServiceException.class).hasMessage("请求里要的条数不对");

		verifyNoInteractions(notices);
	}

	/** 两端接起来：调用方读出来的和这边查出来的一样，是谁在找、找什么也原样传到 */
	@Test
	void theCallingSideReadsBackWhatThisSideSent() {
		NoticeViewer viewer = new NoticeViewer(1902771234567890125L, true);
		when(notices.titlesLike(viewer, "放假", 5)).thenReturn(List.of("放假通知", "放假值班表"));
		InnerClient client = mock(InnerClient.class);
		when(client.call(anyString(), anyString(), any())).thenAnswer(invocation -> {
			JsonNode body = objectMapper.valueToTree(invocation.getArgument(2));
			return objectMapper.valueToTree(endpoint.handle(new InnerCall("mugsun-system", "000000", body)));
		});

		assertThat(new RemoteNoticeTitleFinder(client, "mugsun-desk").titlesLike(viewer, "放假", 5))
			.containsExactly("放假通知", "放假值班表");
	}
}
