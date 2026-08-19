package io.svra;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 跑真的 PostgreSQL 的整合測試共用設定。
 *
 * <p>為什麼需要它：這個專案最關鍵的三個宣稱——唯一約束擋得住 race、
 * {@code SKIP LOCKED} 讓多實例不重疊、處理器跑在獨立交易——<b>沒有一個</b>
 * 是 mock 驗得了的。用 Mockito 讓 repository 拋 {@code DataIntegrityViolationException}
 * 證明的是「呼叫端會處理這個例外」，不是「資料庫真的會拋」，
 * 更不是「拋完之後這個交易還能正常提交」。後面那件事才是會出人命的地方。
 *
 * <p>容器由 Spring Boot 管理生命週期，測試類別之間共用同一個 context，
 * 所以 PostgreSQL 只會起一次。
 */
@TestConfiguration(proxyBeanMethods = false)
public class IntegrationTest {

    /**
     * 用 pgvector 映像檔而不是官方 postgres：正式環境用的就是它（見 docker-compose），
     * 而測試環境跟正式環境的差異，遲早會變成「在我機器上是好的」。
     */
    @Bean
    @ServiceConnection
    PostgreSQLContainer postgres() {
        return new PostgreSQLContainer(
                DockerImageName.parse("pgvector/pgvector:pg17")
                        .asCompatibleSubstituteFor("postgres"));
    }
}
