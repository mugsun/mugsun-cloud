package com.mugsun.cloud.flow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mugsun.boot.app.AppTodoService;
import com.mugsun.boot.app.dto.AppTodoItemVO;
import com.mugsun.cloud.api.flow.RemoteAppTodoReader;
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

class AppTodoEndpointTest {

	private static final List<AppTodoItemVO> TODOS = List.of(
		new AppTodoItemVO(1902771234567890123L, 1902771234567890001L, "请假审批", "请假审批", "部门经理",
			"2026-11-20 09:30:00"),
		new AppTodoItemVO(11L, 2L, "待办事项", null, "财务", null));

	private final ObjectMapper objectMapper = new ObjectMapper();

	private final AppTodoService todos = mock(AppTodoService.class);

	private final AppTodoEndpoint endpoint = new AppTodoEndpoint(todos);

	/** 请求的 JSON 用单引号写，省得满屏转义；返回的也换成单引号来比 */
	private String handle(String json) throws Exception {
		InnerCall call = new InnerCall("mugsun-system", "000000", objectMapper.readTree(json.replace('\'', '"')));
		return objectMapper.writeValueAsString(endpoint.handle(call)).replace('"', '\'');
	}

	/** 路径是和调用方约好的，改了两边就对不上 */
	@Test
	void listensOnTheAgreedPath() {
		assertThat(endpoint.path()).isEqualTo("/inner/flow/app/todos");
	}

	/** 顺序和查出来的一样（新的在前），空的字段照样是空的 */
	@Test
	void returnsTheUsersTodosInOrder() throws Exception {
		when(todos.listOf(7L)).thenReturn(TODOS);

		assertThat(handle("{'userId':7}")).isEqualTo("{'todos':["
			+ "{'taskId':1902771234567890123,'instanceId':1902771234567890001,'title':'请假审批','flowName':'请假审批',"
			+ "'nodeName':'部门经理','createTime':'2026-11-20 09:30:00'},"
			+ "{'taskId':11,'instanceId':2,'title':'待办事项','flowName':null,'nodeName':'财务','createTime':null}]}");
	}

	@Test
	void returnsAnEmptyListWhenThereIsNothingToDo() throws Exception {
		when(todos.listOf(7L)).thenReturn(List.of());

		assertThat(handle("{'userId':7}")).isEqualTo("{'todos':[]}");
	}

	/** 调用方的 JSON 配置可能把长整数写成字符串 */
	@Test
	void acceptsTheUserIdWrittenAsText() throws Exception {
		when(todos.listOf(1902771234567890123L)).thenReturn(List.of());

		assertThat(handle("{'userId':'1902771234567890123'}")).isEqualTo("{'todos':[]}");
	}

	@Test
	void refusesARequestWithoutAUsableUserId() {
		for (String request : List.of("{}", "{'userId':null}", "{'userId':''}", "{'userId':'abc'}", "{'userId':1.5}",
			"{'userId':0}", "{'userId':-3}", "{'userId':true}", "{'userId':[7]}", "{'userId':99999999999999999999}")) {
			assertThatThrownBy(() -> handle(request)).as(request)
				.isInstanceOf(ServiceException.class).hasMessage("请求里没有用户编号");
		}

		verifyNoInteractions(todos);
	}

	/** 两端接起来：调用方读出来的和这边查出来的一样，十九位的编号一位不差 */
	@Test
	void theCallingSideReadsBackWhatThisSideSent() {
		when(todos.listOf(7L)).thenReturn(TODOS);
		InnerClient client = mock(InnerClient.class);
		when(client.call(anyString(), anyString(), any())).thenAnswer(invocation -> {
			JsonNode body = objectMapper.valueToTree(invocation.getArgument(2));
			return objectMapper.valueToTree(endpoint.handle(new InnerCall("mugsun-system", "000000", body)));
		});

		assertThat(new RemoteAppTodoReader(client, "mugsun-flow").listOf(7L)).isEqualTo(TODOS);
	}
}
