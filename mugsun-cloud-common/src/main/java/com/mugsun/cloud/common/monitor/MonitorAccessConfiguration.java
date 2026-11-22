package com.mugsun.cloud.common.monitor;

import com.mugsun.boot.monitor.ActuatorAuthFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 把 {@link MonitorAccessFilter} 换到同步来的 {@link ActuatorAuthFilter} 原来的位置上。
 *
 * <p>{@link ActuatorAuthFilter} 对 {@code /actuator} 下面的请求一律要登录，监控中心直接连上来的请求过不去。
 * 它本身不改（同步来的代码不手改），只是不再让容器直接调用，改由 {@link MonitorAccessFilter} 在请求是经网关来的时候调用。
 *
 * <p>两个登记写在同一个类里：要么都生效，要么都不生效，不会出现「原来的停了、新的没上」、监控接口没人管的情况。
 */
@Configuration(proxyBeanMethods = false)
public class MonitorAccessConfiguration {

	@Bean
	public FilterRegistrationBean<MonitorAccessFilter> monitorAccessFilter(ActuatorAuthFilter loginGate) {
		FilterRegistrationBean<MonitorAccessFilter> registration =
			new FilterRegistrationBean<>(new MonitorAccessFilter(loginGate));
		registration.setOrder(MonitorAccessFilter.ORDER);
		return registration;
	}

	/** 给一个过滤器单独写了登记、又把登记关掉，容器就不会再自动调用它 */
	@Bean
	public FilterRegistrationBean<ActuatorAuthFilter> actuatorAuthFilterNotCalledByContainer(ActuatorAuthFilter filter) {
		FilterRegistrationBean<ActuatorAuthFilter> registration = new FilterRegistrationBean<>(filter);
		registration.setEnabled(false);
		return registration;
	}
}
