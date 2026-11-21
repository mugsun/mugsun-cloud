package com.mugsun.cloud.gateway.ratelimit;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 令牌桶怎么数：连发多少个、补得多快、要等多久、各客户端和各规则互不影响、内存里的桶会被清掉。时钟是假的，不用真的等。
 */
class RateLimiterTest {

	private static final String ALICE = "192.0.2.1";
	private static final String BOB = "192.0.2.2";

	private final AtomicLong now = new AtomicLong(1_000_000_000L);
	private final RateLimiter limiter = new RateLimiter(now::get);
	private final RateLimitRules rules = RateLimitRules.parse("3/60s", "limit", "/auth=2/10s,/big=1000/1s", "path-limits");
	private final RateLimitRules.Rule threePerMinute = rules.defaultRule();
	private final RateLimitRules.Rule twoPerTenSeconds = rules.ruleFor(List.of("/auth/login"));

	@Test
	void fullBucketAllowsABurstThenRejects() {
		assertThat(allowed(threePerMinute, ALICE)).isTrue();
		assertThat(allowed(threePerMinute, ALICE)).isTrue();
		assertThat(allowed(threePerMinute, ALICE)).isTrue();
		assertThat(allowed(threePerMinute, ALICE)).isFalse();
	}

	@Test
	void rejectionSaysHowLongUntilTheNextToken() {
		drain(threePerMinute, ALICE, 3);

		// 每 60 秒补 3 个，也就是 20 秒一个
		assertThat(limiter.tryAcquire(threePerMinute, ALICE)).isEqualTo(Duration.ofSeconds(20).toNanos());
		advance(Duration.ofSeconds(5));
		assertThat(limiter.tryAcquire(threePerMinute, ALICE)).isEqualTo(Duration.ofSeconds(15).toNanos());
	}

	@Test
	void tokensComeBackOneByOne() {
		drain(threePerMinute, ALICE, 3);

		advance(Duration.ofSeconds(19));
		assertThat(allowed(threePerMinute, ALICE)).isFalse();
		advance(Duration.ofSeconds(1));
		assertThat(allowed(threePerMinute, ALICE)).isTrue();
		assertThat(allowed(threePerMinute, ALICE)).isFalse();
		advance(Duration.ofSeconds(40));
		assertThat(allowed(threePerMinute, ALICE)).isTrue();
		assertThat(allowed(threePerMinute, ALICE)).isTrue();
		assertThat(allowed(threePerMinute, ALICE)).isFalse();
	}

	@Test
	void bucketNeverHoldsMoreThanItsCapacity() {
		drain(threePerMinute, ALICE, 3);

		advance(Duration.ofMinutes(50));
		drain(threePerMinute, ALICE, 3);
		assertThat(allowed(threePerMinute, ALICE)).isFalse();
	}

	@Test
	void rejectedRequestsDoNotMakeTheWaitLonger() {
		drain(threePerMinute, ALICE, 3);
		for (int i = 0; i < 50; i++) {
			assertThat(allowed(threePerMinute, ALICE)).isFalse();
		}

		advance(Duration.ofSeconds(20));
		assertThat(allowed(threePerMinute, ALICE)).isTrue();
	}

	@Test
	void everyClientHasItsOwnBucket() {
		drain(threePerMinute, ALICE, 3);
		assertThat(allowed(threePerMinute, ALICE)).isFalse();

		drain(threePerMinute, BOB, 3);
		assertThat(allowed(threePerMinute, BOB)).isFalse();
	}

	@Test
	void everyRuleHasItsOwnBucket() {
		drain(twoPerTenSeconds, ALICE, 2);
		assertThat(allowed(twoPerTenSeconds, ALICE)).isFalse();

		// 同一个客户端在另一条规则下还是满桶
		drain(threePerMinute, ALICE, 3);
		assertThat(limiter.tryAcquire(twoPerTenSeconds, ALICE)).isEqualTo(Duration.ofSeconds(5).toNanos());
	}

	@Test
	void busyClientGetsExactlyItsRateOverTime() {
		RateLimitRules.Rule rule = rules.ruleFor(List.of("/big"));
		int passed = 0;
		// 每毫秒发 5 个，发 10 秒：开头连发一整桶 1000 个，之后每秒补回 1000 个
		for (int millis = 0; millis < 10_000; millis++) {
			for (int i = 0; i < 5; i++) {
				if (allowed(rule, ALICE)) {
					passed++;
				}
			}
			advance(Duration.ofMillis(1));
		}

		assertThat(passed).isBetween(1000 + 9_990, 1000 + 10_000);
	}

	@Test
	void idleBucketIsDroppedOnceItIsFullAgain() {
		drain(threePerMinute, ALICE, 3);
		drain(twoPerTenSeconds, BOB, 1);
		assertThat(limiter.bucketCount()).isEqualTo(2);

		// 10 秒的那个桶歇够了 10 秒，已经补满，丢掉；60 秒的还没补满，留着。
		// 缓存清过期的桶最多会晚一秒多，所以这里都多等几秒再数
		advance(Duration.ofSeconds(15));
		assertThat(limiter.bucketCount()).isEqualTo(1);
		assertThat(allowed(threePerMinute, ALICE)).isFalse();

		// 又用到了，从这次算起再留 60 秒
		advance(Duration.ofSeconds(55));
		assertThat(limiter.bucketCount()).isEqualTo(1);
		advance(Duration.ofSeconds(10));
		assertThat(limiter.bucketCount()).isZero();

		// 丢掉以后再来，和补满的桶没有区别
		drain(threePerMinute, ALICE, 3);
		assertThat(allowed(threePerMinute, ALICE)).isFalse();
	}

	@Test
	void numberOfBucketsIsBounded() {
		RateLimiter small = new RateLimiter(now::get, 100);

		for (int i = 0; i < 5_000; i++) {
			small.tryAcquire(threePerMinute, "client-" + i);
		}

		assertThat(small.bucketCount()).isLessThanOrEqualTo(100);
	}

	private boolean allowed(RateLimitRules.Rule rule, String client) {
		return limiter.tryAcquire(rule, client) == 0;
	}

	private void drain(RateLimitRules.Rule rule, String client, int tokens) {
		for (int i = 0; i < tokens; i++) {
			assertThat(allowed(rule, client)).as("第 %d 个请求", i + 1).isTrue();
		}
	}

	private void advance(Duration duration) {
		now.addAndGet(duration.toNanos());
	}
}
