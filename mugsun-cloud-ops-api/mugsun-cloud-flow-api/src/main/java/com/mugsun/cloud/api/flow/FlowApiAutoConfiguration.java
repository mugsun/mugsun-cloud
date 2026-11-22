package com.mugsun.cloud.api.flow;

import com.mugsun.boot.app.AppTodoReader;
import com.mugsun.cloud.common.inner.InnerClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * 依赖了这个模块的服务自动得到的 Bean：服务自己没有取待办的实现时，用转给审批流服务的那一个。
 *
 * <p>写成自动配置而不是 {@code @Component}：自动配置排在各服务自己扫描到的类后面处理，这样才看得出这个服务里
 * 有没有取待办的实现。审批流服务自己带着 mugsun-boot 的 {@code AppTodoService}，转发的那个在它那里不注册；
 * 别的服务里没有，就注册转发的。
 *
 * <p>所以这个包不能放在各服务启动类扫描的包（{@code com.mugsun.cloud.common}、{@code com.mugsun.cloud.<服务名>}）下面：
 * 被扫描到的话就不是排在后面处理了。
 */
@AutoConfiguration
public class FlowApiAutoConfiguration {

	/** 审批流服务的服务名，不配是 mugsun-flow */
	static final String SERVICE_ID_KEY = "mugsun.cloud.flow.service-id";

	@Bean
	@ConditionalOnMissingBean(AppTodoReader.class)
	public AppTodoReader remoteAppTodoReader(InnerClient client,
											 @Value("${" + SERVICE_ID_KEY + ":mugsun-flow}") String service) {
		return new RemoteAppTodoReader(client, service);
	}
}
