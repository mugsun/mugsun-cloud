package com.mugsun.cloud.common.inner;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mugsun.boot.tenant.TenantContext;
import com.mugsun.cloud.common.inner.InnerCallException.Kind;
import com.mugsun.cloud.common.lane.LaneContext;
import com.mugsun.cloud.common.lane.Lanes;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.cloud.client.DefaultServiceInstance;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;

import java.net.ConnectException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 内部调用按灰度标记找实例：没有标记的请求只找正式实例；带标记的先找同标记的，没有或者不行就找正式实例，标记接着带过去。
 *
 * <p>客户端每次都把实例打乱顺序，所以每种情况都调很多轮：不管哪个实例排在前面，结论都要成立。
 */
class InnerClientLaneTest {

	/** 测试用的假密钥 */
	private static final String SECRET = "test-only-key-0123456789abcdef-XYZ";

	private static final byte[] KEY = SECRET.getBytes(StandardCharsets.UTF_8);

	private static final String SELF = "mugsun-ai";

	private static final String TARGET = "mugsun-gis";

	private static final String PATH = "/inner/gis/insight/snapshot";

	private static final String OK_REPLY = "{\"ok\":true,\"data\":{\"count\":3}}";

	private static final String BUSY_REPLY = "{\"ok\":false,\"code\":\"busy\",\"msg\":\"这个内部接口正忙，这次没有执行\"}";

	/** 两个正式实例 */
	private static final String BASE = "10.0.0.5";

	private static final String BASE_2 = "10.0.0.6";

	/** 标记是 canary 的两个灰度实例 */
	private static final String CANARY = "10.0.1.1";

	private static final String CANARY_2 = "10.0.1.2";

	/** 标记是 blue 的灰度实例 */
	private static final String BLUE = "10.0.2.1";

	private static final int ROUNDS = 40;

	private final ObjectMapper mapper = new ObjectMapper();

	private final DiscoveryClient discovery = mock(DiscoveryClient.class);

	/** 发出去的请求，按顺序 */
	private final List<Sent> sent = new ArrayList<>();

	/** 连不上的实例 */
	private final Set<String> unreachable = new HashSet<>();

	/** 回「忙」的实例 */
	private final Set<String> busy = new HashSet<>();

	/**
	 * @param host 发给了哪个实例
	 * @param lane 请求头里带的灰度标记，没带是 null
	 */
	private record Sent(String host, String lane) {
	}

	@AfterEach
	void cleanUp() {
		TenantContext.clear();
		MDC.clear();
	}

	// ---------- 找哪一组实例 ----------

	@Test
	void requestWithoutLaneNeverReachesGreyInstances() {
		registered(instance(CANARY, "canary"), instance(BASE, null), instance(BLUE, "blue"));
		InnerClient client = client(null);

		for (int round = 0; round < ROUNDS; round++) {
			assertThat(failureOf(client)).isNull();
		}

		assertThat(sent).hasSize(ROUNDS).containsOnly(new Sent(BASE, null));
	}

	@Test
	void requestInALaneGoesToThatLaneAndCarriesTheLaneOn() {
		registered(instance(BASE, null), instance(CANARY, "canary"), instance(BLUE, "blue"), instance(BASE_2, null));
		InnerClient client = client(null);
		LaneContext.set("canary");

		for (int round = 0; round < ROUNDS; round++) {
			assertThat(failureOf(client)).isNull();
		}

		assertThat(sent).hasSize(ROUNDS).containsOnly(new Sent(CANARY, "canary"));
	}

	/** 对方没有这个标记的实例：找正式实例。标记照样带过去，对方再往后调时还要用 */
	@Test
	void laneWithoutInstancesOfItsOwnUsesBaseAndStillCarriesTheLane() {
		registered(instance(BASE, null), instance(BLUE, "blue"));
		InnerClient client = client(null);
		LaneContext.set("canary");

		for (int round = 0; round < ROUNDS; round++) {
			assertThat(failureOf(client)).isNull();
		}

		assertThat(sent).hasSize(ROUNDS).containsOnly(new Sent(BASE, "canary"));
	}

	@Test
	void instancesOfOtherLanesAreNeverUsed() {
		registered(instance(BLUE, "blue"));
		InnerClient client = client(null);

		InnerCallException withoutLane = failureOf(client);
		LaneContext.set("canary");
		InnerCallException inAnotherLane = failureOf(client);

		assertThat(withoutLane.kind()).isEqualTo(Kind.NO_INSTANCE);
		assertThat(withoutLane.absent()).isTrue();
		assertThat(withoutLane).hasMessageContaining("灰度实例").hasMessageContaining("没有灰度标记");
		assertThat(inAnotherLane.kind()).isEqualTo(Kind.NO_INSTANCE);
		assertThat(inAnotherLane).hasMessageContaining("灰度实例").hasMessageContaining("canary");
		assertThat(sent).isEmpty();
	}

