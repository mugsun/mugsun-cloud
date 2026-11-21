package com.mugsun.cloud.common.startup;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.Configuration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 迁移锁：占得到就迁移、迁移完放开；别的实例占着就等；等不到、数据库占不了这把锁时不排队直接迁移。
 * 前一半用假的锁看排队的规则，等待不真等，只记下等了几次；后一半用模拟的数据库连接看发给数据库的语句和连接有没有还回去。
 * 真的 PostgreSQL 上几个迁移同时跑的效果是另外实测的，结果在 docs/deployment.md 第 7 节。
 */
class MigrationLockTest {

	private static final Duration TWO_MINUTES = Duration.ofMinutes(2);

	private final List<String> events = new ArrayList<>();

	private final List<Duration> sleeps = new ArrayList<>();

	private final DataSource dataSource = mock(DataSource.class);

	private final Connection connection = mock(Connection.class);

	private final PreparedStatement tryLock = mock(PreparedStatement.class);

	private final PreparedStatement unlock = mock(PreparedStatement.class);

	private final Flyway flyway = mock(Flyway.class);

	@AfterEach
	void clearInterrupt() {
		Thread.interrupted();
	}

	/** 假的锁：前 busyTimes 次占不到 */
	private final class FakeLock implements MigrationLock.DbLock {

		private int busyTimes;

		private SQLException failure;

		FakeLock(int busyTimes) {
			this.busyTimes = busyTimes;
		}

		@Override
		public boolean tryAcquire() throws SQLException {
			if (failure != null) {
				throw failure;
			}
			if (busyTimes > 0) {
				busyTimes--;
				events.add("busy");
				return false;
			}
			events.add("acquired");
			return true;
		}

		@Override
		public void release() {
			events.add("released");
		}

		@Override
		public void abandon() {
			events.add("abandoned");
		}
	}

	private MigrationLock lock(Duration maxWait) {
		return new MigrationLock(maxWait, sleeps::add);
	}

	private void migration() {
		events.add("migrated");
	}

	@Test
	void migratesWhileHoldingTheLockThenReleasesIt() {
		lock(TWO_MINUTES).run(() -> new FakeLock(0), this::migration);

		assertThat(events).containsExactly("acquired", "migrated", "released");
		assertThat(sleeps).isEmpty();
	}

	@Test
	void waitsWhileAnotherInstanceIsMigrating() {
		lock(TWO_MINUTES).run(() -> new FakeLock(3), this::migration);

		assertThat(events).containsExactly("busy", "busy", "busy", "acquired", "migrated", "released");
		assertThat(sleeps).isEqualTo(Collections.nCopies(3, MigrationLock.RETRY_INTERVAL));
	}

	@Test
	void stopsWaitingAfterMaxWaitAndMigratesWithoutTheLock() {
		lock(Duration.ofSeconds(1)).run(() -> new FakeLock(Integer.MAX_VALUE), this::migration);

		// 1 秒里每 200 毫秒试一次：等了 5 次，第 6 次还占不到就不等了
		assertThat(sleeps).hasSize(5);
		assertThat(events).doesNotContain("acquired", "released");
		assertThat(events.subList(events.size() - 2, events.size())).containsExactly("abandoned", "migrated");
	}

	@Test
	void releasesTheLockWhenTheMigrationFails() {
		MigrationLock lock = lock(TWO_MINUTES);

		assertThatThrownBy(() -> lock.run(() -> new FakeLock(0), () -> {
			throw new IllegalStateException("迁移脚本有错");
		})).isInstanceOf(IllegalStateException.class).hasMessage("迁移脚本有错");

		assertThat(events).containsExactly("acquired", "released");
	}

	@Test
	void migratesWithoutTheLockWhenTheDatabaseHasNoSuchLock() {
		FakeLock unsupported = new FakeLock(0);
		unsupported.failure = new SQLException("function pg_try_advisory_lock(bigint) does not exist");

		lock(TWO_MINUTES).run(() -> unsupported, this::migration);

		assertThat(events).containsExactly("abandoned", "migrated");
	}

	@Test
	void migratesWithoutTheLockWhenThereIsNoConnectionForIt() {
		lock(TWO_MINUTES).run(() -> {
			throw new SQLException("连接池满了");
		}, this::migration);

		assertThat(events).containsExactly("migrated");
	}

	@Test
	void migratesWithoutTheLockWhenInterruptedWhileWaiting() {
		MigrationLock lock = new MigrationLock(TWO_MINUTES, duration -> {
			throw new InterruptedException();
		});

		lock.run(() -> new FakeLock(1), this::migration);

		assertThat(events).containsExactly("busy", "abandoned", "migrated");
		assertThat(Thread.currentThread().isInterrupted()).isTrue();
	}

	/** Flyway 自己的锁用的数是 77431708279161 加上表名的哈希（一个 int），这里的数要在那个范围之外 */
	@Test
	void theKeyCannotBeTheOneFlywayUses() {
		assertThat(Math.abs(MigrationLock.KEY - 77431708279161L)).isGreaterThan(1L << 32);
	}

