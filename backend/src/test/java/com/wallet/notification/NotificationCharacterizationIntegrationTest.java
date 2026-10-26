package com.wallet.notification;

import static com.wallet.common.constant.RequestAttributeNames.AUTHENTICATED_MEMBER_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.LocalDate;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.ContextHierarchy;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.wallet.notification.batch.scheduler.NotificationBatchScheduler;
import com.wallet.notification.redis.NotificationUnreadCountCacheRepository;
import com.wallet.notification.redis.RedisKeys;
import com.wallet.notification.repository.NotificationRepository;
import com.wallet.notification.service.NotificationUnreadCountService;
import com.wallet.notification.support.NotificationIntegrationFixture;
import com.wallet.notification.support.NotificationIntegrationFixture.Scenario;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

/**
 * Boot 전환 전에 알림의 실제 계층 간 계약을 고정한다.
 *
 * <p>실제 Spring MVC, 배치, MyBatis, MySQL과 Redis를 사용한다. 배치가 커밋된 뒤 실행하는
 * 캐시 후처리까지 관찰해야 하므로 테스트 트랜잭션 롤백 대신 고유 픽스처와 명시적 정리를 쓴다.</p>
 */
@ExtendWith(SpringExtension.class)
@WebAppConfiguration
@ContextHierarchy({
    @ContextConfiguration(
        name = "root",
        locations = "classpath:/spring/root-context.xml"
    ),
    @ContextConfiguration(
        name = "dispatcher",
        locations = "classpath:/spring/servlet-context.xml"
    )
})
class NotificationCharacterizationIntegrationTest {
    private static final String BENEFIT_LIMIT_JOB = "benefit-limit";

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JedisPool jedisPool;

    @Autowired
    private Clock clock;

    @Autowired
    private NotificationBatchScheduler batchScheduler;

    @Autowired
    private NotificationRepository notificationRepository;

    private MockMvc mockMvc;
    private JdbcTemplate jdbc;
    private NotificationIntegrationFixture fixture;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
        jdbc = new JdbcTemplate(dataSource);
        fixture = new NotificationIntegrationFixture(jdbc, clock);

