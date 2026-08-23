package io.svra.user;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface GoogleCredentialRepository extends JpaRepository<GoogleCredential, String> {

    Optional<GoogleCredential> findByLineUserIdAndRevokedAtIsNull(String lineUserId);

    boolean existsByLineUserIdAndRevokedAtIsNull(String lineUserId);

    java.util.List<GoogleCredential> findAllByRevokedAtIsNull();

    /**
     * 寫入或更新憑證。
     *
     * <p>用 upsert 而不是「查了再存」：重新授權是常態（token 被撤銷、換行事曆、
     * scope 改了），而每一次都是「同一個人的憑證換一份」。
     *
     * <p>{@code revoked_at} 在更新時清成 NULL——重新授權當然就是重新生效，
     * 忘了清的話那個人會停在「授權過但已撤銷」，而按鈕看起來是好的。
     */
    @Modifying
    @Query(value = """
            INSERT INTO google_credentials
                   (line_user_id, refresh_token_encrypted, calendar_id, scope)
            VALUES (:lineUserId, :refreshToken, :calendarId, :scope)
            ON CONFLICT (line_user_id) DO UPDATE SET
                   refresh_token_encrypted = EXCLUDED.refresh_token_encrypted,
                   calendar_id             = EXCLUDED.calendar_id,
                   scope                   = EXCLUDED.scope,
                   granted_at              = now(),
                   revoked_at              = NULL
            """, nativeQuery = true)
    int upsert(@Param("lineUserId") String lineUserId,
            @Param("refreshToken") String refreshToken,
            @Param("calendarId") String calendarId,
            @Param("scope") String scope);

    @Modifying
    @Query(value = """
            UPDATE google_credentials SET revoked_at = :now
             WHERE line_user_id = :lineUserId AND revoked_at IS NULL
            """, nativeQuery = true)
    int revoke(@Param("lineUserId") String lineUserId,
            @Param("now") java.time.Instant now);
}
