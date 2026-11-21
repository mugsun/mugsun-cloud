package com.mugsun.cloud.common.inner;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InnerGateTest {

	private static final String PATH = "/inner/gis/insight/snapshot";

	private static final String A = "000000";

	private static final String B = "T10001";

	private static final String C = "T10002";

	private static final String D = "T10003";

	/** 排队的时限给得很长：测试里轮得到的都很快轮到，等到这么久就是出了错 */
	private static final long LONG = 30_000;

	/** 要在排队里等着的请求，放到这里面跑。和线上一样用虚拟线程 */
	private final ExecutorService background = Executors.newVirtualThreadPerTaskExecutor();

	@AfterEach
	void stopBackground() {
		background.shutdownNow();
	}

	// ---------- 总数 ----------

	@Test
	void letsInUpToTheLimitAndNoMore() {
		InnerGate gate = new InnerGate(PATH, 3, 3);

		assertThat(gate.enter(A, 0)).isTrue();
		assertThat(gate.enter(A, 0)).isTrue();
		assertThat(gate.enter(A, 0)).isTrue();
		assertThat(gate.enter(A, 0)).as("第四个").isFalse();
		assertThat(gate.inService()).isEqualTo(3);

		gate.leave(A);

		assertThat(gate.enter(A, 0)).as("还回来一个以后").isTrue();
		assertThat(gate.enter(A, 0)).isFalse();
	}

	@Test
	void limitsMustMakeSense() {
		assertThatThrownBy(() -> new InnerGate(PATH, 0, 0)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new InnerGate(PATH, 3, 0)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new InnerGate(PATH, 3, 4)).isInstanceOf(IllegalArgumentException.class);

		assertThat(new InnerGate(PATH, 1, 1).enter(A, 0)).isTrue();
	}

	// ---------- 每个租户的份额 ----------

	/** 一个租户占到自己的份额就不能再多占，留出来的名额别的租户马上能用 */
	@Test
	void oneTenantCannotTakeMoreThanItsShare() {
		InnerGate gate = new InnerGate(PATH, 6, 5);
		for (int i = 0; i < 5; i++) {
			assertThat(gate.enter(A, 0)).isTrue();
		}

		assertThat(gate.enter(A, 0)).as("同一个租户的第六个").isFalse();
		assertThat(gate.enter(B, 0)).as("别的租户").isTrue();

		assertThat(gate.holders()).containsExactly(Map.entry(A, 5), Map.entry(B, 1));
	}

	/** 留出来的名额是别的租户合着用的，不是每家一份：总数满了谁也进不来 */
	@Test
	void theTotalStillHoldsAcrossTenants() {
		InnerGate gate = new InnerGate(PATH, 3, 2);
		assertThat(gate.enter(A, 0)).isTrue();
		assertThat(gate.enter(A, 0)).isTrue();
		assertThat(gate.enter(B, 0)).isTrue();

		assertThat(gate.enter(C, 0)).as("还没占过的租户").isFalse();
		assertThat(gate.enter(B, 0)).as("份额没满的租户").isFalse();

		gate.leave(B);

		assertThat(gate.enter(C, 0)).isTrue();
	}

	/** 名额归还到归还的那个租户头上：它还了几个，就又能占几个；全还完就不再记着它 */
	@Test
	void aTenantGetsItsShareBackAsItLeaves() {
		InnerGate gate = new InnerGate(PATH, 4, 2);
		gate.enter(A, 0);
		gate.enter(A, 0);
		gate.enter(B, 0);

		gate.leave(A);

		assertThat(gate.holders()).containsExactly(Map.entry(A, 1), Map.entry(B, 1));
		assertThat(gate.enter(A, 0)).isTrue();
		assertThat(gate.enter(A, 0)).isFalse();

		gate.leave(A);
		gate.leave(A);
		gate.leave(B);

		assertNothingLeft(gate);
	}

	@Test
	void givingBackASlotThatWasNeverTakenIsAnError() {
		InnerGate gate = new InnerGate(PATH, 2, 2);
		gate.enter(A, 0);

		assertThatThrownBy(() -> gate.leave(B)).isInstanceOf(IllegalStateException.class).hasMessageContaining(B);

		assertThat(gate.holders()).containsExactly(Map.entry(A, 1));
		assertThat(gate.inService()).isEqualTo(1);
	}

	// ---------- 排队 ----------

	/** 满了的时候排队，有名额空出来就进去 */
	@Test
	void aWaiterGetsInAsSoonAsASlotIsFreed() throws Exception {
		InnerGate gate = new InnerGate(PATH, 1, 1);
		gate.enter(A, 0);
		Future<Boolean> waiting = background.submit(() -> gate.enter(A, LONG));
		awaitQueued(gate, 1);
		assertThat(waiting.isDone()).isFalse();

		gate.leave(A);

		assertThat(gate.queued()).isZero();
		assertThat(gate.holders()).as("名额已经记到排队的那个名下").containsExactly(Map.entry(A, 1));
		assertThat(waiting.get(5, TimeUnit.SECONDS)).isTrue();
		gate.leave(A);
		assertNothingLeft(gate);
	}

	/** 等到时间还没轮到就算了，并且不留痕迹：后面空出来的名额不会给一个已经走了的 */
	@Test
	void aWaiterThatRunsOutOfTimeLeavesNoTrace() {
		InnerGate gate = new InnerGate(PATH, 1, 1);
		gate.enter(A, 0);

		long started = System.nanoTime();
		boolean admitted = gate.enter(B, 200);
		long waitedMillis = (System.nanoTime() - started) / 1_000_000;

		assertThat(admitted).isFalse();
		assertThat(waitedMillis).isBetween(190L, 20_000L);
		assertThat(gate.queued()).isZero();

		gate.leave(A);

		assertThat(gate.inService()).as("空出来的名额没有给已经走了的").isZero();
		assertThat(gate.enter(B, 0)).isTrue();
	}

	/** 等待时间是 0 就不排队，满了马上回来 */
	@Test
	void zeroWaitMeansNoQueueing() {
		InnerGate gate = new InnerGate(PATH, 1, 1);
		gate.enter(A, 0);

		long started = System.nanoTime();
		boolean admitted = gate.enter(A, 0);
		long waitedMillis = (System.nanoTime() - started) / 1_000_000;

		assertThat(admitted).isFalse();
		assertThat(waitedMillis).isLessThan(5_000L);
		assertThat(gate.queued()).isZero();
	}

	/** 排队时线程被打断：不等了，按没占到算，打断标记留着给外面看，排队里不留它 */
	@Test
	void anInterruptedWaiterGivesUpAndStaysInterrupted() throws Exception {
		InnerGate gate = new InnerGate(PATH, 1, 1);
		gate.enter(A, 0);
		AtomicReference<Boolean> admitted = new AtomicReference<>();
		AtomicBoolean flagKept = new AtomicBoolean();
		Thread waiter = Thread.ofVirtual().start(() -> {
			admitted.set(gate.enter(B, LONG));
			flagKept.set(Thread.currentThread().isInterrupted());
		});
		awaitQueued(gate, 1);

		waiter.interrupt();
		waiter.join(5_000);

		assertThat(waiter.isAlive()).isFalse();
		assertThat(admitted.get()).isFalse();
		assertThat(flagKept).isTrue();
		assertThat(gate.queued()).isZero();
		gate.leave(A);
		assertNothingLeft(gate);
	}

	/** 线程带着打断标记来：有名额照样进，没名额就不排队了 */
	@Test
	void aThreadAlreadyInterruptedGetsAFreeSlotButDoesNotQueue() throws Exception {
		InnerGate gate = new InnerGate(PATH, 1, 1);
		List<Boolean> results = new CopyOnWriteArrayList<>();
		Thread caller = Thread.ofVirtual().start(() -> {
			Thread.currentThread().interrupt();
			results.add(gate.enter(A, LONG));
			results.add(gate.enter(A, LONG));
			results.add(Thread.currentThread().isInterrupted());
		});

		caller.join(5_000);

		assertThat(caller.isAlive()).isFalse();
		assertThat(results).containsExactly(true, false, true);
		assertThat(gate.queued()).isZero();
		assertThat(gate.inService()).isEqualTo(1);
	}

	// ---------- 空出来的名额给谁 ----------

	/** 一个租户的份额满了、还有请求排着队：别的租户不用排在它后面 */
	@Test
	void aTenantQueueingAtItsShareDoesNotHoldUpOthers() throws Exception {
		InnerGate gate = new InnerGate(PATH, 3, 2);
		gate.enter(A, 0);
		gate.enter(A, 0);
		Future<Boolean> thirdOfA = background.submit(() -> gate.enter(A, LONG));
		awaitQueued(gate, 1);

		assertThat(gate.enter(B, 0)).as("别的租户直接进").isTrue();
		assertThat(gate.queued()).as("那个租户多出来的请求接着排").isEqualTo(1);

		gate.leave(A);

		assertThat(thirdOfA.get(5, TimeUnit.SECONDS)).as("它自己还回来一个，才轮到它排着的那个").isTrue();
		assertThat(gate.holders()).containsExactly(Map.entry(A, 2), Map.entry(B, 1));
	}

	/** 同上，但名额是别人还回来的：排在前面的租户份额满了进不去，名额给排在后面、进得去的 */
	@Test
	void aFreedSlotSkipsWaitersWhoseTenantIsAtItsShare() throws Exception {
		InnerGate gate = new InnerGate(PATH, 2, 1);
		gate.enter(A, 0);
		gate.enter(B, 0);
		Future<Boolean> secondOfA = background.submit(() -> gate.enter(A, LONG));
		awaitQueued(gate, 1);
		Future<Boolean> firstOfC = background.submit(() -> gate.enter(C, LONG));
		awaitQueued(gate, 2);

		gate.leave(B);

		assertThat(gate.holders()).containsExactly(Map.entry(A, 1), Map.entry(C, 1));
		assertThat(gate.queued()).isEqualTo(1);
		assertThat(firstOfC.get(5, TimeUnit.SECONDS)).isTrue();

		gate.leave(A);

		assertThat(secondOfA.get(5, TimeUnit.SECONDS)).isTrue();
		assertThat(gate.holders()).containsExactly(Map.entry(A, 1), Map.entry(C, 1));
	}

	/** 空出来的名额先给这时占得最少的租户，哪怕它来得晚 */
	@Test
	void aFreedSlotGoesToTheTenantHoldingFewest() throws Exception {
		InnerGate gate = new InnerGate(PATH, 3, 2);
		gate.enter(A, 0);
		gate.enter(A, 0);
		gate.enter(B, 0);
		Future<Boolean> secondOfB = background.submit(() -> gate.enter(B, LONG));
		awaitQueued(gate, 1);
		Future<Boolean> firstOfC = background.submit(() -> gate.enter(C, LONG));
		awaitQueued(gate, 2);

		gate.leave(A);

		assertThat(gate.holders()).as("一个都没占的租户先进，已经占着一个的接着排")
			.containsExactly(Map.entry(A, 1), Map.entry(B, 1), Map.entry(C, 1));
		assertThat(gate.queued()).isEqualTo(1);
		assertThat(firstOfC.get(5, TimeUnit.SECONDS)).isTrue();

		gate.leave(A);

		assertThat(secondOfB.get(5, TimeUnit.SECONDS)).isTrue();
		assertThat(gate.holders()).containsExactly(Map.entry(B, 2), Map.entry(C, 1));
	}

	/** 占得一样多的租户之间先到先得 */
	@Test
	void tenantsHoldingTheSameAreServedInOrderOfArrival() throws Exception {
		InnerGate gate = new InnerGate(PATH, 2, 1);
		gate.enter(A, 0);
		gate.enter(B, 0);
		Future<Boolean> firstOfC = background.submit(() -> gate.enter(C, LONG));
		awaitQueued(gate, 1);
		Future<Boolean> firstOfD = background.submit(() -> gate.enter(D, LONG));
		awaitQueued(gate, 2);

		gate.leave(A);

		assertThat(gate.holders()).containsExactly(Map.entry(B, 1), Map.entry(C, 1));
		assertThat(firstOfC.get(5, TimeUnit.SECONDS)).isTrue();

		gate.leave(B);

		assertThat(firstOfD.get(5, TimeUnit.SECONDS)).isTrue();
		assertThat(gate.holders()).containsExactly(Map.entry(C, 1), Map.entry(D, 1));
	}

	/** 只有一个租户在用时就是先到先得 */
	@Test
	void oneTenantIsServedInOrderOfArrival() throws Exception {
		InnerGate gate = new InnerGate(PATH, 1, 1);
		gate.enter(A, 0);
		List<Integer> order = new CopyOnWriteArrayList<>();
		List<Future<?>> waiters = new ArrayList<>();
		for (int i = 1; i <= 4; i++) {
			int number = i;
			waiters.add(background.submit(() -> {
				if (gate.enter(A, LONG)) {
					order.add(number);
					gate.leave(A);
				}
			}));
			awaitQueued(gate, i);
		}

		gate.leave(A);

		for (Future<?> waiter : waiters) {
			waiter.get(5, TimeUnit.SECONDS);
		}
		assertThat(order).containsExactly(1, 2, 3, 4);
		assertNothingLeft(gate);
	}

	// ---------- 日志 ----------

	/** 忙的时候不能每个被拒绝的请求刷一行：10 秒最多一行，行里写着它代表几次、这时谁占着名额 */
	@Test
	void refusalsAreLoggedOnceInTenSecondsWithACount() {
		AtomicLong now = new AtomicLong(-5_000);
		InnerGate gate = new InnerGate(PATH, 3, 3, now::get);
		gate.enter(A, 0);
		gate.enter(A, 0);
		gate.enter(B, 0);

		List<String> warnings = warningsWhile(() -> {
			for (int i = 0; i < 5; i++) {
				gate.enter(C, 0);
			}
			now.addAndGet(InnerGate.LOG_EVERY_NANOS - 1);
			gate.enter(C, 0);
			now.addAndGet(1);
			gate.enter(C, 0);
		});

		assertThat(warnings).hasSize(2);
		assertThat(warnings.get(0)).contains(PATH, "上限 3 个（mugsun.cloud.inner.max-concurrent）", "租户 " + C + " 新来的请求",
			"忙", "租户 " + A + " 2 个、租户 " + B + " 1 个", "这一行代表 1 次");
		assertThat(warnings.get(1)).contains("这一行代表 6 次");
	}

	/** 一个租户到了自己的份额，和整个接口满了，是两回事：日志分开说，也分开限频，一种不会把另一种压掉 */
	@Test
	void aTenantAtItsShareIsLoggedApartFromAFullEndpoint() {
		AtomicLong now = new AtomicLong(-5_000);
		InnerGate gate = new InnerGate(PATH, 3, 2, now::get);
		gate.enter(A, 0);
		gate.enter(A, 0);

		List<String> warnings = warningsWhile(() -> {
			gate.enter(A, 0);
			gate.enter(A, 0);
			assertThat(gate.enter(B, 0)).isTrue();
			gate.enter(C, 0);
			gate.enter(C, 0);
			gate.enter(A, 0);
		});

		assertThat(warnings).hasSize(2);
		assertThat(warnings.get(0)).contains(PATH, "租户 " + A + " 同时在处理的请求",
			"每个租户的上限 2 个（mugsun.cloud.inner.max-concurrent-per-tenant）", "忙", "别的租户不受影响", "这一行代表 1 次");
		assertThat(warnings.get(1)).contains("上限 3 个（mugsun.cloud.inner.max-concurrent）", "租户 " + C + " 新来的请求",
			"租户 " + A + " 2 个、租户 " + B + " 1 个", "这一行代表 1 次").doesNotContain("per-tenant");
	}

	/** 没有按租户分（份额和总数一样）时，满了就是接口满了，不说成某个租户到了份额 */
	@Test
	void withoutASeparateShareAFullEndpointIsNotBlamedOnTheTenant() {
		InnerGate gate = new InnerGate(PATH, 2, 2, new AtomicLong(-5_000)::get);
		gate.enter(A, 0);
		gate.enter(A, 0);

		List<String> warnings = warningsWhile(() -> gate.enter(A, 0));

		assertThat(warnings).hasSize(1);
		assertThat(warnings.get(0)).contains("上限 2 个（mugsun.cloud.inner.max-concurrent）").doesNotContain("per-tenant");
	}

	// ---------- 很多请求一起来 ----------

	/**
	 * 名额在不断空出来的时候，排着的不会等到超时：没有「明明进得去却没人叫它」的。
	 * 三个租户的请求数差得很多，一直核对两个上限，最后一个名额都不剩。
	 */
	@Test
	void nobodyWaitsForeverWhileSlotsKeepComingFree() throws Exception {
		InnerGate gate = new InnerGate(PATH, 4, 3);
		Inside inside = new Inside(4, 3);
		AtomicInteger refused = new AtomicInteger();
		List<Future<?>> running = new ArrayList<>();
		Map.of(A, 12, B, 3, C, 1).forEach((tenant, threads) -> {
			for (int i = 0; i < threads; i++) {
				running.add(background.submit(() -> {
					for (int round = 0; round < 2_000; round++) {
						if (!gate.enter(tenant, 10_000)) {
							refused.incrementAndGet();
							return;
						}
						try {
							inside.visit(tenant);
						} finally {
							gate.leave(tenant);
						}
					}
				}));
			}
		});

		for (Future<?> future : running) {
			future.get(60, TimeUnit.SECONDS);
		}

		assertThat(refused).as("名额几微秒就空出来一个，却有人等了 10 秒都没轮到").hasValue(0);
		assertThat(inside.problems).isEmpty();
		assertThat(inside.visits).hasValue(16 * 2_000);
		assertNothingLeft(gate);
	}

	/**
	 * 乱着来一阵：等得到的、等不到的、排到一半被打断的都有（包括刚轮到就被打断的）。
	 * 两个上限始终不超；停下来以后一个名额、一个排队的都不剩，名额还能照常用。
	 */
	@Test
	void underRandomLoadWithInterruptsNothingLeaks() throws Exception {
		InnerGate gate = new InnerGate(PATH, 4, 3);
		Inside inside = new Inside(4, 3);
		String[] tenants = {A, B, C, D};
		AtomicBoolean stop = new AtomicBoolean();
		List<Thread> workers = new ArrayList<>();
		for (int i = 0; i < 24; i++) {
			String tenant = tenants[i % tenants.length];
			workers.add(Thread.ofVirtual().start(() -> {
				ThreadLocalRandom random = ThreadLocalRandom.current();
				while (!stop.get()) {
					if (gate.enter(tenant, random.nextInt(3))) {
						try {
							inside.visit(tenant);
						} finally {
							gate.leave(tenant);
						}
					}
					Thread.interrupted();
				}
			}));
		}

		long began = System.nanoTime();
		ThreadLocalRandom random = ThreadLocalRandom.current();
		while (System.nanoTime() - began < TimeUnit.MILLISECONDS.toNanos(1_500)) {
			workers.get(random.nextInt(workers.size())).interrupt();
			LockSupport.parkNanos(20_000);
		}
		stop.set(true);
		for (Thread worker : workers) {
			worker.join(20_000);
			assertThat(worker.isAlive()).isFalse();
		}

		assertThat(inside.visits.get()).isPositive();
		assertThat(inside.problems).isEmpty();
		assertNothingLeft(gate);
		for (int i = 0; i < 3; i++) {
			assertThat(gate.enter(A, 0)).isTrue();
		}
		assertThat(gate.enter(A, 0)).isFalse();
		assertThat(gate.enter(B, 0)).isTrue();
		assertThat(gate.enter(C, 0)).isFalse();
	}

	// ---------- 辅助 ----------

	/** 等到排队的正好是这么多个：后台的请求要先排进去，测试才能接着往下做 */
	private static void awaitQueued(InnerGate gate, int expected) throws InterruptedException {
		long began = System.nanoTime();
		while (gate.queued() != expected) {
			assertThat(System.nanoTime() - began).as("排队的一直不是 %d 个，现在是 %d 个", expected, gate.queued())
				.isLessThan(TimeUnit.SECONDS.toNanos(5));
			Thread.sleep(1);
		}
	}

	private static void assertNothingLeft(InnerGate gate) {
		assertThat(gate.inService()).as("占着的名额").isZero();
		assertThat(gate.queued()).as("排队的").isZero();
		assertThat(gate.holders()).as("占着名额的租户").isEmpty();
	}

	/** 跑一段代码，返回这期间名额类打出来的警告 */
	private static List<String> warningsWhile(Runnable action) {
		Logger logger = (Logger) LoggerFactory.getLogger(InnerGate.class);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		logger.addAppender(appender);
		try {
			action.run();
		} finally {
			logger.detachAppender(appender);
		}
		return appender.list.stream()
			.filter(event -> event.getLevel() == Level.WARN)
			.map(ILoggingEvent::getFormattedMessage)
			.toList();
	}

	/** 测试自己数的：这一刻有几个请求占着名额、每个租户几个。超了就记一条 */
	private static final class Inside {

		private final int limit;

		private final int perTenant;

		private final AtomicInteger all = new AtomicInteger();

		private final Map<String, AtomicInteger> byTenant = new ConcurrentHashMap<>();

		private final AtomicInteger visits = new AtomicInteger();

		private final List<String> problems = new CopyOnWriteArrayList<>();

		private Inside(int limit, int perTenant) {
			this.limit = limit;
			this.perTenant = perTenant;
		}

		/** 占到名额以后调：数上自己，让别的线程跑一下，再把自己减掉 */
		private void visit(String tenant) {
			AtomicInteger own = byTenant.computeIfAbsent(tenant, key -> new AtomicInteger());
			int allNow = all.incrementAndGet();
			int ownNow = own.incrementAndGet();
			if (allNow > limit) {
				problems.add("同时占着名额的有 " + allNow + " 个，上限是 " + limit);
			}
			if (ownNow > perTenant) {
				problems.add("租户 " + tenant + " 同时占着 " + ownNow + " 个，上限是 " + perTenant);
			}
			Thread.yield();
			own.decrementAndGet();
			all.decrementAndGet();
			visits.incrementAndGet();
		}
	}
}
