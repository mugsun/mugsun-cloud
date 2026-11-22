package com.mugsun.cloud.common.lane;

import com.alibaba.cloud.nacos.registry.NacosRegistration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InstanceLaneTest {

	@ParameterizedTest
	@ValueSource(strings = {"", " ", "\t"})
	void notConfiguredMeansABaseInstanceThatRegistersNothingExtra(String configured) {
		InstanceLane lane = new InstanceLane(configured);
		Map<String, String> metadata = new HashMap<>(Map.of("preserved.register.source", "SPRING_CLOUD"));

		lane.customize(registrationWith(metadata));

		assertThat(lane.name()).isNull();
		assertThat(metadata).containsOnlyKeys("preserved.register.source");
	}

	@Test
	void missingPropertyMeansABaseInstance() {
		assertThat(new InstanceLane(null).name()).isNull();
	}

	@Test
	void configuredLaneGoesIntoTheRegistrationMetadata() {
		InstanceLane lane = new InstanceLane(" canary ");
		Map<String, String> metadata = new HashMap<>(Map.of("preserved.register.source", "SPRING_CLOUD"));

		lane.customize(registrationWith(metadata));

		assertThat(lane.name()).isEqualTo("canary");
		assertThat(metadata).containsEntry(Lanes.METADATA_KEY, "canary").containsKey("preserved.register.source");
	}

	/** 写错的标记和谁都对不上，实例会一直收不到请求：启动时就报出来 */
	@ParameterizedTest
	@ValueSource(strings = {"Canary", "-canary", "can ary", "can_ary", "灰度", "a,b", "abcdefghijklmnopqrstuvwxyz0123456"})
	void malformedLaneRefusesToStart(String configured) {
		assertThatThrownBy(() -> new InstanceLane(configured))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("MUGSUN_LANE")
			.hasMessageContaining(configured);
	}

	@Test
	void theNameBaseIsReserved() {
		assertThatThrownBy(() -> new InstanceLane("base"))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("MUGSUN_LANE")
			.hasMessageContaining("base");
	}

	private static NacosRegistration registrationWith(Map<String, String> metadata) {
		NacosRegistration registration = mock(NacosRegistration.class);
		when(registration.getMetadata()).thenReturn(metadata);
		return registration;
	}
}
