package com.mugsun.cloud.common.machine;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.context.ApplicationContextException;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 机器号：每个实例领到的不一样；领不到时用 1 继续跑、以后再领；续期时发现号没了就占回来、被别人领走了就换一个；
 * 停止和启动失败时把登记删掉。登记表用内存里的代替，行为和 Redis 那个一样；定时续期不等时间到，直接调。
 */
class MachineNumberTest {

	private static final Duration TTL = Duration.ofMinutes(3);

	/** 内存里的登记表。可以让它报错；直接改 owners 可以模拟登记过期、被别的实例领走 */
	static class MemoryStore implements MachineNumberStore {

		final Map<Integer, String> owners = new TreeMap<>();

		long cursor;

		boolean broken;

		int releases;

		private void check() {
			if (broken) {
				throw new IllegalStateException("连不上 Redis");
			}
		}

		@Override
		public long nextStart() {
			check();
			return cursor++;
		}

		@Override
		public boolean claim(int number, String owner, Duration ttl) {
			check();
			return owners.putIfAbsent(number, owner) == null;
		}

		@Override
		public Renewal renew(int number, String owner, Duration ttl) {
			check();
			String current = owners.get(number);
			if (owner.equals(current)) {
				return Renewal.RENEWED;
			}
			if (current == null) {
				owners.put(number, owner);
				return Renewal.RECLAIMED;
			}
			return Renewal.TAKEN;
		}

		@Override
		public void release(int number, String owner) {
			check();
			releases++;
			owners.remove(number, owner);
		}
	}

	private final MemoryStore store = new MemoryStore();

	private final List<MachineNumber> started = new ArrayList<>();

	private MachineNumber start(String owner) {
		MachineNumber machineNumber = new MachineNumber(store, TTL, owner);
		started.add(machineNumber);
		machineNumber.afterPropertiesSet();
		return machineNumber;
	}

	@AfterEach
	void stopRenewing() {
		store.broken = false;
		started.forEach(MachineNumber::stop);
	}

	@Test
	void everyInstanceGetsItsOwnNumber() {
		MachineNumber a = start("a");
		MachineNumber b = start("b");
		MachineNumber c = start("c");

		assertThat(List.of(a.current(), b.current(), c.current())).containsExactly(2, 3, 4);
		assertThat(store.owners).containsExactly(Map.entry(2, "a"), Map.entry(3, "b"), Map.entry(4, "c"));
		assertThat(a.isRunning()).isTrue();
	}

	/** 找号的起点每次往后挪：刚放掉的号不会马上发给下一个实例 */
	@Test
	void aReleasedNumberIsNotHandedOutAgainRightAway() {
		MachineNumber a = start("a");
		a.stop();
		assertThat(store.owners).isEmpty();

		assertThat(start("b").current()).isEqualTo(3);
	}

	@Test
	void theSearchWrapsAroundAndSkipsNumbersInUse() {
		store.cursor = 97;
		store.owners.put(99, "别的实例");
		store.owners.put(2, "别的实例");

		assertThat(start("a").current()).isEqualTo(3);
	}

	@Test
	void whenEveryNumberIsInUseItRunsWithTheFallbackAndClaimsOneLater() {
		for (int number = MachineNumber.FIRST; number <= MachineNumber.LAST; number++) {
			store.owners.put(number, "别的实例");
		}
		MachineNumber a = start("a");
		assertThat(a.current()).isEqualTo(MachineNumber.UNASSIGNED);

		store.owners.remove(50);
		a.renew();
		assertThat(a.current()).isEqualTo(50);
		assertThat(store.owners).containsEntry(50, "a");
	}

	@Test
	void whenRedisIsDownAtStartupItRunsWithTheFallbackAndClaimsOneLater() {
		store.broken = true;
		MachineNumber a = start("a");
		List<Integer> told = new ArrayList<>();
		a.onChange(told::add);
		assertThat(a.current()).isEqualTo(MachineNumber.UNASSIGNED);

		a.renew();
		assertThat(a.current()).isEqualTo(MachineNumber.UNASSIGNED);

		store.broken = false;
		a.renew();
		assertThat(a.current()).isEqualTo(2);
		assertThat(told).containsExactly(MachineNumber.UNASSIGNED, 2);
	}

	@Test
	void listenerIsToldTheCurrentNumberRightAway() {
		MachineNumber a = start("a");
		List<Integer> told = new ArrayList<>();
		a.onChange(told::add);

		a.renew();
		assertThat(told).containsExactly(2);
	}

	/** 登记过期了（Redis 断开过，或者进程卡住过），还没有别人领：占回来，号不变 */
	@Test
	void anExpiredRegistrationIsTakenBack() {
		MachineNumber a = start("a");
		List<Integer> told = new ArrayList<>();
		a.onChange(told::add);
		store.owners.clear();

		a.renew();
		assertThat(a.current()).isEqualTo(2);
		assertThat(store.owners).containsExactly(Map.entry(2, "a"));
		assertThat(told).containsExactly(2);
	}