	/** 登记的标记写法不合规的实例，谁也不找它：当成正式实例的话正常的请求就发过去了 */
	@Test
	void instanceRegisteredWithAMalformedLaneIsNeverUsed() {
		registered(instance(BLUE, "Canary"), instance(BASE, "  "));
		InnerClient client = client(null);

		for (int round = 0; round < ROUNDS; round++) {
			assertThat(failureOf(client)).isNull();
		}
		LaneContext.set("canary");
		for (int round = 0; round < ROUNDS; round++) {
			assertThat(failureOf(client)).isNull();
		}

		assertThat(sent).hasSize(ROUNDS * 2).extracting(Sent::host).containsOnly(BASE);
	}

	// ---------- 试的那个实例不行 ----------

	/** 灰度实例连不上：接着试的是正式实例，不是另一个灰度实例 */
	@Test
	void whenTheLaneInstanceCannotBeReachedTheCallMovesToBase() {
		registered(instance(CANARY, "canary"), instance(BASE, null), instance(CANARY_2, "canary"), instance(BASE_2, null));
		unreachable.addAll(List.of(CANARY, CANARY_2));
		LaneContext.set("canary");

		for (int round = 0; round < ROUNDS; round++) {
			sent.clear();

			assertThat(failureOf(client(null))).isNull();

			assertThat(sent).hasSize(2);
			assertThat(sent.get(0).host()).isIn(CANARY, CANARY_2);
			assertThat(sent.get(1).host()).isIn(BASE, BASE_2);
			assertThat(sent.get(1).lane()).isEqualTo("canary");
		}
	}

	@Test
	void whenTheLaneInstanceIsBusyTheCallMovesToBase() {
		registered(instance(CANARY, "canary"), instance(CANARY_2, "canary"), instance(BASE, null));
		busy.addAll(List.of(CANARY, CANARY_2));
		LaneContext.set("canary");

		for (int round = 0; round < ROUNDS; round++) {
			sent.clear();

			assertThat(failureOf(client(null))).isNull();

			assertThat(sent).extracting(Sent::host).hasSize(2).last().isEqualTo(BASE);
			assertThat(sent.get(0).host()).isIn(CANARY, CANARY_2);
		}
	}

	/** 对方只有灰度实例、没有正式实例：这时才换另一个灰度实例 */
	@Test
	void withoutBaseInstancesTheOtherLaneInstanceIsTried() {
		registered(instance(CANARY, "canary"), instance(CANARY_2, "canary"));
		unreachable.add(CANARY);
		LaneContext.set("canary");
		int retried = 0;

		for (int round = 0; round < ROUNDS; round++) {
			sent.clear();

			assertThat(failureOf(client(null))).isNull();

			assertThat(sent).last().isEqualTo(new Sent(CANARY_2, "canary"));
			if (sent.size() == 2) {
				assertThat(sent.get(0).host()).isEqualTo(CANARY);
				retried++;
			}
		}
		assertThat(retried).as("连不上的那个排在前面的轮数").isPositive();
	}

	/** 最多还是试两个：一个灰度的，一个正式的 */
	@Test
	void whenBaseCannotBeReachedEitherTheCallFailsAfterTwoAttempts() {
		registered(instance(CANARY, "canary"), instance(CANARY_2, "canary"), instance(BASE, null), instance(BASE_2, null));
		unreachable.addAll(List.of(CANARY, CANARY_2, BASE, BASE_2));
		LaneContext.set("canary");

		for (int round = 0; round < ROUNDS; round++) {
			sent.clear();

			InnerCallException e = failureOf(client(null));

			assertThat(e.kind()).isEqualTo(Kind.FAILED);
			assertThat(sent).hasSize(2);
			assertThat(sent.get(0).host()).isIn(CANARY, CANARY_2);
			assertThat(sent.get(1).host()).isIn(BASE, BASE_2);
		}
	}

	/** 没有标记的请求换实例重试时，也只在正式实例里换 */
	@Test
	void retryOfARequestWithoutLaneStaysAmongBaseInstances() {
		registered(instance(BASE, null), instance(CANARY, "canary"), instance(BASE_2, null), instance(CANARY_2, "canary"));
		unreachable.add(BASE);
		int retried = 0;

		for (int round = 0; round < ROUNDS; round++) {
			sent.clear();

			assertThat(failureOf(client(null))).isNull();

			assertThat(sent).extracting(Sent::host).isSubsetOf(BASE, BASE_2).last().isEqualTo(BASE_2);
			retried += sent.size() - 1;
		}
		assertThat(retried).as("连不上的那个排在前面的轮数").isPositive();
	}

