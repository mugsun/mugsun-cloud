package com.mugsun.cloud.system.ai;

import com.mugsun.boot.ai.mapper.AiSecretMapper;
import com.mybatisflex.core.dialect.impl.CommonsDialectImpl;
import com.mybatisflex.core.query.CPI;
import com.mybatisflex.core.query.QueryCondition;
import com.mybatisflex.core.query.QueryWrapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

/**
 * 删租户时清 AI 密钥：只删这一个租户的，租户编码是空的时候不动库。
 */
class CloudAiSecretPurgerTest {

	private final AiSecretMapper mapper = mock(AiSecretMapper.class);

	private final CloudAiSecretPurger purger = new CloudAiSecretPurger(mapper);

	@Test
	void deletesOnlyTheSecretsOfThatTenant() {
		purger.purgeByTenantCode("T10001");

		ArgumentCaptor<QueryWrapper> query = ArgumentCaptor.forClass(QueryWrapper.class);
		verify(mapper).deleteByQuery(query.capture());
		verifyNoMoreInteractions(mapper);

		QueryCondition where = CPI.getWhereQueryCondition(query.getValue());
		String sql = where.toSql(List.of(), new CommonsDialectImpl()).replaceAll("\\s+", " ").trim();
		assertThat(sql).isEqualTo("tenant_id = ?");
		assertThat(CPI.getNextCondition(where)).as("除了租户，没有别的条件").isNull();
		assertThat(CPI.getValueArray(query.getValue())).containsExactly("T10001");
	}

	@Test
	void doesNothingWithoutATenantCode() {
		purger.purgeByTenantCode(null);
		purger.purgeByTenantCode("");
		purger.purgeByTenantCode("  ");

		verifyNoInteractions(mapper);
	}
}