	private void database(boolean autoCommit, Boolean firstTry, Boolean... laterTries) throws SQLException {
		Configuration configuration = mock(Configuration.class);
		when(flyway.getConfiguration()).thenReturn(configuration);
		when(configuration.getDataSource()).thenReturn(dataSource);
		when(dataSource.getConnection()).thenReturn(connection);
		when(connection.getAutoCommit()).thenReturn(autoCommit);
		when(connection.prepareStatement("select pg_try_advisory_lock(?)")).thenReturn(tryLock);
		when(connection.prepareStatement("select pg_advisory_unlock(?)")).thenReturn(unlock);
		ResultSet locked = mock(ResultSet.class);
		when(locked.next()).thenReturn(true);
		when(locked.getBoolean(1)).thenReturn(firstTry, laterTries);
		when(tryLock.executeQuery()).thenReturn(locked);
		ResultSet unlocked = mock(ResultSet.class);
		when(unlocked.next()).thenReturn(true);
		when(unlocked.getBoolean(1)).thenReturn(true);
		when(unlock.executeQuery()).thenReturn(unlocked);
	}

	@Test
	void locksAndUnlocksOnOneConnectionAroundTheMigration() throws SQLException {
		database(true, true);

		lock(TWO_MINUTES).migrate(flyway);

		InOrder order = inOrder(connection, tryLock, flyway, unlock);
		order.verify(tryLock).setLong(1, MigrationLock.KEY);
		order.verify(tryLock).executeQuery();
		order.verify(flyway).migrate();
		order.verify(unlock).setLong(1, MigrationLock.KEY);
		order.verify(unlock).executeQuery();
		order.verify(connection).close();
		verify(dataSource, times(1)).getConnection();
		verify(connection, never()).setAutoCommit(anyBoolean());
	}

	@Test
	void keepsTryingOnTheSameConnectionUntilTheLockIsFree() throws SQLException {
		database(true, false, false, true);

		lock(TWO_MINUTES).migrate(flyway);

		verify(tryLock, times(3)).executeQuery();
		verify(dataSource, times(1)).getConnection();
		assertThat(sleeps).hasSize(2);
		InOrder order = inOrder(tryLock, flyway, unlock);
		order.verify(tryLock, times(3)).executeQuery();
		order.verify(flyway).migrate();
		order.verify(unlock).executeQuery();
	}

	/** 连接池配成不自动提交时：占锁的语句不能留下一个开着的事务，还连接之前要改回原样 */
	@Test
	void holdsNoOpenTransactionWhileLocked() throws SQLException {
		database(false, true);

		lock(TWO_MINUTES).migrate(flyway);

		InOrder order = inOrder(connection, tryLock, flyway, unlock);
		order.verify(connection).setAutoCommit(true);
		order.verify(tryLock).executeQuery();
		order.verify(flyway).migrate();
		order.verify(unlock).executeQuery();
		order.verify(connection).setAutoCommit(false);
		order.verify(connection).close();
	}

	@Test
	void unlocksAndReturnsTheConnectionWhenTheMigrationFails() throws SQLException {
		database(true, true);
		when(flyway.migrate()).thenThrow(new IllegalStateException("迁移脚本有错"));

		assertThatThrownBy(() -> lock(TWO_MINUTES).migrate(flyway)).isInstanceOf(IllegalStateException.class);

		InOrder order = inOrder(connection, unlock);
		order.verify(unlock).executeQuery();
		order.verify(connection).close();
	}

	/** 放锁的语句没执行成：连接要是原样还回连接池，锁会跟着这条连接一直占着，所以要把它断掉 */
	@Test
	void dropsTheConnectionWhenUnlockingFails() throws SQLException {
		database(true, true);
		when(unlock.executeQuery()).thenThrow(new SQLException("connection reset"));

		lock(TWO_MINUTES).migrate(flyway);

		InOrder order = inOrder(flyway, connection);
		order.verify(flyway).migrate();
		order.verify(connection).abort(any());
		order.verify(connection).close();
	}

	/** 数据库说锁不在这条连接上（经过了事务模式的连接池）：断掉这条连接也放不了锁，只记警告，连接照常还回去 */
	@Test
	void returnsTheConnectionAsUsualWhenTheLockIsNotOnIt() throws SQLException {
		database(true, true);
		ResultSet notHeld = mock(ResultSet.class);
		when(notHeld.next()).thenReturn(true);
		when(notHeld.getBoolean(1)).thenReturn(false);
		when(unlock.executeQuery()).thenReturn(notHeld);

		lock(TWO_MINUTES).migrate(flyway);

		verify(flyway).migrate();
		verify(connection).close();
		verify(connection, never()).abort(any());
	}

	@Test
	void returnsTheConnectionAndMigratesWhenTheDatabaseRejectsTheLockStatement() throws SQLException {
		database(true, true);
		when(tryLock.executeQuery()).thenThrow(new SQLException("无效的方法名[PG_TRY_ADVISORY_LOCK]"));

		lock(TWO_MINUTES).migrate(flyway);

		verify(flyway).migrate();
		verify(connection).close();
		verify(connection, never()).prepareStatement("select pg_advisory_unlock(?)");
		verify(connection, never()).abort(any());
	}

	@Test
	void returnsTheConnectionWhenItCannotBePrepared() throws SQLException {
		database(true, true);
		when(connection.getAutoCommit()).thenThrow(new SQLException("connection is closed"));

		lock(TWO_MINUTES).migrate(flyway);

		verify(connection).close();
		verify(connection, never()).prepareStatement(anyString());
		verify(flyway).migrate();
	}

	@Test
	void migratesWithoutTheLockWhenThePoolGivesNoConnection() throws SQLException {
		database(true, true);
		when(dataSource.getConnection()).thenThrow(new SQLException("Connection is not available, request timed out"));

		lock(TWO_MINUTES).migrate(flyway);

		verify(flyway).migrate();
	}
}
