package io.svra.user;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
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

    /**
     * 存著的那一列，跟這組設定<b>一模一樣</b>嗎？<b>看得到已撤銷的列。</b>
     *
     * <p>🔴 這是給 {@code CalendarCredentialsBootstrap} 判斷「.env 這組要不要種」用的，
     * 而它<b>不能</b>用 {@link #find} 來問：find 查的是還有效的列，撤銷之後它回空的，
     * 於是「同一顆已經被 Google 拒掉的 token」會被讀成「這個人還沒有憑證」——
     * 每次啟動原封不動種回去、順手把 {@code revoked_at} 清掉。
     * 症狀是撤銷永遠撐不過一次重啟，而 log 還會說「已用 .env 的設定更新憑證」。
     *
     * <p>回傳布林而不是憑證本身，理由跟 {@link #hasActive} 一樣：
     * 呼叫端要的是一個判斷，不是那份祕密。解密留在這個類別裡面。
     */
    @Transactional(readOnly = true)
    public boolean storedMatches(String lineUserId, String refreshToken, String calendarId,
            String scope) {
        return repository.findById(lineUserId)
                .filter(c -> c.getCalendarId().equals(calendarId))
                .filter(c -> c.getScope().equals(scope))
                .filter(c -> cipher.decrypt(c.getRefreshTokenEncrypted()).equals(refreshToken))
                .isPresent();
    }

    /** 這個人有沒有憑證那一列——<b>不管有沒有被撤銷</b>。只用來決定 log 要說「建立」還是「更新」。 */
    @Transactional(readOnly = true)
    public boolean hasCredentialRow(String lineUserId) {
        return lineUserId != null && repository.existsById(lineUserId);
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
     *
     * <p>🔴 <b>{@code REQUIRES_NEW} 不是裝飾。</b>唯一的正式呼叫端是
     * {@code GoogleTokenProvider} 認出 {@code invalid_grant} 的那條路，而它
     * <b>正要把例外往外拋</b>。撤銷跟著呼叫端的交易走的話，呼叫端一回滾，
     * 這個事實就跟著消失——症狀是「log 說授權壞了，資料庫說一切正常」，
     * 而那種不一致查起來特別久。
     *
     * <p>今天的呼叫路徑其實不會回滾（outbox 處理器跑在交易被掛起的狀態下），
     * 所以這是一道保險而不是修補。但撤銷本來就該獨立於「誰發現的」而成立。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
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
    /**
     * 授權過、但已經失效的所有使用者。
     *
     * <p>跟 {@link #activeUserIds()} 一樣刻意不回傳憑證本身。
     */
    @Transactional(readOnly = true)
    public java.util.List<String> revokedUserIds() {
        return repository.findAllByRevokedAtIsNotNull().stream()
                .map(GoogleCredential::getLineUserId)
                .toList();
    }

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
