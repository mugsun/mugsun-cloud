package com.mugsun.cloud.gateway.lane;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.cloud.client.DefaultServiceInstance;
import org.springframework.cloud.client.ServiceInstance;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 灰度发布的几个约定：标记的名字怎么写算对，实例登记的标记怎么读，以及网关和服务各写的一份是不是一样。
 */
class LaneNamesTest {

	@ParameterizedTest
	@ValueSource(strings = {"canary", "v2", "2026-11", "a", "0", "a-b-c", "abcdefghijklmnopqrstuvwxyz012345"})
	void lowerCaseLettersDigitsAndHyphensUpTo32CharactersAreUsable(String name) {
		assertThat(LaneNames.usable(name)).isTrue();
	}

	@ParameterizedTest
	@NullSource
	@ValueSource(strings = {"", " ", "base", "Canary", "-a", "a_b", "a.b", "a b", " canary", "canary\n", "灰度",
		"abcdefghijklmnopqrstuvwxyz0123456"})
	void anythingElseIsNotUsable(String name) {
		assertThat(LaneNames.usable(name)).isFalse();
	}

	@Test
	void instanceWithoutARegisteredLaneIsABaseInstance() {
		assertThat(LaneNames.of(instance(Map.of()))).isNull();
		assertThat(LaneNames.of(instance(Map.of("lane", "")))).isNull();
		assertThat(LaneNames.of(instance(Map.of("lane", "  ")))).isNull();
		assertThat(LaneNames.of(instance(Map.of("version", "2.0")))).isNull();
		// 有的注册中心客户端给的元数据是 null
		assertThat(LaneNames.of(mock(ServiceInstance.class))).isNull();
	}

	@Test
	void registeredLaneIsReadAsItIs() {
		assertThat(LaneNames.of(instance(Map.of("lane", "canary")))).isEqualTo("canary");
		assertThat(LaneNames.of(instance(Map.of("lane", " canary ")))).isEqualTo("canary");
	}

	/** 写法不对的标记也是标记：这样的实例谁的请求都收不到，但不能当成正式实例 */
	@Test
	void malformedLaneStillMakesAGreyInstance() {
		assertThat(LaneNames.of(instance(Map.of("lane", "Canary!")))).isEqualTo("Canary!");
		assertThat(LaneNames.of(instance(Map.of("lane", "base")))).isEqualTo("base");
		assertThat(LaneNames.usable("Canary!")).isFalse();
		assertThat(LaneNames.usable("base")).isFalse();
	}

	/**
	 * 网关不依赖 mugsun-cloud-common，请求头叫什么、元数据的键叫什么、名字怎么写这几样两边各写了一份。
	 * 改了一边没改另一边，网关写的头服务就不认了，而且两边各自的测试都还是绿的：所以这里直接对着那边的源文件核对。
	 */
	@Test
	void sameConventionsAsTheServices() throws IOException {
		Path source = Path.of("..", "mugsun-cloud-common", "src", "main", "java", "com", "mugsun", "cloud", "common",
			"lane", "Lanes.java");
		assertThat(source).as("服务那边放灰度约定的文件。搬了地方的话，这里要跟着改").isRegularFile();

		String text = Files.readString(source);
		assertThat(text).contains("String HEADER = \"" + LaneNames.HEADER + "\";");
		assertThat(text).contains("String METADATA_KEY = \"" + LaneNames.METADATA_KEY + "\";");
		assertThat(text).contains("String BASE = \"" + LaneNames.BASE + "\";");
		assertThat(text).contains("Pattern NAME = Pattern.compile(\"" + LaneNames.NAME.pattern() + "\");");
	}

	private static ServiceInstance instance(Map<String, String> metadata) {
		return new DefaultServiceInstance("gis-1", "mugsun-gis", "10.0.0.12", 8102, false, metadata);
	}
}