        try (Jedis jedis = jedisPool.getResource()) {
            assertThat(jedis.ping()).isEqualTo("PONG");
            jedis.del(RedisKeys.lockKey(BENEFIT_LIMIT_JOB, LocalDate.now(clock)));
        }
    }

    @AfterEach
    void tearDown() {
        if (fixture == null) {
            return;
        }

        try (Jedis jedis = jedisPool.getResource()) {
            fixture.memberIds().forEach(memberId -> jedis.del(RedisKeys.unreadCountKey(memberId)));
            jedis.del(RedisKeys.lockKey(BENEFIT_LIMIT_JOB, LocalDate.now(clock)));
        } finally {
            fixture.cleanUp();
        }
    }

    @Test
    @DisplayName("성공 흐름 - 설정을 반영해 개별·다이제스트 알림을 만들고 API 읽음 처리와 캐시 수를 함께 갱신한다")
    void fullFlow_reflectsSettingsAndKeepsDatabaseAndCacheConsistent() throws Exception {
        Scenario individual = fixture.createBenefitLimitScenario(1, 8_000);
        Scenario digest = fixture.createBenefitLimitScenario(4, 10_000);
        Scenario disabled = fixture.createBenefitLimitScenario(1, 10_000);

        updateSettings(individual.memberId(), true);
        updateSettings(digest.memberId(), true);
        updateSettings(disabled.memberId(), false);

        assertUnreadCount(individual.memberId(), 0);
        assertUnreadCount(digest.memberId(), 0);
        batchScheduler.runBenefitLimitJob();

        assertThat(notificationCount(individual.memberId())).isEqualTo(1);
        assertThat(notificationType(individual.memberId())).isEqualTo("BENEFIT_LIMIT");
        assertThat(notificationCount(digest.memberId())).isEqualTo(1);
        assertThat(notificationType(digest.memberId())).isEqualTo("BENEFIT_LIMIT_DIGEST");
        assertThat(notificationCount(disabled.memberId())).isZero();

        long notificationId = notificationId(individual.memberId());
        mockMvc.perform(get("/api/notifications")
                .requestAttr(AUTHENTICATED_MEMBER_ID, individual.memberId()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.totalCount").value(1))
            .andExpect(jsonPath("$.data.notifications[0].notificationId").value(notificationId))
            .andExpect(jsonPath("$.data.notifications[0].notificationType").value("BENEFIT_LIMIT"))
            .andExpect(jsonPath("$.data.notifications[0].read").value(false));

        assertUnreadCount(individual.memberId(), 1);
        assertThat(cachedUnreadCount(individual.memberId())).isEqualTo("1");

        mockMvc.perform(patch("/api/notifications/{notificationId}/read", notificationId)
                .requestAttr(AUTHENTICATED_MEMBER_ID, individual.memberId()))
            .andExpect(status().isNoContent());

        assertThat(jdbc.queryForObject(
            "SELECT read_at IS NOT NULL FROM notification WHERE notification_id = ?",
            Boolean.class,
            notificationId
        )).isTrue();
        assertThat(cachedUnreadCount(individual.memberId())).isIn(null, "0");
        assertUnreadCount(individual.memberId(), 0);
    }

    @Test
    @DisplayName("중복 방지 - 동일 혜택 한도 배치를 재실행해도 같은 알림을 추가하지 않는다")
    void rerun_doesNotCreateDuplicateNotification() throws Exception {
        Scenario scenario = fixture.createBenefitLimitScenario(1, 8_000);
        updateSettings(scenario.memberId(), true);

        batchScheduler.runBenefitLimitJob();
        batchScheduler.runBenefitLimitJob();

        assertThat(notificationCount(scenario.memberId())).isEqualTo(1);
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(DISTINCT deduplication_key) FROM notification WHERE member_id = ?",
            Integer.class,
            scenario.memberId()
        )).isEqualTo(1);
    }

    @Test
    @DisplayName("삭제 - 목록에서는 제외하지만 DB 발송 이력과 중복 방지 키는 유지한다")
    void delete_hidesNotificationButPreservesDeliveryAndDeduplicationHistory() throws Exception {
        Scenario scenario = fixture.createBenefitLimitScenario(1, 10_000);
        updateSettings(scenario.memberId(), true);
        batchScheduler.runBenefitLimitJob();

        long notificationId = notificationId(scenario.memberId());
        String deduplicationKey = jdbc.queryForObject(
            "SELECT deduplication_key FROM notification WHERE notification_id = ?",
            String.class,
            notificationId
        );

        mockMvc.perform(delete("/api/notifications/{notificationId}", notificationId)
                .requestAttr(AUTHENTICATED_MEMBER_ID, scenario.memberId()))
            .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/notifications")
                .requestAttr(AUTHENTICATED_MEMBER_ID, scenario.memberId()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.totalCount").value(0))
            .andExpect(jsonPath("$.data.notifications").isEmpty());

        assertThat(jdbc.queryForObject(
            "SELECT deleted_at IS NOT NULL FROM notification WHERE notification_id = ?",
            Boolean.class,
            notificationId
        )).isTrue();
        assertThat(jdbc.queryForObject(
            "SELECT notification_status FROM notification WHERE notification_id = ?",
            String.class,
            notificationId
        )).isEqualTo("SENT");
        assertThat(jdbc.queryForObject(
            "SELECT deduplication_key FROM notification WHERE notification_id = ?",
            String.class,
            notificationId
        )).isEqualTo(deduplicationKey);

        batchScheduler.runBenefitLimitJob();
        assertThat(notificationCount(scenario.memberId())).isEqualTo(1);
    }

    @Test
    @DisplayName("캐시 대체 - 실제 Redis 캐시 미스와 Redis 연결 장애에서는 MySQL 안 읽은 수를 반환한다")
    void unreadCount_fallsBackToMySqlOnCacheMissAndRedisFailure() throws Exception {
        long memberId = fixture.createStandaloneMember();
        fixture.insertUnreadNotification(memberId, "CACHE_FALLBACK_" + memberId);

        deleteUnreadCache(memberId);
        assertUnreadCount(memberId, 1);
        assertThat(cachedUnreadCount(memberId)).isEqualTo("1");

        try (JedisPool unavailableRedis = new JedisPool(new JedisPoolConfig(), "127.0.0.1", 1)) {
            NotificationUnreadCountCacheRepository unavailableCache =
                new NotificationUnreadCountCacheRepository(
                    unavailableRedis,
                    3600,
                    mock(TaskScheduler.class)
                );
            NotificationUnreadCountService service =
                new NotificationUnreadCountService(unavailableCache, notificationRepository);

            assertThat(service.getUnreadCount(memberId)).isEqualTo(1);
        }
    }

    private void updateSettings(long memberId, boolean benefitLimitEnabled) throws Exception {
        mockMvc.perform(patch("/api/notifications/settings")
                .requestAttr(AUTHENTICATED_MEMBER_ID, memberId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"performanceShortageEnabled\":true,\"benefitLimitEnabled\":"
                    + benefitLimitEnabled + "}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.benefitLimitEnabled").value(benefitLimitEnabled));
    }

    private void assertUnreadCount(long memberId, int expected) throws Exception {
        mockMvc.perform(get("/api/notifications/unread-count")
                .requestAttr(AUTHENTICATED_MEMBER_ID, memberId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.unreadCount").value(expected));
    }

    private int notificationCount(long memberId) {
        return jdbc.queryForObject(
            "SELECT COUNT(*) FROM notification WHERE member_id = ?",
            Integer.class,
            memberId
        );
    }

    private long notificationId(long memberId) {
        return jdbc.queryForObject(
            "SELECT notification_id FROM notification WHERE member_id = ?",
            Long.class,
            memberId
        );
    }

    private String notificationType(long memberId) {
        return jdbc.queryForObject(
            "SELECT notification_type FROM notification WHERE member_id = ?",
            String.class,
            memberId
        );
    }

    private String cachedUnreadCount(long memberId) {
        try (Jedis jedis = jedisPool.getResource()) {
            return jedis.get(RedisKeys.unreadCountKey(memberId));
        }
    }

    private void deleteUnreadCache(long memberId) {
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.del(RedisKeys.unreadCountKey(memberId));
        }
    }
}
