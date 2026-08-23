package io.svra.user;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import io.svra.IntegrationTest;
import io.svra.note.NoteRepository;
import io.svra.notify.MessageAnchors;
import io.svra.outbox.OutboxEvent;
import io.svra.outbox.OutboxEventRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * users 這張表的兩個宣稱，各驗一次。
 *
 * <p>用真的 PostgreSQL，因為兩個宣稱都是<b>資料庫的行為</b>：外鍵擋不擋得住、
 * CASCADE 掃不掃得乾淨。mock 對這兩件事什麼都證明不了。
 */
@Tag("integration")
@SpringBootTest
@Import(IntegrationTest.class)
@TestPropertySource(properties = {
        "svra.outbox.poll-interval-ms=3600000",
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "svra.secrets.encryption-key=c3ZyYS10ZXN0LWtleS1kby1ub3QtdXNlLWluLXByb2Q=",
        "svra.line.channel-secret=integration-test-secret",
        "svra.line.channel-access-token=integration-test-token",
})
class UserDataIntegrationTest {

    @Autowired
    private Users users;

    @Autowired
    private Credentials credentials;

    @Autowired
    private NoteRepository noteRepository;

    @Autowired
    private MessageAnchors anchors;

    @Autowired
    private OutboxEventRepository outboxRepository;

    @Autowired
    private JdbcTemplate jdbc;

    /** {@code insertPendingIfAbsent} 是 {@code @Modifying}，沒有交易會被 Spring 擋下。 */
    @Autowired
    private PlatformTransactionManager transactionManager;

    // ── 外鍵：使用者列一定要先在 ────────────────────────────────────

    @Test
    @DisplayName("🔴 沒有使用者列就寫不進任何一筆他的資料")
    void writingDataForAnUnknownUserIsRejected() {
        String ghost = "U-ghost-" + UUID.randomUUID();

        // 這正是「follow 事件漏接」的那條路。擋不住的話，資料庫裡會留下
        // 一堆查不到主人的孤兒列——而使用者要求刪除資料時，那些就是掃不到的。
        assertThatThrownBy(() -> inTransaction(
                () -> noteRepository.insertPendingIfAbsent(ghost, "audio-x")))
                .hasMessageContaining("fk_notes_user");
    }

    @Test
    @DisplayName("webhook 入口的 upsert 跑兩次也只有一列，而且不會拋例外")
    void ensureExistsIsIdempotent() {
        String userId = "U-twice-" + UUID.randomUUID();

        users.ensureExists(userId);
        users.ensureExists(userId);

        assertThat(countUsers(userId)).isEqualTo(1);
    }

    // ── CASCADE：刪一個使用者要掃得乾淨 ────────────────────────────

    @Test
    @DisplayName("🔴 刪掉使用者 → 他在每一張表裡的資料都要跟著消失")
    void deletingAUserRemovesEverythingOfTheirs() {
        String userId = "U-erase-" + UUID.randomUUID();
        users.ensureExists(userId);

        inTransaction(() -> noteRepository.insertPendingIfAbsent(userId, "audio-" + userId));
        // card_id 是 varchar(32)——用短的，不然擋下這一筆的會是長度而不是外鍵
        anchors.record("msg-" + userId, "c-" + UUID.randomUUID().toString().substring(0, 8),
                userId, List.of(1L, 2L));
        outboxRepository.save(OutboxEvent.pending(
                "agg-" + userId, "test.event", userId, "{}"));
        credentials.store(userId, "refresh-token", "cal@example.com", "scope");

        assertThat(rowsOwnedBy(userId)).isEqualTo(4);

        // 🔴 這一行 SQL 就是建 users 表最實際的回報。在它之前，
        // 「刪掉這個使用者的所有資料」是「記得掃五張表、而且要照對的順序」。
        jdbc.update("DELETE FROM users WHERE line_user_id = ?", userId);

        assertThat(rowsOwnedBy(userId))
                .as("有殘留 = 某一張表漏了外鍵，而那正是刪除請求最不該發生的事")
                .isZero();
    }

    // ── 憑證 ──────────────────────────────────────────────────────

