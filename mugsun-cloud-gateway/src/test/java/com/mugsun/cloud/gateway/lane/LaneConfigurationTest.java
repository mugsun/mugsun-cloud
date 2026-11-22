package com.mugsun.cloud.gateway.lane;

import com.alibaba.cloud.nacos.NacosDiscoveryProperties;
import com.mugsun.cloud.gateway.props.GatewayGuardProperties;
import com.mugsun.cloud.gateway.support.NacosConfigSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 灰度规则从 Nacos 的哪一份配置读、读到的内容交给谁，以及开关没开时不去读。
 * 「按标记挑实例」这一半没有开关，它在网关里是不是真的生效，在 GatewayLaneTest 里测。
 */
class LaneConfigurationTest {

	private final LaneRuleBook book = new LaneRuleBook();

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withUserConfiguration(LaneConfiguration.RulesFromNacos.class)
		// 这里只看开关：不连 Nacos，所以没有「读哪一份配置」这个 bean
		.withPropertyValues("spring.cloud.nacos.discovery.enabled=false");

	private static NacosDiscoveryProperties registeringIn(String group) {
		NacosDiscoveryProperties nacos = new NacosDiscoveryProperties();
		nacos.setGroup(group);
		return nacos;
	}

	private NacosConfigSource.Watch watch(GatewayGuardProperties properties) {
		return new LaneConfiguration.RulesFromNacos().laneRulesWatch(properties, registeringIn("SERVICES"), book);
	}

	@Test
	void rulesAreReadFromTheDefaultDataIdInTheGroupTheServicesRegisterIn() {
		NacosConfigSource.Watch watch = watch(new GatewayGuardProperties());

		assertThat(watch.what()).isEqualTo("灰度规则");
		assertThat(watch.dataId()).isEqualTo("mugsun-gateway-lanes.yaml");
		assertThat(watch.group()).isEqualTo("SERVICES");
		// Nacos 连不上时日志里说的「现在怎么办」
		assertThat(watch.without()).contains("所有请求都只发给正式实例");
	}

	@Test
	void configuredDataIdAndGroupAreUsed() {
		GatewayGuardProperties properties = new GatewayGuardProperties();
		properties.setLanesDataId("lanes-of-test.yaml");
		properties.setLanesGroup("GATEWAY");

		NacosConfigSource.Watch watch = watch(properties);

		assertThat(watch.dataId()).isEqualTo("lanes-of-test.yaml");
		assertThat(watch.group()).isEqualTo("GATEWAY");
	}

	@Test
	void whatNacosSendsGoesIntoTheRuleBook() {
		NacosConfigSource.Watch watch = watch(new GatewayGuardProperties());

		watch.sink().accept("lanes:\n  - name: canary\n    client-ips: 203.0.113.7\n");
		assertThat(book.laneFor("203.0.113.7", "www.example.com", null)).isEqualTo("canary");

		// 配置被删掉时 Nacos 送来的是 null
		watch.sink().accept(null);
		assertThat(book.hasRules()).isFalse();
	}

	@Test
	void rulesAreReadOnlyWhenSwitchedOn() {
		runner.withPropertyValues("mugsun.gateway.lanes=true")
			.run(context -> assertThat(context).hasSingleBean(LaneConfiguration.RulesFromNacos.class)
				.doesNotHaveBean(NacosConfigSource.Watch.class));
		runner.run(context -> assertThat(context).doesNotHaveBean(LaneConfiguration.RulesFromNacos.class));
	}

	@ParameterizedTest
	@ValueSource(strings = {"false", "", "off"})
	void anythingButTrueLeavesTheRulesUnread(String value) {
		runner.withPropertyValues("mugsun.gateway.lanes=" + value)
			.run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(LaneConfiguration.RulesFromNacos.class));
	}
}