	/** 登记过期以后号被别的实例领走了：换一个，并通知用到机器号的地方；不动别人的登记 */
	@Test
	void aNumberTakenByAnotherInstanceIsReplaced() {
		MachineNumber a = start("a");
		List<Integer> told = new ArrayList<>();
		a.onChange(told::add);
		store.owners.put(2, "别的实例");

		a.renew();
		assertThat(a.current()).isEqualTo(3);
		assertThat(told).containsExactly(2, 3);
		assertThat(store.owners).containsExactly(Map.entry(2, "别的实例"), Map.entry(3, "a"));

		a.stop();
		assertThat(store.owners).containsExactly(Map.entry(2, "别的实例"));
	}

	@Test
	void aFailedRenewalKeepsTheNumber() {
		MachineNumber a = start("a");
		store.broken = true;

		a.renew();
		assertThat(a.current()).isEqualTo(2);
	}

	@Test
	void stoppingRemovesTheRegistrationOnce() {
		MachineNumber a = start("a");

		a.stop();
		a.destroy();
		a.renew();
		assertThat(store.owners).isEmpty();
		assertThat(store.releases).isEqualTo(1);
		assertThat(a.isRunning()).isFalse();
		// 停止过程剩下的时间里接着用这个号
		assertThat(a.current()).isEqualTo(2);
	}

	@Test
	void stoppingWhenRedisIsDownDoesNotThrow() {
		MachineNumber a = start("a");
		store.broken = true;

		a.stop();
		assertThat(a.isRunning()).isFalse();
	}

	@Test
	void rejectsATtlThatIsTooShort() {
		assertThatThrownBy(() -> new MachineNumber(store, Duration.ofSeconds(5), "a"))
			.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("mugsun.cloud.machine-number.ttl");
	}

	/** 删登记要赶在 Redis 连接关掉之前：连接工厂的阶段是 0，这个类排在它后面启动、前面停止 */
	@Test
	void stopsBeforeTheRedisConnectionFactory() {
		assertThat(new LettuceConnectionFactory().getPhase()).isLessThan(MachineNumber.PHASE);

		List<String> events = new ArrayList<>();
		MemoryStore recording = new MemoryStore() {
			@Override
			public void release(int number, String owner) {
				events.add("删登记");
				super.release(number, owner);
			}
		};
		try (GenericApplicationContext context = new GenericApplicationContext()) {
			context.registerBean("machineNumber", MachineNumber.class, () -> new MachineNumber(recording, TTL, "a"));
			context.registerBean("redisConnectionFactory", SmartLifecycle.class,
				() -> lifecycle(0, () -> { }, () -> events.add("关 Redis 连接")));
			context.refresh();
			assertThat(recording.owners).containsExactly(Map.entry(2, "a"));
		}
		assertThat(events).containsExactly("删登记", "关 Redis 连接");
		assertThat(recording.owners).isEmpty();
	}

	/** 启动到最后一步失败（比如登记不上注册中心）：容器会被反复拉起，每次都要把号还回去，不然几轮下来号就占完了 */
	@Test
	void registrationIsRemovedWhenStartupFailsAtTheLastStep() {
		GenericApplicationContext context = new GenericApplicationContext();
		context.registerBean("machineNumber", MachineNumber.class, () -> new MachineNumber(store, TTL, "a"));
		context.registerBean("webServer", SmartLifecycle.class, () -> lifecycle(Integer.MAX_VALUE - 2048, () -> {
			assertThat(store.owners).containsExactly(Map.entry(2, "a"));
			throw new IllegalStateException("登记不上注册中心");
		}, () -> { }));

		assertThatThrownBy(context::refresh).isInstanceOf(ApplicationContextException.class)
			.hasRootCauseMessage("登记不上注册中心");
		assertThat(store.owners).isEmpty();
	}

	/** 启动失败得更早（某个组件建不出来）：Spring 不走停止那一步，只销毁，这时也要把号还回去 */
	@Test
	void registrationIsRemovedWhenABeanCannotBeCreated() {
		GenericApplicationContext context = new GenericApplicationContext();
		context.registerBean("machineNumber", MachineNumber.class, () -> new MachineNumber(store, TTL, "a"));
		context.registerBean("broken", Object.class, () -> {
			assertThat(store.owners).containsExactly(Map.entry(2, "a"));
			throw new IllegalStateException("建不出来");
		});

		assertThatThrownBy(context::refresh).isInstanceOf(BeanCreationException.class).hasRootCauseMessage("建不出来");
		assertThat(store.owners).isEmpty();
	}

	private static SmartLifecycle lifecycle(int phase, Runnable onStart, Runnable onStop) {
		return new SmartLifecycle() {

			private boolean running;

			@Override
			public void start() {
				onStart.run();
				running = true;
			}

			@Override
			public void stop() {
				onStop.run();
				running = false;
			}

			@Override
			public boolean isRunning() {
				return running;
			}

			@Override
			public int getPhase() {
				return phase;
			}
		};
	}
}
