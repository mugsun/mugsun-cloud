package com.mugsun.cloud.common.inner;

import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Plugin;
import org.apache.ibatis.plugin.Signature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 处理内部调用期间发出的查询，最多跑到调用方不再等为止。
 *
 * <p>调用方等不到响应就按失败处理了（默认 10 秒）。没有这个时限的话，这边卡住的查询会一直留在库里，占着连接，
 * 结果也没人要。时限用 JDBC 的 {@code Statement.setQueryTimeout} 设，到点由数据库驱动取消这条语句。
 *
 * <p>只管走 MyBatis 的语句（Mapper、{@code Db}）。直接用 {@code JdbcTemplate} 发的语句不经过这里，管不到。
 *
 * <p>只在处理内部调用的线程上生效（见 {@link InnerDeadline}）：用户请求、定时任务、数据库迁移的语句一律不碰。
 * 语句本来就有更短的时限（比如事务快到期了）时也不动它。
 */
@Component
@Intercepts(@Signature(type = StatementHandler.class, method = "prepare", args = {Connection.class, Integer.class}))
class InnerQueryTimeout implements Interceptor {

	private static final Logger log = LoggerFactory.getLogger(InnerQueryTimeout.class);

	private final AtomicBoolean warned = new AtomicBoolean();

	@Override
	public Object intercept(Invocation invocation) throws Throwable {
		Object prepared = invocation.proceed();
		int seconds = InnerDeadline.secondsLeft();
		if (seconds > 0 && prepared instanceof Statement statement) {
			limit(statement, seconds);
		}
		return prepared;
	}

	/** 不在处理内部调用时原样返回，不套代理：别的请求不为这个功能多走一层 */
	@Override
	public Object plugin(Object target) {
		return target instanceof StatementHandler && InnerDeadline.active() ? Plugin.wrap(target, this) : target;
	}

	private void limit(Statement statement, int seconds) {
		try {
			int current = statement.getQueryTimeout();
			if (current <= 0 || seconds < current) {
				statement.setQueryTimeout(seconds);
			}
		} catch (SQLException e) {
			// 设不上时限不能让语句跑不了：照常执行，只是没有时限
			if (warned.compareAndSet(false, true)) {
				log.warn("数据库驱动不让给语句设时限，处理内部调用时发出的查询没有时限：{}。这条提示只打一次", e.toString());
			}
		}
	}
}