	/**
	 * 一个灰度实例接连失败到被熔断以后，跳过它不算「试过一个灰度实例」：另一个灰度实例好着就用它，请求不会因此改去正式实例。
	 */
	@Test
	void laneInstanceBehindAnOpenBreakerGivesWayToAnotherLaneInstance() {
		registered(instance(CANARY, "canary"), instance(CANARY_2, "canary"), instance(BASE, null));
		unreachable.add(CANARY);
		LaneContext.set("canary");
		InnerClient client = client(null);

		// 连不上的那个灰度实例失败满 3 次就被熔断（测试里的时钟不走，熔断不会到期）
		for (int round = 0; sentTo(CANARY) < 3; round++) {
			assertThat(round).as("调了这么多轮，连不上的那个还没有试满 3 次").isLessThan(1000);
			assertThat(failureOf(client)).isNull();
		}
		assertThat(sentTo(BASE)).as("熔断之前，每次连不上都改去正式实例").isEqualTo(3);
		sent.clear();

		for (int round = 0; round < ROUNDS; round++) {
			assertThat(failureOf(client)).isNull();
		}

		assertThat(sent).hasSize(ROUNDS).containsOnly(new Sent(CANARY_2, "canary"));
	}

	// ---------- 这个实例自己是灰度实例 ----------

	/** 灰度实例自己发起的调用（定时任务，或者有人不经网关直接访问它）：按它自己的标记找 */
	@Test
	void greyInstanceUsesItsOwnLaneWhenTheRequestHasNone() {
		registered(instance(BASE, null), instance(CANARY, "canary"), instance(BLUE, "blue"));
		InnerClient client = client("canary");

		for (int round = 0; round < ROUNDS; round++) {
			assertThat(failureOf(client)).isNull();
		}

		assertThat(sent).hasSize(ROUNDS).containsOnly(new Sent(CANARY, "canary"));
	}

	@Test
	void theRequestsOwnLaneWinsOverTheInstancesLane() {
		registered(instance(BASE, null), instance(CANARY, "canary"), instance(BLUE, "blue"));
		InnerClient client = client("canary");
		LaneContext.set("blue");

		for (int round = 0; round < ROUNDS; round++) {
			assertThat(failureOf(client)).isNull();
		}

		assertThat(sent).hasSize(ROUNDS).containsOnly(new Sent(BLUE, "blue"));
	}

	// ---------- 对方在不在 ----------

	/** 对方只有灰度实例时，对没有标记的请求来说它就是没有部署 */
	@Test
	void reachableCountsOnlyInstancesTheRequestMayUse() {
		registered(instance(CANARY, "canary"));
		InnerClient plain = client(null);

		assertThat(plain.reachable(TARGET)).isFalse();

		LaneContext.set("blue");
		assertThat(plain.reachable(TARGET)).isFalse();

		LaneContext.set("canary");
		assertThat(plain.reachable(TARGET)).isTrue();

		LaneContext.clear();
		assertThat(client("canary").reachable(TARGET)).isTrue();

		registered(instance(CANARY, "canary"), instance(BASE, null));
		assertThat(plain.reachable(TARGET)).isTrue();
		LaneContext.set("blue");
		assertThat(plain.reachable(TARGET)).isTrue();
	}

	// ---------- 辅助 ----------

	/** 调一次，返回它抛出的 InnerCallException；调用成功返回 null */
	private InnerCallException failureOf(InnerClient client) {
		return catchThrowableOfType(InnerCallException.class,
			() -> TenantContext.execute("000000", () -> client.call(TARGET, PATH, Map.of())));
	}

	private long sentTo(String host) {
		return sent.stream().filter(request -> request.host().equals(host)).count();
	}

	/**
	 * @param instanceLane 发起调用的这个实例自己的灰度标记，正式实例传 null
	 */
	private InnerClient client(String instanceLane) {
		InnerProperties properties = new InnerProperties();
		properties.setSecret(SECRET);
		properties.setBreakerFailures(3);
		properties.validate();
		InnerClient.Transport transport = (uri, headers, body, readTimeout) -> {
			String host = uri.getHost();
			sent.add(new Sent(host, headers.get(Lanes.HEADER)));
			if (unreachable.contains(host)) {
				throw new ConnectException("Connection refused");
			}
			byte[] reply = (busy.contains(host) ? BUSY_REPLY : OK_REPLY).getBytes(StandardCharsets.UTF_8);
			return new InnerClient.Reply(200, InnerProtocol.signResponse(KEY, PATH,
				headers.get(InnerProtocol.HEADER_NONCE), SELF, TARGET, reply), reply);
		};
		return new InnerClient(properties, discovery, mapper, SELF, instanceLane, transport, () -> 1_700_000_000_000L,
			() -> 0L);
	}

	private void registered(ServiceInstance... instances) {
		when(discovery.getInstances(TARGET)).thenReturn(List.of(instances));
	}

	private static ServiceInstance instance(String host, String lane) {
		Map<String, String> metadata = new HashMap<>();
		if (lane != null) {
			metadata.put(Lanes.METADATA_KEY, lane);
		}
		return new DefaultServiceInstance(host + ":8102", TARGET, host, 8102, false, metadata);
	}
}
