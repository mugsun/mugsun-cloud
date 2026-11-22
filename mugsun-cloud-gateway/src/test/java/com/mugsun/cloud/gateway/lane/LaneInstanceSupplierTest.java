package com.mugsun.cloud.gateway.lane;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.cloud.client.DefaultServiceInstance;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.loadbalancer.DefaultRequest;
import org.springframework.cloud.client.loadbalancer.DefaultRequestContext;
import org.springframework.cloud.client.loadbalancer.Request;
import org.springframework.cloud.client.loadbalancer.RequestData;
import org.springframework.cloud.client.loadbalancer.RequestDataContext;
import org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.util.LinkedMultiValueMap;
import reactor.core.publisher.Flux;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 按灰度标记挑实例：没有标记的请求只有正式实例；有标记的先是同一个标记的灰度实例，没有才是正式实例；别的标记的从来不给；
 * 刚才那个没连上、要换一个时，先换正式实例。经过网关的完整过程在 GatewayLaneTest 里测。
 */
@ExtendWith(OutputCaptureExtension.class)
class LaneInstanceSupplierTest {

	private static final ServiceInstance BASE_1 = instance("10.0.0.1", null);
	private static final ServiceInstance BASE_2 = instance("10.0.0.2", "");
	private static final ServiceInstance CANARY_1 = instance("10.0.0.11", "canary");
	private static final ServiceInstance CANARY_2 = instance("10.0.0.12", "canary");
	private static final ServiceInstance BETA_1 = instance("10.0.0.21", "beta");
	/** 登记的标记写法不对：谁的请求都不给它，也不当成正式实例 */
	private static final ServiceInstance MALFORMED = instance("10.0.0.31", "Canary!");

	private static final List<ServiceInstance> ALL = List.of(BASE_1, CANARY_1, BETA_1, BASE_2, CANARY_2, MALFORMED);

	private static ServiceInstance instance(String host, String lane) {
		Map<String, String> metadata = new HashMap<>();
		if (lane != null) {
			metadata.put("lane", lane);
		}
		return new DefaultServiceInstance("gis-" + host, "mugsun-gis", host, 8102, false, metadata);
	}

	private static LaneInstanceSupplier supplier(List<ServiceInstance> instances) {
		return new LaneInstanceSupplier(new Fixed(instances));
	}

	/** 注册中心里的实例就是给定的这几个 */
	private record Fixed(List<ServiceInstance> instances) implements ServiceInstanceListSupplier {

		@Override
		public String getServiceId() {
			return "mugsun-gis";
		}

		@Override
		public Flux<List<ServiceInstance>> get() {
			return Flux.just(instances);
		}
	}

	private static Request<?> request(Map<String, Object> attributes) {
		RequestData data = new RequestData(HttpMethod.GET, URI.create("http://mugsun-gis/gis/layer/list"), new HttpHeaders(),
			new LinkedMultiValueMap<>(), attributes);
		return new DefaultRequest<>(new RequestDataContext(data));
	}

	private static List<String> warnings(CapturedOutput output) {
		return output.getOut().lines().filter(line -> line.contains("都是灰度实例")).toList();
	}

	@Test
	void requestWithoutALaneGetsOnlyBaseInstances() {
		assertThat(supplier(ALL).pick(ALL, null, null)).containsExactly(BASE_1, BASE_2);
	}

	@Test
	void requestWithALaneGetsTheGreyInstancesOfThatLane() {
		assertThat(supplier(ALL).pick(ALL, "canary", null)).containsExactly(CANARY_1, CANARY_2);
		assertThat(supplier(ALL).pick(ALL, "beta", null)).containsExactly(BETA_1);
	}

	/** 一次发版只动一两个服务：没动的服务没有这个标记的实例，带着标记的请求到了那里照常由正式实例处理 */
	@Test
	void laneWithoutItsOwnInstancesFallsBackToBase() {
		assertThat(supplier(ALL).pick(ALL, "hotfix-1", null)).containsExactly(BASE_1, BASE_2);
		assertThat(supplier(ALL).pick(List.of(BASE_1, BETA_1), "canary", null)).containsExactly(BASE_1);
	}

	@Test
	void instancesOfAnotherLaneAreNeverGiven(CapturedOutput output) {
		LaneInstanceSupplier supplier = supplier(ALL);

		assertThat(supplier.pick(List.of(BETA_1, MALFORMED), "canary", null)).isEmpty();
		// 请求带的标记和那个写法不对的标记一模一样时会对上；但网关算出来的标记都是写法对的，不会有这样的请求
		assertThat(supplier.pick(List.of(BETA_1, MALFORMED), "beta", null)).containsExactly(BETA_1);
		assertThat(warnings(output)).singleElement().asString()
			.contains(" WARN ")
			.contains("mugsun-gis 在注册中心里的 2 个实例都是灰度实例，灰度标记是 canary 的请求不能发给它们，返回 503");
	}

	/** 没有正式实例时，没带标记的请求宁可没有实例可发，也不发给灰度实例：那上面是还没验证过的版本 */
	@Test
	void requestWithoutALaneGetsNothingWhenThereIsNoBaseInstance(CapturedOutput output) {
		assertThat(supplier(ALL).pick(List.of(CANARY_1, CANARY_2, BETA_1), null, null)).isEmpty();

		assertThat(warnings(output)).singleElement().asString()
			.contains("mugsun-gis 在注册中心里的 3 个实例都是灰度实例，没有灰度标记的请求不能发给它们，返回 503")
			.contains("MUGSUN_LANE");
	}

	@Test
	void serviceWithoutAnyInstanceIsNotWarnedAbout(CapturedOutput output) {
		assertThat(supplier(List.of()).pick(List.of(), null, null)).isEmpty();
		assertThat(supplier(List.of()).pick(List.of(), "canary", null)).isEmpty();

		assertThat(warnings(output)).isEmpty();
	}

