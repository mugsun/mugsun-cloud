package com.mugsun.cloud.common.startup;

import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;

/**
 * 几个实例同时启动时，让主库的迁移（Flyway）一个一个来。
 *
 * <p>Flyway 执行迁移脚本的那一步自己有锁，在这之前「看一眼有没有迁移记录表，没有就建」的那一步没有。空库上第一次启动、
 * 又正好有两个实例一起启动时：后到的实例先看到「没有记录表」，接着看「库是不是空的」时，先到的实例刚好把记录表建出来了、
 * 还没往里写，于是后到的实例认为「库不是空的，但没有迁移记录」。mugsun-boot 的配置（baseline-on-migrate）让它在
 * 这种时候补一条基线记录，而 Flyway 不肯往一张已经存在的空记录表里补，后到的实例启动失败，要等被重新拉起来。
 *
 * <p>做法：迁移之前先在同一个库里占一把 PostgreSQL 的 advisory lock（按一个固定的数占，不锁任何表），占到了才迁移，
 * 迁移完放开；没占到就等。这把锁跟着数据库连接走：持有它的实例被强行杀掉时连接断开，锁自己就放了，不用设过期时间、
 * 不用续期，所以没有用 {@link StartupLock} 那种 Redis 里的锁。{@link StartupLock} 管的是迁移之后「改表结构、写初始数据」
 * 的那一段，它占锁的时刻在迁移之后，管不到这里。
 *
 * <p>等够 {@code mugsun.cloud.startup-lock.max-wait}（默认 2 分钟）还没轮到，记一条警告、不排队直接迁移：这时和没有这个类时
 * 一样，执行迁移脚本的那一步仍然有 Flyway 自己的锁。数据库不认这个函数（不是 PostgreSQL，比如达梦）、占锁时出错，也是
 * 不排队直接迁移，不会因为这把锁启动不了。开关和 {@link StartupLock} 共用 {@code mugsun.cloud.startup-lock.enabled}。
 *
 * <p>Flyway 自己在 PostgreSQL 上用的也是 advisory lock（mugsun-boot 把它设成了会话级的），所以这里没有增加新的限制：
 * 连数据库要直连，或者经过会话模式的连接池。两把锁用的数不同，不会互相等。代价是迁移期间多占连接池里的一条连接，
 * 迁移完就还回去。
 *
 * <p>只管 Spring Boot 自动配置的那个 Flyway，也就是主库的迁移，只在 system 服务里生效。埋点库由 track 里同步来的代码
 * 自己迁移，不经过这里。
 */