    @Test
    @DisplayName("存進去的 token 解得回來，但資料庫裡看不到明文")
    void credentialsRoundTripWithoutStoringPlaintext() {
        String userId = "U-cred-" + UUID.randomUUID();
        users.ensureExists(userId);
        String token = "1//0-refresh-" + UUID.randomUUID();

        credentials.store(userId, token, "cal@example.com", "scope");

        assertThat(credentials.find(userId)).get()
                .extracting(GoogleAuthorization::refreshToken, GoogleAuthorization::calendarId)
                .containsExactly(token, "cal@example.com");

        String stored = jdbc.queryForObject(
                "SELECT refresh_token_encrypted FROM google_credentials WHERE line_user_id = ?",
                String.class, userId);
        assertThat(stored)
                .as("開發者跑 psql 時看到的就是這一欄——明文的話它會留在終端機捲軸裡")
                .doesNotContain(token);
    }

    @Test
    @DisplayName("🔴 兩個人各自的行事曆，不會互相拿到")
    void eachUserGetsTheirOwnCalendar() {
        String alice = "U-alice-" + UUID.randomUUID();
        String bob = "U-bob-" + UUID.randomUUID();
        users.ensureExists(alice);
        users.ensureExists(bob);

        credentials.store(alice, "alice-token", "alice@group.calendar.google.com", "scope");
        credentials.store(bob, "bob-token", "bob@group.calendar.google.com", "scope");

        // 憑證在環境變數裡的時候，這個測試寫不出來——因為只有一組值。
        // 「第二個人的行程會寫進第一個人的行事曆」就是那個形狀的必然結果。
        assertThat(credentials.find(alice)).get()
                .extracting(GoogleAuthorization::calendarId)
                .isEqualTo("alice@group.calendar.google.com");
        assertThat(credentials.find(bob)).get()
                .extracting(GoogleAuthorization::calendarId)
                .isEqualTo("bob@group.calendar.google.com");
    }

    @Test
    @DisplayName("撤銷後查不到，但那一列還在——「授權過又失效」跟「從沒授權」是兩件事")
    void revokingHidesTheCredentialWithoutDeletingIt() {
        String userId = "U-revoke-" + UUID.randomUUID();
        users.ensureExists(userId);
        credentials.store(userId, "token", "cal@example.com", "scope");

        credentials.revoke(userId);

        assertThat(credentials.hasActive(userId)).isFalse();
        assertThat(credentials.find(userId)).isEmpty();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM google_credentials WHERE line_user_id = ?",
                Integer.class, userId)).isEqualTo(1);
    }

    @Test
    @DisplayName("重新授權要把撤銷標記清掉，否則按鈕看起來好的但不會動")
    void reAuthorizingClearsTheRevocation() {
        String userId = "U-reauth-" + UUID.randomUUID();
        users.ensureExists(userId);
        credentials.store(userId, "old", "cal@example.com", "scope");
        credentials.revoke(userId);

        credentials.store(userId, "new", "cal@example.com", "scope");

        assertThat(credentials.hasActive(userId)).isTrue();
        assertThat(credentials.find(userId)).get()
                .extracting(GoogleAuthorization::refreshToken).isEqualTo("new");
    }

    // ── 封鎖 ──────────────────────────────────────────────────────

    @Test
    @DisplayName("封鎖再解除，狀態要跟著走——不然回來的人是個收不到訊息的幽靈")
    void blockingAndUnblocking() {
        String userId = "U-block-" + UUID.randomUUID();
        users.ensureExists(userId);

        assertThat(users.isBlocked(userId)).isFalse();
        users.block(userId);
        assertThat(users.isBlocked(userId)).isTrue();
        users.unblock(userId);
        assertThat(users.isBlocked(userId)).isFalse();
    }

    @Test
    @DisplayName("從沒見過的人送 unfollow 進來，也要記得住")
    void blockingCreatesTheUserIfNeeded() {
        String userId = "U-unseen-" + UUID.randomUUID();

        users.block(userId);

        assertThat(users.isBlocked(userId)).isTrue();
    }

    // ── 工具 ──────────────────────────────────────────────────────

    private <T> T inTransaction(java.util.function.Supplier<T> work) {
        return new TransactionTemplate(transactionManager).execute(status -> work.get());
    }

    private void inTransaction(Runnable work) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> work.run());
    }

    private int countUsers(String userId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM users WHERE line_user_id = ?", Integer.class, userId);
    }

    private int rowsOwnedBy(String userId) {
        return jdbc.queryForObject("""
                SELECT (SELECT count(*) FROM notes              WHERE line_user_id = ?)
                     + (SELECT count(*) FROM message_anchors    WHERE line_user_id = ?)
                     + (SELECT count(*) FROM outbox_events      WHERE line_user_id = ?)
                     + (SELECT count(*) FROM google_credentials WHERE line_user_id = ?)
                """, Integer.class, userId, userId, userId, userId);
    }
}
