package com.mugsun.cloud.system.ai;

import com.mugsun.boot.ai.mapper.AiSecretMapper;
import com.mugsun.boot.tenant.TenantCredentialPurger;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.stereotype.Component;

/**
 * 删租户时清掉这个租户的 AI 密钥（{@code ai_secret} 表，OpenAI 兼容接口用的那种）。
 *
 * <p>单体版里这件事由 AI 模块的 {@code AiSecretTenantPurger} 做：{@code TenantService} 删租户时，在同一个事务里调用容器里所有的
 * {@link TenantCredentialPurger}。微服务版里 AI 模块在 ai 服务，system 的容器里没有那个 Bean，不补上的话租户删掉以后
 * 它的密钥还留在库里。
 *
 * <p>密钥表在各服务共用的主库里，所以这里直接删，语句和 boot 的 {@code AiSecretBizService.removeByTenantCode} 相同，
 * 不去调用 ai 服务：这样和删租户在同一个事务里，ai 服务没启动、没部署时也照样清。
 *
 * <p>{@code AiSecret} 和 {@code AiSecretMapper} 是从 mugsun-boot 的 AI 模块单独拷过来的（scripts/sync-rules.json 的 extras）。
 * boot 的可选模块以后新增别的 {@link TenantCredentialPurger} 时，那个服务里的 {@code TenantPurgerGuardTest} 会失败，
 * 提醒在 system 里补上对应的清理。
 */
@Component
public class CloudAiSecretPurger implements TenantCredentialPurger {

	private final AiSecretMapper mapper;

	public CloudAiSecretPurger(AiSecretMapper mapper) {
		this.mapper = mapper;
	}

	@Override
	public void purgeByTenantCode(String tenantCode) {
		if (tenantCode == null || tenantCode.isBlank()) {
			return;
		}
		mapper.deleteByQuery(QueryWrapper.create().eq("tenant_id", tenantCode));
	}
}