@Component
@ConditionalOnProperty(name = "spring.flyway.enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnProperty(name = "mugsun.cloud.startup-lock.enabled", havingValue = "true", matchIfMissing = true)
public class MigrationLock implements FlywayMigrationStrategy {

	private static final Logger log = LoggerFactory.getLogger(MigrationLock.class);

	/**
	 * 占锁用的数：「mugsun」六个字母的编码。Flyway 自己的锁用的是「Flyway」的编码（0x466C79776179）加上表名的哈希，
	 * 在那个数上下 2^31 以内，和这个数差得远，不会相同。
	 */
	static final long KEY = 0x6D75_6773_756EL;

	static final Duration RETRY_INTERVAL = Duration.ofMillis(200);

	/** 数据库里的那把锁，连着占锁用的那条连接。单测里换成假的。 */
	interface DbLock {

		/** 占到了返回 true，别人占着返回 false */
		boolean tryAcquire() throws SQLException;

		/** 放开锁，把连接还回去。不抛异常 */
		void release();

		/** 没占到锁时用：只把连接还回去。不抛异常 */
		void abandon();
	}

	@FunctionalInterface
	interface DbLockOpener {

		DbLock open() throws SQLException;
	}

	private final Duration maxWait;

	private final StartupLock.Sleeper sleeper;

	@Autowired
	public MigrationLock(@Value("${mugsun.cloud.startup-lock.max-wait:2m}") Duration maxWait) {
		this(maxWait, duration -> Thread.sleep(duration.toMillis()));
	}

	MigrationLock(Duration maxWait, StartupLock.Sleeper sleeper) {
		this.maxWait = maxWait;
		this.sleeper = sleeper;
	}

	@Override
	public void migrate(Flyway flyway) {
		DataSource dataSource = flyway.getConfiguration().getDataSource();
		run(() -> AdvisoryLock.open(dataSource), flyway::migrate);
	}

	void run(DbLockOpener opener, Runnable migration) {
		DbLock lock = acquire(opener);
		try {
			migration.run();
		} finally {
			if (lock != null) {
				lock.release();
				log.debug("主库迁移做完，迁移锁已放开");
			}
		}
	}

	/** 占到了返回那把锁，迁移完要放开；返回 null 表示不排队直接迁移。 */
	private DbLock acquire(DbLockOpener opener) {
		DbLock lock;
		try {
			lock = opener.open();
		} catch (SQLException | RuntimeException e) {
			log.warn("拿不到数据库连接来占迁移锁，不排队直接迁移：{}", e.getMessage());
			return null;
		}
		Duration waited = Duration.ZERO;
		try {
			while (true) {
				if (lock.tryAcquire()) {
					if (waited.isZero()) {
						log.debug("占到迁移锁，开始迁移主库");
					} else {
						log.info("等了 {} 毫秒，轮到本实例迁移主库", waited.toMillis());
					}
					return lock;
				}
				if (waited.compareTo(maxWait) >= 0) {
					log.warn("等了 {} 秒，另一个实例还没迁移完主库。不再等，不排队直接迁移", waited.toSeconds());
					break;
				}
				if (waited.isZero()) {
					log.info("另一个实例正在迁移主库，等它做完再继续（最多等 {} 秒）", maxWait.toSeconds());
				}
				sleeper.sleep(RETRY_INTERVAL);
				waited = waited.plus(RETRY_INTERVAL);
			}
		} catch (SQLException | RuntimeException e) {
			// 不是 PostgreSQL 的库每次启动都会走到这里，所以不记成警告
			log.info("这个数据库占不了迁移锁（不是 PostgreSQL 时没有这把锁），不排队直接迁移：{}", e.getMessage());
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			log.warn("等迁移锁时被打断，不排队直接迁移");
		}
		lock.abandon();
		return null;
	}

	/** PostgreSQL 的会话级 advisory lock：占在哪条连接上，就要在哪条连接上放，连接断了也就放了。 */
	static final class AdvisoryLock implements DbLock {

		private final Connection connection;

		private final boolean autoCommit;

		private AdvisoryLock(Connection connection, boolean autoCommit) {
			this.connection = connection;
			this.autoCommit = autoCommit;
		}

		static AdvisoryLock open(DataSource dataSource) throws SQLException {
			Connection connection = dataSource.getConnection();
			try {
				boolean autoCommit = connection.getAutoCommit();
				if (!autoCommit) {
					// 锁不靠事务。占着锁的这条连接上不能留着一个没结束的事务：迁移里的 CREATE INDEX CONCURRENTLY 会等它结束
					connection.setAutoCommit(true);
				}
				return new AdvisoryLock(connection, autoCommit);
			} catch (SQLException | RuntimeException e) {
				closeQuietly(connection);
				throw e;
			}
		}

		@Override
		public boolean tryAcquire() throws SQLException {
			return call("select pg_try_advisory_lock(?)");
		}

		@Override
		public void release() {
			try {
				if (!call("select pg_advisory_unlock(?)")) {
					// 直连数据库时不会这样：占锁和放锁用的是同一条连接
					log.warn("放开迁移锁时数据库说锁不在这条连接上，这把锁可能还占着，之后启动的实例要等到不再等为止。"
						+ "连数据库经过了事务模式的连接池（比如 PgBouncer 的 transaction 模式）时会这样，请改成直连或会话模式");
				}
			} catch (SQLException | RuntimeException e) {
				// 连接还回连接池以后还活着的话，锁会一直占着，所以直接把这条连接断掉：连接一断，锁就放了
				log.warn("放开迁移锁时出错，改成断开占锁的那条连接：{}", e.getMessage());
				try {
					connection.abort(Runnable::run);
				} catch (SQLException | RuntimeException ignored) {
					// 断不掉说明连接已经不能用了
				}
			}
			abandon();
		}

		@Override
		public void abandon() {
			try {
				if (!autoCommit && !connection.isClosed()) {
					connection.setAutoCommit(false);
				}
			} catch (SQLException | RuntimeException ignored) {
				// 连接已经不能用了，连接池会把它换掉
			}
			closeQuietly(connection);
		}

		private boolean call(String sql) throws SQLException {
			try (PreparedStatement statement = connection.prepareStatement(sql)) {
				statement.setLong(1, KEY);
				try (ResultSet result = statement.executeQuery()) {
					return result.next() && result.getBoolean(1);
				}
			}
		}

		private static void closeQuietly(Connection connection) {
			try {
				connection.close();
			} catch (SQLException | RuntimeException ignored) {
				// 关不掉的连接由连接池回收
			}
		}
	}
}
