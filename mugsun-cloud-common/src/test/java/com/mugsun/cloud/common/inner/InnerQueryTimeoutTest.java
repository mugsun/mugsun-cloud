package com.mugsun.cloud.common.inner;

import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.executor.parameter.ParameterHandler;
import org.apache.ibatis.executor.resultset.ResultSetHandler;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.sql.Connection;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class InnerQueryTimeoutTest {

	private static final long SECOND = 1_000_000_000L;

	private final InnerQueryTimeout interceptor = new InnerQueryTimeout();

	private final Connection connection = mock(Connection.class);

	private final Statement statement = mock(Statement.class);

	private final StatementHandler handler = mock(StatementHandler.class);

	@AfterEach
	void clearDeadline() {
		InnerDeadline.clear();
	}

	/** 用户请求、定时任务、数据库迁移的语句：连代理都不套 */
	@Test
	void outsideAnInnerCallNothingIsWrapped() throws Exception {
		when(handler.prepare(connection, null)).thenReturn(statement);

		Object plugged = interceptor.plugin(handler);

		assertThat(plugged).isSameAs(handler);
		((StatementHandler) plugged).prepare(connection, null);
		verifyNoInteractions(statement);
	}

	@Test
	void duringAnInnerCallAStatementMayRunOnlyUntilTheCallerStopsWaiting() throws Exception {
		InnerDeadline.set(System.nanoTime() + 7 * SECOND + SECOND / 2);
		when(handler.prepare(connection, null)).thenReturn(statement);

		Statement prepared = plugged().prepare(connection, null);

		assertThat(prepared).isSameAs(statement);
		// 还剩 7.5 秒，向上取整是 8；机器很慢、这几行跑了半秒以上时是 7
		assertThat(appliedTimeout()).isBetween(7, 8);
	}

	/** 语句本来就有更短的时限（比如事务快到期了）：不动 */
	@Test
	void aShorterLimitAlreadyOnTheStatementIsKept() throws Exception {
		InnerDeadline.set(System.nanoTime() + 8 * SECOND);
		when(handler.prepare(connection, 3)).thenReturn(statement);
		when(statement.getQueryTimeout()).thenReturn(3);

		plugged().prepare(connection, 3);

		verify(statement, never()).setQueryTimeout(anyInt());
	}

	@Test
	void aLongerLimitIsShortened() throws Exception {
		InnerDeadline.set(System.nanoTime() + 8 * SECOND);
		when(handler.prepare(connection, 60)).thenReturn(statement);
		when(statement.getQueryTimeout()).thenReturn(60);

		plugged().prepare(connection, 60);

		assertThat(appliedTimeout()).isBetween(7, 8);
	}

	/** 调用方已经不等了才发出的语句：给 1 秒。0 在 JDBC 里是「不限」，不能用 */
	@Test
	void afterTheDeadlineAStatementGetsOneSecond() throws Exception {
		InnerDeadline.set(System.nanoTime() - 30 * SECOND);
		when(handler.prepare(connection, null)).thenReturn(statement);

		plugged().prepare(connection, null);

		assertThat(appliedTimeout()).isEqualTo(1);
	}

	/** 设不上时限不能让语句跑不了 */
	@Test
	void aDriverThatCannotSetALimitStillRunsTheStatement() throws Exception {
		InnerDeadline.set(System.nanoTime() + 8 * SECOND);
		when(handler.prepare(connection, null)).thenReturn(statement);
		doThrow(new SQLFeatureNotSupportedException("setQueryTimeout")).when(statement).setQueryTimeout(anyInt());

		Statement prepared = plugged().prepare(connection, null);

		assertThat(prepared).isSameAs(statement);
	}

	/** 只管语句的准备这一步；MyBatis 的其它对象不套代理 */
	@Test
	void otherMyBatisObjectsAreNeverWrapped() {
		InnerDeadline.set(System.nanoTime() + 8 * SECOND);

		for (Object other : new Object[] {mock(Executor.class), mock(ParameterHandler.class), mock(ResultSetHandler.class)}) {
			assertThat(interceptor.plugin(other)).isSameAs(other);
		}
	}

	private StatementHandler plugged() {
		Object plugged = interceptor.plugin(handler);
		assertThat(plugged).as("处理内部调用时要套上代理").isNotSameAs(handler);
		return (StatementHandler) plugged;
	}

	private int appliedTimeout() throws Exception {
		ArgumentCaptor<Integer> seconds = ArgumentCaptor.forClass(Integer.class);
		verify(statement).setQueryTimeout(seconds.capture());
		return seconds.getValue();
	}
}
