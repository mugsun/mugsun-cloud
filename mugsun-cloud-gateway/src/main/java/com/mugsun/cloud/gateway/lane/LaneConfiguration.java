package com.mugsun.cloud.gateway.lane;

import com.alibaba.cloud.nacos.ConditionalOnNacosDiscoveryEnabled;
import com.alibaba.cloud.nacos.NacosDiscoveryProperties;
import com.mugsun.cloud.config.NacosConfigSource;
import com.mugsun.cloud.gateway.props.GatewayGuardProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.cloud.loadbalancer.annotation.LoadBalancerClients;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 灰度发布在网关里的装配。分两部分，开不开各管各的：
 *
 * <ul>
 * <li>按灰度标记挑实例（{@link LaneLoadBalancerConfiguration}）：一直开着，没有开关。没有灰度规则时所有请求都没有标记，
 * 效果就是「灰度实例收不到网关转来的请求」。这一条不能跟着开关走：实例带着灰度标记启动了，网关这边开关却没开，
 * 这时要是把它当成普通实例分流量，还没验证过的版本就直接接了正式流量</li>
 * <li>从 Nacos 读灰度规则（{@link RulesFromNacos}）：{@code mugsun.gateway.lanes} 是 true 才有</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@LoadBalancerClients(defaultConfiguration = LaneLoadBalancerConfiguration.class)
public class LaneConfiguration {

	/**
	 * 灰度规则的总开关：{@code mugsun.gateway.lanes} 不是 true 时不去读 Nacos 里的规则，{@link LaneRuleBook} 里一直是空的，
	 * 所有请求都只发给正式实例。
	 */
	@Configuration(proxyBeanMethods = false)
	@ConditionalOnBooleanProperty("mugsun.gateway.lanes")
	public static class RulesFromNacos {

		/** 灰度规则写在 Nacos 的哪一份配置里。去读的是 {@link NacosConfigSource}，和动态路由共用一条连接 */
		@Bean
		@ConditionalOnNacosDiscoveryEnabled
		NacosConfigSource.Watch laneRulesWatch(GatewayGuardProperties properties, NacosDiscoveryProperties nacos,
				LaneRuleBook book) {
			String group = properties.getLanesGroup();
			return new NacosConfigSource.Watch("灰度规则", "先按没有灰度规则转发，所有请求都只发给正式实例", properties.getLanesDataId(),
				group.isBlank() ? nacos.getGroup() : group, book::load);
		}
	}
}
