package io.svra.user;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 每個使用者自己的 Google 憑證。
 *
 * <p>🔴 <b>這個類別存在的意義是消滅一個限制。</b>在它之前，refresh token 與
 * calendarId 放在環境變數裡——而環境變數只能有一份，所以
 * {@code CALENDAR_OAUTH_USER_IDS} 實務上只能放一個人：名單放兩個，
 * 第二個人的行程會寫進第一個人的行事曆。
 *
 * <p>那從來不是產品決定，是儲存方式造成的。搬進資料庫之後它自己消失。
 *
 * <p>還沒有的是<b>取得</b>憑證的那一段（讓使用者在 LINE 裡跑完 OAuth）。
 * 那是功能，不是 schema——但這張表就緒之後，那件事只剩一個端點要寫。
 */
@Service
public class Credentials {

    private static final Logger log = LoggerFactory.getLogger(Credentials.class);

    private final GoogleCredentialRepository repository;
    private final SecretCipher cipher;
    private final Clock clock;

    Credentials(GoogleCredentialRepository repository, SecretCipher cipher, Clock clock) {
        this.repository = repository;
        this.cipher = cipher;
        this.clock = clock;
    }

    /**
     * 存下（或更新）一個人的授權。
     *
     * <p>呼叫端必須先確定 {@code users} 有這一列——外鍵擋著。實務上一定成立，
     * 因為授權只可能發生在他跟 bot 互動之後。
     */
    @Transactional
    public void store(String lineUserId, String refreshToken, String calendarId, String scope) {
        repository.upsert(lineUserId, cipher.encrypt(refreshToken), calendarId, scope);
        log.info("已存下使用者的行事曆授權：calendarId={} scope={}", calendarId, scope);
    }

    /** 這個人的授權，已解密。沒授權或已撤銷則是空的。 */
    @Transactional(readOnly = true)
    public Optional<GoogleAuthorization> find(String lineUserId) {
        if (lineUserId == null) {
            return Optional.empty();
        }
        return repository.findByLineUserIdAndRevokedAtIsNull(lineUserId)
                .map(c -> new GoogleAuthorization(
                        cipher.decrypt(c.getRefreshTokenEncrypted()),
                        c.getCalendarId(),
                        c.getScope()));
    }

    /**
     * 這個人能不能讓後端直接寫入行事曆。
     *
     * <p>🔴 <b>刻意不解密。</b>它只回答「有沒有那一列」，而卡片排版每次都要問一次——
     * 為了一個布林值去跑 AES 是白花的，更重要的是<b>沒有必要把 token 解出來的地方，
     * 就不要解出來</b>。
     */
    @Transactional(readOnly = true)
    public boolean hasActive(String lineUserId) {
        return lineUserId != null && repository.existsByLineUserIdAndRevokedAtIsNull(lineUserId);
    }

    /**
     * 標記撤銷。<b>不刪列</b>——「他曾經授權過、後來失效了」跟「他從來沒授權過」
     * 是兩件不同的事，而只有前者需要收到一則「請重新授權」。
     */
    @Transactional
    public void revoke(String lineUserId) {
        if (repository.revoke(lineUserId, Instant.now(clock)) == 1) {
            log.warn("使用者的行事曆授權已標記為撤銷，之後的匯入會走連結");
        }
    }

    /**
     * 目前有有效授權的所有使用者。
     *
     * <p>只給啟動時的健康檢查用（見 {@code CalendarStartupCheck}）——
     * <b>刻意不回傳憑證本身</b>，呼叫端要用再一個一個去拿。
     * 一次把所有人的 token 解密攤在一個 list 裡，是那種寫的時候很方便、
     * 出事時才發現它被 log 出去過的東西。
     */
    @Transactional(readOnly = true)
    public java.util.List<String> activeUserIds() {
        return repository.findAllByRevokedAtIsNull().stream()
                .map(GoogleCredential::getLineUserId)
                .toList();
    }

    /** 這個部署有沒有能力存憑證（＝有沒有設加密金鑰）。 */
    public boolean canStoreCredentials() {
        return cipher.isConfigured();
    }
}
