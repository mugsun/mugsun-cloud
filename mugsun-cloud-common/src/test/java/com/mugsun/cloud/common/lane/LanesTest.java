package com.mugsun.cloud.common.lane;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.springframework.cloud.client.DefaultServiceInstance;
import org.springframework.cloud.client.ServiceInstance;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LanesTest {

	private static final ServiceInstance BASE_1 = instance("10.0.0.1", null);
	private static final ServiceInstance BASE_2 = instance("10.0.0.2", null);
	private static final ServiceInstance CANARY_1 = instance("10.0.0.3", "canary");
	private static final ServiceInstance CANARY_2 = instance("10.0.0.4", "canary");
	private static final ServiceInstance BLUE = instance("10.0.0.5", "blue");

	@AfterEach
	void clear() {
		MDC.clear();
	}

	// ---------- 名字的写法 ----------

	@ParameterizedTest
	@ValueSource(strings = {"canary", "a", "0", "v2", "feature-123", "a-", "abcdefghijklmnopqrstuvwxyz012345"})
	void wellFormedNamesAreKept(String name) {
		assertThat(Lanes.normalize(name)).isEqualTo(name);
	}

	@ParameterizedTest
	@ValueSource(strings = {"", " ", "base", "Canary", "CANARY", "-canary", "can ary", " canary", "canary ", "can_ary",
		"can.ary", "灰度", "canary\n", "a,b", "abcdefghijklmnopqrstuvwxyz0123456"})
	void anythingElseCountsAsNoLane(String name) {
		assertThat(Lanes.normalize(name)).isNull();
	}

	@Test
	void missingNameCountsAsNoLane() {
		assertThat(Lanes.normalize(null)).isNull();
	}

	// ---------- 实例登记的标记 ----------

	@Test
	void instanceWithoutTheKeyOrWithABlankValueIsABaseInstance() {
		assertThat(Lanes.of(instance("10.0.0.1", null))).isNull();
		assertThat(Lanes.of(instance("10.0.0.1", ""))).isNull();
		assertThat(Lanes.of(instance("10.0.0.1", "  "))).isNull();
	}

	@Test
	void instanceLaneIsReadFromItsMetadata() {
		assertThat(Lanes.of(CANARY_1)).isEqualTo("canary");
		assertThat(Lanes.of(instance("10.0.0.1", " canary "))).isEqualTo("canary");
	}

	/** 写法不合规的标记原样留着，不能当成没有标记：那样正常的请求就会发给这个实例 */
	@ParameterizedTest
	@ValueSource(strings = {"Canary", "base", "can ary", "abcdefghijklmnopqrstuvwxyz0123456"})
	void malformedInstanceLaneIsNotTreatedAsBase(String registered) {
		ServiceInstance odd = instance("10.0.0.9", registered);

		assertThat(Lanes.of(odd)).isEqualTo(registered);
		assertThat(Lanes.candidates(List.of(odd), null).isEmpty()).isTrue();
		assertThat(Lanes.candidates(List.of(odd), "canary").isEmpty()).isTrue();
	}

	// ---------- 按标记挑实例 ----------

	@Test
	void requestWithoutLaneMayOnlyUseBaseInstances() {
		Lanes.Candidates found = Lanes.candidates(List.of(CANARY_1, BASE_1, BLUE, BASE_2, CANARY_2), null);

		assertThat(found.preferred()).containsExactly(BASE_1, BASE_2);
		assertThat(found.fallback()).isEmpty();
	}

	@Test
	void requestInALanePrefersThatLaneAndFallsBackToBase() {
		Lanes.Candidates found = Lanes.candidates(List.of(CANARY_1, BASE_1, BLUE, BASE_2, CANARY_2), "canary");

		assertThat(found.preferred()).containsExactly(CANARY_1, CANARY_2);
		assertThat(found.fallback()).containsExactly(BASE_1, BASE_2);
	}

	@Test
	void laneWithoutInstancesOfItsOwnIsLeftWithBase() {
		Lanes.Candidates found = Lanes.candidates(List.of(BASE_1, BLUE), "canary");

		assertThat(found.preferred()).isEmpty();
		assertThat(found.fallback()).containsExactly(BASE_1);
		assertThat(found.isEmpty()).isFalse();
	}

	@Test
	void instancesOfOtherLanesAreNeverCandidates() {
		assertThat(Lanes.candidates(List.of(BLUE), "canary").isEmpty()).isTrue();
		assertThat(Lanes.candidates(List.of(BLUE, CANARY_1), null).isEmpty()).isTrue();
		assertThat(Lanes.candidates(List.of(), "canary").isEmpty()).isTrue();
		assertThat(Lanes.candidates(List.of(), null).isEmpty()).isTrue();
	}

	// ---------- 当前请求的标记 ----------

	@Test
	void contextKeepsTheLaneUntilCleared() {
		assertThat(LaneContext.current()).isNull();

		LaneContext.set("canary");
		assertThat(LaneContext.current()).isEqualTo("canary");

		LaneContext.clear();
		assertThat(LaneContext.current()).isNull();
	}

	/** 记一个不能用的标记，等于把原来记着的清掉：不能让上一个请求留下的标记接着生效 */
	@ParameterizedTest
	@ValueSource(strings = {"", "base", "Canary", "can ary"})
	void settingAnUnusableLaneClearsWhatWasThere(String unusable) {
		LaneContext.set("canary");

		LaneContext.set(unusable);

		assertThat(LaneContext.current()).isNull();
		assertThat(MDC.get(LaneContext.MDC_KEY)).isNull();
	}

	@Test
	void settingNullClearsWhatWasThere() {
		LaneContext.set("canary");

		LaneContext.set(null);

		assertThat(LaneContext.current()).isNull();
	}

	/** 标记存在 MDC 里，异步任务的线程池把 MDC 带过去时它就跟着过去；那边读到的也要过一遍写法检查 */
	@Test
	void contextLivesInTheMdc() {
		LaneContext.set("canary");
		Map<String, String> copied = MDC.getCopyOfContextMap();
		MDC.clear();
		assertThat(LaneContext.current()).isNull();

		MDC.setContextMap(copied);
		assertThat(LaneContext.current()).isEqualTo("canary");

		MDC.put(LaneContext.MDC_KEY, "Not A Lane");
		assertThat(LaneContext.current()).isNull();
	}

	// ---------- 和这个模块外面约好的名字 ----------

	/**
	 * 这三个名字不只在这个模块里用：请求头是网关写的（网关不依赖这个模块，另有一份 LaneNames），部署文档里也教人带它；
	 * 元数据的键网关按它挑实例，在 Nacos 控制台里也看得到；MDC 的键是给日志格式用的（{@code %X{lane}}），改了名那一栏就空了。
	 * 这个模块自己的测试用的都是常量，改了名照样全过，所以把现在的值钉在这里：要改名，连同网关、部署文档、实测脚本一起改，再改这里。
	 */
	@Test
	void namesAgreedWithTheGatewayAndTheDocsStayAsTheyAre() {
		assertThat(Lanes.HEADER).isEqualTo("X-Mugsun-Lane");
		assertThat(Lanes.METADATA_KEY).isEqualTo("lane");
		assertThat(LaneContext.MDC_KEY).isEqualTo("lane");
	}

	private static ServiceInstance instance(String host, String lane) {
		Map<String, String> metadata = new HashMap<>();
		if (lane != null) {
			metadata.put(Lanes.METADATA_KEY, lane);
		}
		return new DefaultServiceInstance(host + ":8102", "mugsun-gis", host, 8102, false, metadata);
	}
}
