package com.mugsun.cloud.api.flow;

/**
 * 审批流内部接口里两端约好的名字：请求和响应的字段。
 *
 * <p>调用的一端是 {@link RemoteAppTodoReader}，接收的一端是审批流服务的 {@code AppTodoEndpoint}，
 * 两边都从这里取，免得写得不一样。路径登记在公共层的 {@code InnerPaths}。
 *
 * <p>取待办（{@code InnerPaths.FLOW_APP_TODOS}）：请求 {@code {"userId": 用户编号}}，租户随内部调用带过去；
 * 响应 {@code {"todos": [{"taskId": …, "instanceId": …, "title": "…", "flowName": "…", "nodeName": "…", "createTime": "…"}]}}，
 * 新的在前，一条也没有时 {@code todos} 是空数组。每一条的内容和手机端待办列表里的一条相同。
 *
 * <p>三个编号（用户、任务、流程实例）写成数字或者写成字符串形式的数字，两端都认：各服务的 JSON 配置可能把长整数写成字符串。
 */
public final class FlowApi {

	public static final String FIELD_USER_ID = "userId";

	public static final String FIELD_TODOS = "todos";

	public static final String FIELD_TASK_ID = "taskId";

	public static final String FIELD_INSTANCE_ID = "instanceId";

	public static final String FIELD_TITLE = "title";

	public static final String FIELD_FLOW_NAME = "flowName";

	public static final String FIELD_NODE_NAME = "nodeName";

	public static final String FIELD_CREATE_TIME = "createTime";

	private FlowApi() {
	}
}
