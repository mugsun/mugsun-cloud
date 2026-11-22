package com.mugsun.cloud.gateway.lane;

import org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplier;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * 每个服务的负载均衡都用这一套实例列表：从注册中心取，缓存几秒（{@code spring.cloud.loadbalancer.cache.ttl}），
 * 再按灰度标记挑（{@link LaneInstanceSupplier}）。前两步和框架默认的一样，只是在最外面多包了一层。
 *
 * <p>这个类不能加 {@code @Configuration}：框架给每个服务各建一个小容器，这个类是放进那些小容器里用的（由 {@link LaneConfiguration}
 * 上的 {@code @LoadBalancerClients} 登记）；加了的话网关自己的容器也会扫描到它，在不该建的地方多建一份。
 *
 * <p>标成首选（{@code @Primary}）：小容器里万一还有别人建的实例列表（打开 {@code spring.cloud.loadbalancer.nacos.enabled} 时，
 * Nacos 那一套选实例的办法会建一份），负载均衡取的仍然是这一份。取到另一份的话，灰度实例会和正式实例一起分流量。
 * 那个选项在这个工程里没有用过、也没有测过，没有需要不要打开。
 */
class LaneLoadBalancerConfiguration {

	@Bean
	@Primary
	ServiceInstanceListSupplier laneInstanceSupplier(ConfigurableApplicationContext context) {
		return new LaneInstanceSupplier(
			ServiceInstanceListSupplier.builder().withDiscoveryClient().withCaching().build(context));
	}
}
