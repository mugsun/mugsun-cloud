package com.mugsun.cloud.common.inner;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ServiceNamesTest {

	@Test
	void keepsTheConfiguredNames() {
		ServiceNames names = new ServiceNames("mugsun-gis", "track.v2", "AI_01");

		assertThat(names.gis()).isEqualTo("mugsun-gis");
		assertThat(names.track()).isEqualTo("track.v2");
		assertThat(names.ai()).isEqualTo("AI_01");
	}

	@Test
	void dropsSurroundingBlanks() {
		ServiceNames names = new ServiceNames(" mugsun-gis ", "mugsun-track\t", "mugsun-ai");

		assertThat(names.gis()).isEqualTo("mugsun-gis");
		assertThat(names.track()).isEqualTo("mugsun-track");
	}

	/** 名字会写进签名里，带换行、空格、斜杠这些的不能要，启动时就报出是哪个配置项 */
	@Test
	void refusesNamesThatCannotBeSigned() {
		for (String bad : List.of("", "   ", "mugsun gis", "mugsun/gis", "mugsun\ngis", "地图服务", "a".repeat(65))) {
			assertThatThrownBy(() -> new ServiceNames(bad, "mugsun-track", "mugsun-ai"))
				.as("gis=[%s]", bad)
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("mugsun.cloud.module.gis.service-id");
		}
		assertThatThrownBy(() -> new ServiceNames("mugsun-gis", "a b", "mugsun-ai"))
			.hasMessageContaining("mugsun.cloud.module.track.service-id");
		assertThatThrownBy(() -> new ServiceNames("mugsun-gis", "mugsun-track", null))
			.hasMessageContaining("mugsun.cloud.module.ai.service-id");
	}
}
