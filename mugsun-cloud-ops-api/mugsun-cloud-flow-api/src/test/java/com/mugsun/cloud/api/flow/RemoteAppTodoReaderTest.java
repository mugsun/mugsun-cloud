package com.mugsun.cloud.api.flow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mugsun.boot.app.dto.AppTodoItemVO;
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

class RemoteAppTodoReaderTest {

	private static final String FLOW = "mugsun-flow";

	/** 路径是和审批流服务约好的，改了两边就对不上 */
	private static final String PATH = "/inner/flow/app/todos";

	private final ObjectMapper objectMapper = new ObjectMapper();

	private final InnerClient client = mock(InnerClient.class);

	private final RemoteAppTodoReader reader = new RemoteAppTodoReader(client, FLOW);

	/** 让审批流服务返回这段内容。JSON 用单引号写，省得满屏转义 */
	private void answers(String json) throws Exception {
		JsonNode data = objectMapper.readTree(json.replace('\'', '"'));
		when(client.call(anyString(), anyString(), any())).thenReturn(data);
	}

	/** 用户编号放在请求里：内部调用只带租户，不带登录用户 */
	@Test
	void asksTheFlowServiceForTheUsersTodos() throws Exception {
		answers("{'todos':["
			+ "{'taskId':12,'instanceId':3,'title':'请假审批','flowName':'请假审批',"
			+ "'nodeName':'部门经理','createTime':'2026-11-20 09:30:00'},"
			+ "{'taskId':11,'instanceId':2,'title':'待办事项','flowName':null,"
			+ "'nodeName':'财务','createTime':null}]}");

		List<AppTodoItemVO> todos = reader.listOf(7L);

		assertThat(todos).containsExactly(
			new AppTodoItemVO(12L, 3L, "请假审批", "请假审批", "部门经理", "2026-11-20 09:30:00"),
			new AppTodoItemVO(11L, 2L, "待办事项", null, "财务", null));
		verify(client).call(FLOW, PATH, Map.of("userId", 7L));
	}

	/** 编号有十九位，写成数字或者字符串传过来都要读得出来，一位都不能差 */
	@Test
	void readsLongIdsExactlyWhetherSentAsNumbersOrText() throws Exception {
		answers("{'todos':["
			+ "{'taskId':'1902771234567890123','instanceId':'1902771234567890001','title':'报销审批',"
			+ "'flowName':'报销审批','nodeName':'财务','createTime':'2026-11-20 09:30:00'},"
			+ "{'taskId':1902771234567890125,'instanceId':1902771234567890003,'title':'请假审批',"
			+ "'flowName':'请假审批','nodeName':'部门经理','createTime':'2026-11-20 09:31:00'}]}");

		assertThat(reader.listOf(7L)).containsExactly(
			new AppTodoItemVO(1902771234567890123L, 1902771234567890001L, "报销审批", "报销审批", "财务",
				"2026-11-20 09:30:00"),
			new AppTodoItemVO(1902771234567890125L, 1902771234567890003L, "请假审批", "请假审批", "部门经理",
				"2026-11-20 09:31:00"));
	}

	@Test
	void anEmptyListIsNotAFailure() throws Exception {
		answers("{'todos':[]}");

		assertThat(reader.listOf(7L)).isEmpty();
	}

	/** 审批流服务没在运行：报错，不能当成没有待办。给用户的话里不带服务名和路径 */
	@Test
	void failsLoudlyWhenTheFlowServiceIsDown() {
		when(client.call(anyString(), anyString(), any()))
			.thenThrow(new InnerCallException(Kind.NO_INSTANCE, "注册中心里没有 mugsun-flow 的实例"));

		assertThatThrownBy(() -> reader.listOf(7L))
			.isInstanceOf(ServiceException.class)
			.hasMessage("待办暂时取不到，请稍后再试");
	}

	@Test
	void saysBusyWhenTheFlowServiceIsFull() {
		when(client.call(anyString(), anyString(), any()))
			.thenThrow(new InnerCallException(Kind.BUSY, "mugsun-flow 的 /inner/flow/app/todos 正忙，这次没有执行"));

		assertThatThrownBy(() -> reader.listOf(7L))
			.isInstanceOf(ServiceException.class)
			.hasMessage("审批流服务正忙，请稍后再试");
	}

	/** 返回的内容对不上约定的格式时也报错：当成空列表的话，首页会显示 0 条待办 */
	@Test
	void doesNotTakeAMalformedAnswerForNoTodos() throws Exception {
		for (String answer : List.of("{}", "{'todos':null}", "{'todos':'没有'}", "{'todos':[{'title':'请假审批'}]}")) {
			answers(answer);

			assertThatThrownBy(() -> reader.listOf(7L)).as(answer)
				.isInstanceOf(ServiceException.class)
				.hasMessage("待办暂时取不到，请稍后再试");
		}
	}

	/** 对方明确说不行的话原样报出去 */
	@Test
	void passesOnWhatTheFlowServiceRefused() {
		when(client.call(anyString(), anyString(), any())).thenThrow(new ServiceException("请求里没有用户编号"));

		assertThatThrownBy(() -> reader.listOf(7L))
			.isInstanceOf(ServiceException.class)
			.hasMessage("请求里没有用户编号");
	}

	@Test
	void refusesAServiceNameThatCannotBeOne() {
		assertThatThrownBy(() -> new RemoteAppTodoReader(client, "bad name"))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("mugsun.cloud.flow.service-id");
	}
}