	/** 每个请求都会走到这里：警告一分钟最多记一次 */
	@Test
	void warningIsWrittenAtMostOnceAMinute(CapturedOutput output) {
		AtomicLong now = new AtomicLong(-TimeUnit.SECONDS.toNanos(5));
		LaneInstanceSupplier supplier = new LaneInstanceSupplier(new Fixed(ALL), now::get);
		List<ServiceInstance> onlyGrey = List.of(CANARY_1);

		supplier.pick(onlyGrey, null, null);
		supplier.pick(onlyGrey, null, null);
		now.addAndGet(TimeUnit.SECONDS.toNanos(59));
		supplier.pick(onlyGrey, null, null);
		assertThat(warnings(output)).hasSize(1);

		now.addAndGet(TimeUnit.SECONDS.toNanos(1));
		supplier.pick(onlyGrey, null, null);
		supplier.pick(onlyGrey, null, null);
		assertThat(warnings(output)).hasSize(2);
	}

	/** 同一个标记的几个灰度实例跑的是同一个新版本，一个起不来，另一个多半也一样：只能再试一次，就试正式实例 */
	@Test
	void afterAFailedGreyInstanceTheBaseInstancesComeFirst() {
		assertThat(supplier(ALL).pick(ALL, "canary", CANARY_1)).containsExactly(BASE_1, BASE_2);
	}

	@Test
	void afterAFailedGreyInstanceAnotherOneOfTheLaneIsTriedOnlyWithoutBaseInstances() {
		assertThat(supplier(ALL).pick(List.of(CANARY_1, CANARY_2, BETA_1), "canary", CANARY_1)).containsExactly(CANARY_2);
	}

	@Test
	void afterAFailureNothingIsLeftWhenTheFailedInstanceWasTheOnlyUsableOne(CapturedOutput output) {
		assertThat(supplier(ALL).pick(List.of(CANARY_1, BETA_1), "canary", CANARY_1)).isEmpty();
		assertThat(supplier(ALL).pick(List.of(BASE_1, CANARY_1), null, BASE_1)).isEmpty();

		// 这次请求已经失败过一次、已经有记录了：不再记「都是灰度实例」
		assertThat(warnings(output)).isEmpty();
	}

	@Test
	void afterAFailedBaseInstanceARequestWithoutALaneStillGetsOnlyBaseInstances() {
		assertThat(supplier(ALL).pick(ALL, null, BASE_1)).containsExactly(BASE_2);
	}

	/** 带着标记、第一次选的是正式实例（这个服务没有该标记的实例），没连上：换另一个正式实例 */
	@Test
	void afterAFailedBaseInstanceARequestWithALaneGetsTheOtherBaseInstances() {
		assertThat(supplier(ALL).pick(List.of(BASE_1, BASE_2, BETA_1), "canary", BASE_1)).containsExactly(BASE_2);
	}

	/** 注册中心每次给的是新的对象：刚才没连上的那个按地址和端口认 */
	@Test
	void failedInstanceIsRecognisedByHostAndPort() {
		ServiceInstance sameAddress = instance("10.0.0.1", null);
		ServiceInstance otherPort = new DefaultServiceInstance("gis-x", "mugsun-gis", "10.0.0.1", 8202, false, Map.of());

		assertThat(supplier(ALL).pick(List.of(BASE_1, BASE_2), null, sameAddress)).containsExactly(BASE_2);
		assertThat(supplier(ALL).pick(List.of(BASE_1, BASE_2), null, otherPort)).containsExactly(BASE_1, BASE_2);
	}

	@Test
	void laneAndFailedInstanceAreTakenFromTheAttributesOfTheForwardedRequest() {
		LaneInstanceSupplier supplier = supplier(ALL);

		assertThat(supplier.get(request(Map.of())).blockFirst()).containsExactly(BASE_1, BASE_2);
		assertThat(supplier.get(request(Map.of(LaneNames.LANE_ATTR, "canary"))).blockFirst())
			.containsExactly(CANARY_1, CANARY_2);
		assertThat(supplier.get(request(Map.of(LaneNames.LANE_ATTR, "canary", LaneNames.FAILED_INSTANCE_ATTR, CANARY_1)))
			.blockFirst()).containsExactly(BASE_1, BASE_2);
		assertThat(supplier.get(request(Map.of(LaneNames.FAILED_INSTANCE_ATTR, BASE_2))).blockFirst())
			.containsExactly(BASE_1);
	}

	/** 不是网关转发的请求（没有那些属性）、属性的类型不对：都按没有标记处理，只有正式实例 */
	@Test
	void anythingElseCountsAsARequestWithoutALane() {
		LaneInstanceSupplier supplier = supplier(ALL);

		assertThat(supplier.get().blockFirst()).containsExactly(BASE_1, BASE_2);
		assertThat(supplier.get(null).blockFirst()).containsExactly(BASE_1, BASE_2);
		assertThat(supplier.get(new DefaultRequest<>()).blockFirst()).containsExactly(BASE_1, BASE_2);
		assertThat(supplier.get(new DefaultRequest<>(new DefaultRequestContext())).blockFirst())
			.containsExactly(BASE_1, BASE_2);
		assertThat(supplier.get(request(Map.of(LaneNames.LANE_ATTR, 42, LaneNames.FAILED_INSTANCE_ATTR, "10.0.0.1")))
			.blockFirst()).containsExactly(BASE_1, BASE_2);
	}

	@Test
	void serviceIdIsThatOfTheInstanceListUnderneath() {
		assertThat(supplier(ALL).getServiceId()).isEqualTo("mugsun-gis");
	}
}
