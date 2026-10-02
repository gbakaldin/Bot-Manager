package com.vingame.bot.domain.botgroup.service;

import com.vingame.bot.common.exception.BadRequestException;
import com.vingame.bot.common.exception.ResourceNotFoundException;
import com.vingame.bot.domain.botgroup.dto.BotGroupDTO;
import com.vingame.bot.domain.botgroup.mapper.BotGroupMapper;
import com.vingame.bot.domain.botgroup.model.BotGroup;
import com.vingame.bot.domain.botgroup.model.BotGroupFilter;
import com.vingame.bot.domain.botgroup.model.BotGroupStatus;
import com.vingame.bot.domain.botgroup.model.RegistrationState;
import com.vingame.bot.domain.botgroup.repository.BotGroupRepository;
import com.vingame.bot.domain.botgroup.validation.BotGroupConfigValidationService;
import com.vingame.bot.domain.brand.model.ProductCode;
import com.vingame.bot.domain.environment.model.Environment;
import com.vingame.bot.domain.environment.service.EnvironmentService;
import com.vingame.bot.domain.game.model.Game;
import com.vingame.bot.domain.game.service.GameService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("BotGroupService")
class BotGroupServiceTest {

    @Mock
    private BotGroupRepository repository;

    @Mock
    private BotGroupMapper mapper;

    @Mock
    private EnvironmentService environmentService;

    @Mock
    private GameService gameService;

    @Mock
    private MongoTemplate mongoTemplate;

    @Mock
    private BotGroupConfigValidationService configValidation;

    @Mock
    private BotGroupBehaviorService behaviorService;

    @Mock
    private RegistrationWorker registrationWorker;

    @org.mockito.Spy
    private DepositLedger depositLedger = new InMemoryDepositLedger();

    @Captor
    private ArgumentCaptor<Query> queryCaptor;

    @InjectMocks
    private BotGroupService service;

    /**
     * Make {@code mongoTemplate.updateFirst} behave like Mongo for the retry's conditional
     * {@code FAILED -> PENDING} flip on {@code group}: it matches only while the group is FAILED,
     * and applies the {@code $set} fields it carries.
     */
    private void stubConditionalFlip(BotGroup group) {
        org.mockito.Mockito.lenient().when(mongoTemplate.updateFirst(any(Query.class),
                any(org.springframework.data.mongodb.core.query.Update.class), eq(BotGroup.class)))
                .thenAnswer(inv -> {
                    if (!RegistrationState.isFailed(group.getRegistrationState())) {
                        return com.mongodb.client.result.UpdateResult.acknowledged(0, 0L, null);
                    }
                    org.bson.Document set = (org.bson.Document) inv
                            .<org.springframework.data.mongodb.core.query.Update>getArgument(1)
                            .getUpdateObject().get("$set");
                    group.setRegistrationState((String) set.get("registrationState"));
                    group.setRegistrationError((String) set.get("registrationError"));
                    if (set.get("depositedCount") instanceof Integer d) {
                        group.setDepositedCount(d);
                    }
                    if (set.containsKey("depositInFlight")) {
                        group.setDepositInFlight((Integer) set.get("depositInFlight"));
                    }
                    return com.mongodb.client.result.UpdateResult.acknowledged(1, 1L, null);
                });
    }

    @Nested
    @DisplayName("findById")
    class FindByIdTests {

        @Test
        @DisplayName("Should return bot group when found")
        void shouldReturnBotGroupWhenFound() {
            BotGroup group = BotGroup.builder().id("123").name("Test").build();
            when(repository.findById("123")).thenReturn(Optional.of(group));

            BotGroup result = service.findById("123");

            assertThat(result).isEqualTo(group);
        }

        @Test
        @DisplayName("Should throw ResourceNotFoundException when not found")
        void shouldThrowWhenNotFound() {
            when(repository.findById("missing")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.findById("missing"))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    @Nested
    @DisplayName("findAll")
    class FindAllTests {

        @Test
        @DisplayName("Should return all bot groups")
        void shouldReturnAll() {
            List<BotGroup> groups = List.of(
                    BotGroup.builder().id("1").build(),
                    BotGroup.builder().id("2").build()
            );
            when(repository.findAll()).thenReturn(groups);

            assertThat(service.findAll()).hasSize(2);
        }
    }

    @Nested
    @DisplayName("findByTargetStatus")
    class FindByTargetStatusTests {

        @Test
        @DisplayName("Should delegate to repository")
        void shouldDelegateToRepository() {
            List<BotGroup> active = List.of(BotGroup.builder().id("1").targetStatus(BotGroupStatus.ACTIVE).build());
            when(repository.findByTargetStatus(BotGroupStatus.ACTIVE)).thenReturn(active);

            List<BotGroup> result = service.findByTargetStatus(BotGroupStatus.ACTIVE);

            assertThat(result).hasSize(1);
            verify(repository).findByTargetStatus(BotGroupStatus.ACTIVE);
        }
    }

    @Nested
    @DisplayName("filter")
    class FilterTests {

        @Test
        @DisplayName("Should always scope by the environment id from the path arg")
        void shouldScopeByEnvironmentId() {
            List<BotGroup> expected = List.of(BotGroup.builder().id("1").environmentId("env-a").build());
            when(mongoTemplate.find(any(Query.class), eq(BotGroup.class))).thenReturn(expected);

            List<BotGroup> result = service.filter("env-a", new BotGroupFilter());

            assertThat(result).hasSize(1);
            verify(mongoTemplate).find(queryCaptor.capture(), eq(BotGroup.class));
            Query capturedQuery = queryCaptor.getValue();
            String queryString = capturedQuery.toString();
            assertThat(queryString).contains("environmentId");
            assertThat(queryString).contains("env-a");
            // Strengthened: assert exact value
            assertThat(capturedQuery.getQueryObject().get("environmentId")).isEqualTo("env-a");
        }

        @Test
        @DisplayName("Should filter by name (case-insensitive) within the env scope")
        void shouldFilterByName() {
            List<BotGroup> expected = List.of(BotGroup.builder().id("2").name("Test Group").build());
            when(mongoTemplate.find(any(Query.class), eq(BotGroup.class))).thenReturn(expected);

            BotGroupFilter filter = new BotGroupFilter();
            filter.setName("test group");

            List<BotGroup> result = service.filter("env-a", filter);

            assertThat(result).hasSize(1);
            verify(mongoTemplate).find(queryCaptor.capture(), eq(BotGroup.class));
            Query capturedQuery = queryCaptor.getValue();
            String queryString = capturedQuery.toString();
            assertThat(queryString).contains("name");
            // Env scope is always present
            assertThat(capturedQuery.getQueryObject().get("environmentId")).isEqualTo("env-a");
            // Strengthened: assert the value is a case-insensitive contains (unanchored) Pattern
            Object nameCriterion = capturedQuery.getQueryObject().get("name");
            assertThat(nameCriterion).isInstanceOf(Pattern.class);
            Pattern namePattern = (Pattern) nameCriterion;
            assertThat(namePattern.flags() & Pattern.CASE_INSENSITIVE).isEqualTo(Pattern.CASE_INSENSITIVE);
            assertThat(namePattern.pattern()).isEqualTo(Pattern.quote("test group"));
        }

        @Test
        @DisplayName("Should build a contains regex that matches a partial substring (unanchored) — item 5")
        void shouldBuildContainsRegexMatchingPartialSubstring() {
            when(mongoTemplate.find(any(Query.class), eq(BotGroup.class)))
                    .thenReturn(List.of(BotGroup.builder().id("2").name("Nightly Test Group A").build()));

            BotGroupFilter filter = new BotGroupFilter();
            filter.setName("test group");

            service.filter("env-a", filter);

            verify(mongoTemplate).find(queryCaptor.capture(), eq(BotGroup.class));
            Pattern namePattern = (Pattern) queryCaptor.getValue().getQueryObject().get("name");

            // Applied client-side, the regex must match a name where the filter term
            // is only a substring, case-insensitively — the behavior the old anchored
            // "^...$" form broke. The env scope stays an exact-match string alongside.
            assertThat(namePattern.matcher("Nightly Test Group A").find())
                    .as("contains match on a partial, differently-cased substring")
                    .isTrue();
            assertThat(namePattern.matcher("Prod Group").find())
                    .as("non-matching name must not match")
                    .isFalse();
        }

        @Test
        @DisplayName("Should filter by game ID within the env scope")
        void shouldFilterByGameId() {
            List<BotGroup> expected = List.of(BotGroup.builder().id("1").gameId("game-1").build());
            when(mongoTemplate.find(any(Query.class), eq(BotGroup.class))).thenReturn(expected);

            BotGroupFilter filter = new BotGroupFilter();
            filter.setGameId("game-1");

            List<BotGroup> result = service.filter("env-a", filter);

            assertThat(result).hasSize(1);
            verify(mongoTemplate).find(queryCaptor.capture(), eq(BotGroup.class));
            Query capturedQuery = queryCaptor.getValue();
            String queryString = capturedQuery.toString();
            assertThat(queryString).contains("gameId");
            assertThat(queryString).contains("game-1");
            // Strengthened: assert exact value
            assertThat(capturedQuery.getQueryObject().get("gameId")).isEqualTo("game-1");
            assertThat(capturedQuery.getQueryObject().get("environmentId")).isEqualTo("env-a");
        }

        @Test
        @DisplayName("Empty filter body returns everything in the env (env scope only)")
        void shouldReturnAllInEnvWhenBodyEmpty() {
            List<BotGroup> all = List.of(
                    BotGroup.builder().id("1").environmentId("env-a").build(),
                    BotGroup.builder().id("2").environmentId("env-a").build()
            );
            when(mongoTemplate.find(any(Query.class), eq(BotGroup.class))).thenReturn(all);

            assertThat(service.filter("env-a", new BotGroupFilter())).hasSize(2);
            verify(mongoTemplate).find(queryCaptor.capture(), eq(BotGroup.class));
            // Only the env scope should be present — no name/gameId criteria
            assertThat(queryCaptor.getValue().getQueryObject().keySet()).containsExactly("environmentId");
            assertThat(queryCaptor.getValue().getQueryObject().get("environmentId")).isEqualTo("env-a");
        }
    }

    @Nested
    @DisplayName("save - new group (asynchronous registration, GATEWAY_REQUEST_BUDGET A2)")
    class SaveNewGroupTests {

        @Test
        @DisplayName("persists REGISTRATION_PENDING at 0/botCount and makes no upstream call")
        void shouldPersistPendingAndRegisterNothingSynchronously() {
            BotGroup group = BotGroup.builder()
                    .name("New Group")
                    .environmentId("env-1")
                    .namePrefix("bot")
                    .password("pass")
                    .botCount(5)
                    .build();

            when(environmentService.findById("env-1")).thenReturn(envWithoutCap());
            when(repository.save(any(BotGroup.class))).thenAnswer(inv -> inv.getArgument(0));

            BotGroup result = service.save(group);

            assertThat(result.getId()).isNotNull().isNotEmpty();
            assertThat(result.getRegistrationState()).isEqualTo(RegistrationState.PENDING);
            assertThat(result.getRegisteredCount()).isZero();
            assertThat(result.getNamedCount()).isZero();
            verify(repository).save(group);
            verify(registrationWorker).enqueue(result.getId());
        }

        @Test
        @DisplayName("the create is not where accounts are created — nothing can 502 here any more")
        void createNeverReportsAnUpstreamFailure() {
            // The predecessor of this test asserted an UpstreamRegistrationException (502) when
            // every account failed. That outcome no longer exists at create time and its absence
            // is the point (A25.1): save() makes no gateway call, so it cannot report one as
            // failed, and our own pacing can no longer surface as "Game server error" about a
            // gateway that was never asked. A refusal now belongs to RegistrationWorker, which
            // charges it against max-attempts-per-user and reports it on the document.
            BotGroup group = BotGroup.builder()
                    .name("Failing Group")
                    .environmentId("env-1")
                    .namePrefix("bot")
                    .password("pass")
                    .botCount(500)
                    .build();

            when(environmentService.findById("env-1")).thenReturn(envWithoutCap());
            when(repository.save(any(BotGroup.class))).thenAnswer(inv -> inv.getArgument(0));

            BotGroup result = service.save(group);

            assertThat(result.getRegistrationState()).isEqualTo(RegistrationState.PENDING);
            assertThat(result.getRegistrationError())
                    .as("a fresh create carries no failure — the worker is what records one")
                    .isNull();
        }

        @Test
        @DisplayName("enqueues only after the persist, so the worker cannot look for a missing doc")
        void enqueuesAfterThePersist() {
            BotGroup group = BotGroup.builder()
                    .name("Ordered Group")
                    .environmentId("env-1")
                    .namePrefix("bot")
                    .password("pass")
                    .botCount(3)
                    .build();

            when(environmentService.findById("env-1")).thenReturn(envWithoutCap());
            when(repository.save(any(BotGroup.class))).thenAnswer(inv -> inv.getArgument(0));

            service.save(group);

            // The worker selects by the PERSISTED state, so an enqueue that overtook the save
            // would find nothing and the group would wait a whole tick for no reason.
            org.mockito.InOrder order = org.mockito.Mockito.inOrder(repository, registrationWorker);
            order.verify(repository).save(any(BotGroup.class));
            order.verify(registrationWorker).enqueue(anyString());
        }
    }

    @Nested
    @DisplayName("save - existing group")
    class SaveExistingGroupTests {

        @Test
        @DisplayName("Should skip user registration for existing group")
        void shouldSkipRegistrationForExistingGroup() {
            BotGroup group = BotGroup.builder()
                    .id("existing-id")
                    .name("Existing Group")
                    .environmentId("env-1")
                    .build();

            when(repository.save(group)).thenReturn(group);

            BotGroup result = service.save(group);

            assertThat(result.getId()).isEqualTo("existing-id");
            verify(repository).save(group);
        }
    }

    @Nested
    @DisplayName("save - skipRegistration=true overload")
    class SaveSkipRegistrationTests {

        @Test
        @DisplayName("Should generate ID and skip user registration for new group when skipRegistration=true")
        void shouldGenerateIdAndSkipRegistration() {
            BotGroup group = BotGroup.builder()
                    .name("Migrated Group")
                    .environmentId("env-1")
                    .namePrefix("bot")
                    .password("pass")
                    .botCount(5)
                    .build();

            when(repository.save(any(BotGroup.class))).thenAnswer(inv -> inv.getArgument(0));

            BotGroup result = service.save(group, true);

            // An id was generated even though we skipped registration
            assertThat(result.getId()).isNotNull().isNotEmpty();

            // No environment client was looked up, no users registered
            verify(repository).save(group);
        }

        @Test
        @DisplayName("Should still register users for new group when skipRegistration=false (overload)")
        void shouldRegisterWhenSkipFalse() {
            BotGroup group = BotGroup.builder()
                    .name("Plain Group")
                    .environmentId("env-1")
                    .namePrefix("bot")
                    .password("pass")
                    .botCount(3)
                    .build();

            when(environmentService.findById("env-1")).thenReturn(envWithoutCap());
            when(repository.save(any(BotGroup.class))).thenAnswer(inv -> inv.getArgument(0));

            BotGroup result = service.save(group, false);

            assertThat(result.getId()).isNotNull().isNotEmpty();
            verify(repository).save(group);
        }

        @Test
        @DisplayName("Should not register users when skipRegistration=true even on existing group (id preserved)")
        void shouldNotRegisterOnExistingGroupWithSkipTrue() {
            BotGroup group = BotGroup.builder()
                    .id("existing-id")
                    .name("Existing Group")
                    .environmentId("env-1")
                    .build();

            when(repository.save(group)).thenReturn(group);

            BotGroup result = service.save(group, true);

            assertThat(result.getId()).isEqualTo("existing-id");
            verify(repository).save(group);
        }

        @Test
        @DisplayName("Two-arg save(group) delegates to save(group, false) — registers users for a new group")
        void twoArgSaveDelegatesToSkipFalse() {
            BotGroup group = BotGroup.builder()
                    .name("Two-Arg Group")
                    .environmentId("env-1")
                    .namePrefix("bot")
                    .password("pass")
                    .botCount(2)
                    .build();

            when(environmentService.findById("env-1")).thenReturn(envWithoutCap());
            when(repository.save(any(BotGroup.class))).thenAnswer(inv -> inv.getArgument(0));

            service.save(group);

            verify(repository).save(group);
        }
    }

    @Nested
    @DisplayName("registration lifecycle (GATEWAY_REQUEST_BUDGET A2)")
    class RegistrationLifecycleTests {

        @Test
        @DisplayName("existingGroup=true leaves no registration state and enqueues nothing")
        void migrationPathLeavesNoRegistrationState() {
            BotGroup group = BotGroup.builder()
                    .name("Migrated").environmentId("env-1").namePrefix("bot")
                    .password("pass").botCount(50)
                    .build();

            when(repository.save(any(BotGroup.class))).thenAnswer(inv -> inv.getArgument(0));

            BotGroup result = service.save(group, true);

            // A2.8. The accounts already exist, so there is nothing to pace and nothing to
            // resume — and "no registration state" is exactly the state a group created before
            // this feature has, which is what keeps the two indistinguishable everywhere else.
            assertThat(result.getRegistrationState()).isNull();
            assertThat(result.getRegisteredCount()).isZero();
            verify(registrationWorker, never()).enqueue(anyString());
        }

        @Test
        @DisplayName("PATCH botCount up extends the target and re-enqueues")
        void raisingBotCountReEnqueues() {
            BotGroup existing = BotGroup.builder()
                    .id("g-1").name("G").environmentId("env-1").namePrefix("bot")
                    .botCount(100).registeredCount(100).namedCount(100)
                    .build();
            when(repository.findById("g-1")).thenReturn(Optional.of(existing));
            when(repository.save(any(BotGroup.class))).thenAnswer(inv -> inv.getArgument(0));
            // A raise runs the username pre-flight (BOT_PROVISIONING AD-4); BOM has no cap.
            when(environmentService.findById("env-1")).thenReturn(envWithProductCode(ProductCode.P_097));
            // The mapper is a mock here, so apply the merge the real one would.
            org.mockito.Mockito.doAnswer(inv -> {
                inv.<BotGroup>getArgument(1).setBotCount(300);
                return null;
            }).when(mapper).updateEntityFromDTO(any(BotGroupDTO.class), any(BotGroup.class));

            BotGroup result = service.update("g-1", BotGroupDTO.builder().botCount(300).build());

            // A2.7 — this is what turns "register 200 more bots for this group" from a script
            // into a product feature. The worker resumes at 101; the hundred that exist are never
            // touched.
            assertThat(result.getRegistrationState()).isEqualTo(RegistrationState.PENDING);
            assertThat(result.getRegisteredCount()).isEqualTo(100);
            verify(registrationWorker).enqueue("g-1");
        }

        @Test
        @DisplayName("PATCH botCount down never un-registers and does not re-enqueue")
        void loweringBotCountNeverUnregisters() {
            BotGroup existing = BotGroup.builder()
                    .id("g-1").name("G").environmentId("env-1").namePrefix("bot")
                    .botCount(500).registeredCount(120).namedCount(120)
                    .registrationState(RegistrationState.FAILED)
                    .registrationError("stopped at 121")
                    .build();
            when(repository.findById("g-1")).thenReturn(Optional.of(existing));
            when(repository.save(any(BotGroup.class))).thenAnswer(inv -> inv.getArgument(0));
            org.mockito.Mockito.doAnswer(inv -> {
                inv.<BotGroup>getArgument(1).setBotCount(120);
                return null;
            }).when(mapper).updateEntityFromDTO(any(BotGroupDTO.class), any(BotGroup.class));

            BotGroup result = service.update("g-1", BotGroupDTO.builder().botCount(120).build());

            // The clean exit from a half-failed 500-account group: registeredCount is a fact
            // about accounts that exist, not an intent, so lowering the target cannot destroy
            // anything. The group is now startable with what it has.
            assertThat(result.getRegisteredCount()).isEqualTo(120);
            verify(registrationWorker, never()).enqueue(anyString());

            // AND THE STATE, which is the only thing that makes the sentence above true (review
            // B4). This test asserted the count and the absence of an enqueue while claiming
            // startability, and the claim was false: FAILED was cleared by retryRegistration and
            // nowhere else, so /start answered the same 400 repeating the same advice — a loop the
            // operator could not get out of. Lowering the target to a met one discharges the state
            // here, because the state is a statement about an UNMET target.
            assertThat(result.getRegistrationState())
                    .as("a met target is not a failed registration")
                    .isNull();
            assertThat(result.getRegistrationError()).isNull();
        }

        @Test
        @DisplayName("lowering botCount short of what registered leaves FAILED alone")
        void loweringBotCountShortOfRegisteredKeepsFailed() {
            BotGroup existing = BotGroup.builder()
                    .id("g-1").name("G").environmentId("env-1").namePrefix("bot")
                    .botCount(500).registeredCount(120).namedCount(120)
                    .registrationState(RegistrationState.FAILED)
                    .registrationError("stopped at 121")
                    .build();
            when(repository.findById("g-1")).thenReturn(Optional.of(existing));
            when(repository.save(any(BotGroup.class))).thenAnswer(inv -> inv.getArgument(0));
            org.mockito.Mockito.doAnswer(inv -> {
                inv.<BotGroup>getArgument(1).setBotCount(300);
                return null;
            }).when(mapper).updateEntityFromDTO(any(BotGroupDTO.class), any(BotGroup.class));

            BotGroup result = service.update("g-1", BotGroupDTO.builder().botCount(300).build());

            // 300 > 120: the target is still unmet, so the group still needs the operator's
            // decision. Clearing the state here would hide a half-registered group behind a 200.
            assertThat(result.getRegistrationState()).isEqualTo(RegistrationState.FAILED);
            assertThat(result.getRegistrationError()).isEqualTo("stopped at 121");
            verify(registrationWorker, never()).enqueue(anyString());
        }

        @Test
        @DisplayName("B1: an unrelated PATCH of a LEGACY group does not re-register or rename anything")
        void anUnrelatedPatchOfALegacyGroupDoesNotTouchItsAccounts() {
            // Every group in production before Phase 4: Mongo has no registeredCount field, so
            // Spring Data maps the absent field to int 0, and no registrationState either.
            BotGroup legacy = BotGroup.builder()
                    .id("g-1").name("Prod 250").environmentId("env-1").namePrefix("bot")
                    .botCount(250)
                    .build();
            when(repository.findById("g-1")).thenReturn(Optional.of(legacy));
            when(repository.save(any(BotGroup.class))).thenAnswer(inv -> inv.getArgument(0));
            // A routine PATCH that has nothing to do with accounts.
            org.mockito.Mockito.doAnswer(inv -> {
                inv.<BotGroup>getArgument(1).setMaxBet(50_000L);
                return null;
            }).when(mapper).updateEntityFromDTO(any(BotGroupDTO.class), any(BotGroup.class));

            BotGroup result = service.update("g-1", BotGroupDTO.builder().maxBet(50_000L).build());

            // The predecessor tested `botCount > registeredCount`, which is UNCONDITIONALLY TRUE
            // for such a group, so this PATCH used to: make a live 250-bot group unstartable,
            // hand the worker 250 indices, spend ~250 EXISTED requests — and, because namedCount
            // is absent for the same reason, RENAME all 250 live accounts from the display-name
            // pool. That is half the Cloudflare five-minute allowance on a maxBet edit, plus an
            // unrequested mutation of production accounts (MEMORY's RIK hand-naming recipe is
            // exactly the work it undoes).
            assertThat(result.getRegistrationState()).isNull();
            assertThat(result.getRegisteredCount()).isZero();
            assertThat(result.getNamedCount()).isZero();
            verify(registrationWorker, never()).enqueue(anyString());
        }

        @Test
        @DisplayName("B1: an unrelated PATCH of an existingGroup=true group is equally untouched")
        void anUnrelatedPatchOfAMigratedGroupDoesNotTouchItsAccounts() {
            BotGroup migrated = BotGroup.builder()
                    .id("g-1").name("Migrated").environmentId("env-1").namePrefix("bot")
                    .botCount(50)
                    .build();
            when(repository.findById("g-1")).thenReturn(Optional.of(migrated));
            when(repository.save(any(BotGroup.class))).thenAnswer(inv -> inv.getArgument(0));
            org.mockito.Mockito.doAnswer(inv -> {
                inv.<BotGroup>getArgument(1).setName("Renamed");
                return null;
            }).when(mapper).updateEntityFromDTO(any(BotGroupDTO.class), any(BotGroup.class));

            BotGroup result = service.update("g-1", BotGroupDTO.builder().name("Renamed").build());

            assertThat(result.getRegistrationState()).isNull();
            verify(registrationWorker, never()).enqueue(anyString());
        }

        @Test
        @DisplayName("B1: raising botCount on an untracked group registers ONLY the new indices")
        void raisingBotCountOnAnUntrackedGroupSeedsTheHighWaterMark() {
            BotGroup migrated = BotGroup.builder()
                    .id("g-1").name("Migrated").environmentId("env-1").namePrefix("bot")
                    .botCount(50)
                    .build();
            when(repository.findById("g-1")).thenReturn(Optional.of(migrated));
            when(repository.save(any(BotGroup.class))).thenAnswer(inv -> inv.getArgument(0));
            // A raise runs the username pre-flight (BOT_PROVISIONING AD-4); BOM has no cap.
            when(environmentService.findById("env-1")).thenReturn(envWithProductCode(ProductCode.P_097));
            org.mockito.Mockito.doAnswer(inv -> {
                inv.<BotGroup>getArgument(1).setBotCount(60);
                return null;
            }).when(mapper).updateEntityFromDTO(any(BotGroupDTO.class), any(BotGroup.class));

            BotGroup result = service.update("g-1", BotGroupDTO.builder().botCount(60).build());

            // A2.7 still works on a migrated or legacy group — that is the whole point of
            // including it — but the counters are SEEDED at the old target rather than left at
            // zero, so the worker starts at index 51. Leaving them at zero is what made this
            // feature re-register and rename the fifty accounts that already existed.
            assertThat(result.getRegistrationState()).isEqualTo(RegistrationState.PENDING);
            assertThat(result.getRegisteredCount()).isEqualTo(50);
            assertThat(result.getNamedCount())
                    .as("namedCount is seeded too, or the naming half walks every existing index "
                            + "and overwrites live display names")
                    .isEqualTo(50);
            verify(registrationWorker).enqueue("g-1");
        }

        @Test
        @DisplayName("retry clears FAILED back to PENDING and resumes rather than starting over")
        void retryResumesFromTheHighWaterMark() {
            BotGroup failed = BotGroup.builder()
                    .id("g-1").name("G").environmentId("env-1").namePrefix("bot")
                    .botCount(500).registeredCount(63)
                    .registrationState(RegistrationState.FAILED)
                    .registrationError("Registration stopped at account 64 of 500")
                    .build();
            when(repository.findById("g-1")).thenReturn(Optional.of(failed));
            stubConditionalFlip(failed);

            BotGroup result = service.retryRegistration("g-1");

            assertThat(result.getRegistrationState()).isEqualTo(RegistrationState.PENDING);
            assertThat(result.getRegistrationError()).isNull();
            assertThat(result.getRegisteredCount())
                    .as("resuming is the whole point — re-creating 63 accounts that exist would "
                            + "spend the Cloudflare window twice over for nothing")
                    .isEqualTo(63);
            verify(registrationWorker).enqueue("g-1");
        }

        @Test
        @DisplayName("retrying a group that is not FAILED is a 400 naming the state")
        void retryOfAHealthyGroupIsRejected() {
            BotGroup pending = BotGroup.builder()
                    .id("g-1").name("G").botCount(500).registeredCount(200)
                    .registrationState(RegistrationState.PENDING)
                    .build();
            when(repository.findById("g-1")).thenReturn(Optional.of(pending));

            assertThatThrownBy(() -> service.retryRegistration("g-1"))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("200/500")
                    .hasMessageContaining("PENDING");

            verify(repository, never()).save(any());
            verify(registrationWorker, never()).enqueue(anyString());
        }

        @Test
        @DisplayName("delete calls the registration off before taking the group lock")
        void deleteCancelsRegistrationFirst() {
            BotGroup registering = BotGroup.builder()
                    .id("g-1").name("G").environmentId("env-1")
                    .registrationState(RegistrationState.PENDING)
                    .build();
            when(repository.findById("g-1")).thenReturn(Optional.of(registering));

            service.delete("g-1");

            // A28.6 / the same cancel-then-lock ordering stop() uses: a registration parked
            // inside the budget can be waiting up to registration.max-wait (15 m), and only
            // cancelScope wakes it. Cancelling after stopAndLogout took the lock would make the
            // DELETE wait that out on an HTTP thread.
            org.mockito.InOrder order =
                    org.mockito.Mockito.inOrder(registrationWorker, behaviorService, repository);
            order.verify(registrationWorker).cancel("g-1", "env-1");
            order.verify(behaviorService).stopAndLogout("g-1");
            order.verify(repository).deleteById("g-1");
        }
    }

    @Nested
    @DisplayName("update")
    class UpdateTests {

        @Test
        @DisplayName("Should update and persist entity")
        void shouldUpdateAndPersist() {
            BotGroup existing = BotGroup.builder().id("123").name("Old").build();
            BotGroupDTO dto = BotGroupDTO.builder().name("New").build();

            when(repository.findById("123")).thenReturn(Optional.of(existing));
            when(repository.save(existing)).thenReturn(existing);

            service.update("123", dto);

            verify(mapper).updateEntityFromDTO(dto, existing);
            verify(repository).save(existing);
        }

        @Test
        @DisplayName("Should throw ResourceNotFoundException when not found")
        void shouldThrowWhenNotFound() {
            when(repository.findById("missing")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.update("missing", BotGroupDTO.builder().build()))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    @Nested
    @DisplayName("setActivationMode (TIMED_ACTIVATION AD-4)")
    class SetActivationModeTests {

        @Test
        @DisplayName("flips activationMode and persists, re-stamping updatedAt, without touching targetStatus")
        void flipsModeAndPersists() {
            BotGroup existing = BotGroup.builder()
                    .id("123")
                    .name("Scheduled")
                    .activationMode(com.vingame.bot.domain.botgroup.model.ActivationMode.SCHEDULED)
                    .targetStatus(BotGroupStatus.ACTIVE)
                    .build();
            when(repository.findById("123")).thenReturn(Optional.of(existing));
            when(repository.save(any(BotGroup.class))).thenAnswer(inv -> inv.getArgument(0));

            service.setActivationMode("123",
                    com.vingame.bot.domain.botgroup.model.ActivationMode.MANUAL_OFF);

            ArgumentCaptor<BotGroup> captor = ArgumentCaptor.forClass(BotGroup.class);
            verify(repository).save(captor.capture());
            BotGroup saved = captor.getValue();
            assertThat(saved.getActivationMode())
                    .isEqualTo(com.vingame.bot.domain.botgroup.model.ActivationMode.MANUAL_OFF);
            // targetStatus is driven by the explicit start/stop paths, not this flip.
            assertThat(saved.getTargetStatus()).isEqualTo(BotGroupStatus.ACTIVE);
            assertThat(saved.getUpdatedAt()).isNotNull();
        }

        @Test
        @DisplayName("throws ResourceNotFoundException when the group does not exist")
        void throwsWhenMissing() {
            when(repository.findById("missing")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.setActivationMode("missing",
                    com.vingame.bot.domain.botgroup.model.ActivationMode.MANUAL_ON))
                    .isInstanceOf(ResourceNotFoundException.class);

            verify(repository, never()).save(any(BotGroup.class));
        }

        @Test
        @DisplayName("group overload persists on the already-loaded group without a redundant findById")
        void groupOverloadPersistsWithoutReread() {
            BotGroup existing = BotGroup.builder()
                    .id("123")
                    .name("Scheduled")
                    .activationMode(com.vingame.bot.domain.botgroup.model.ActivationMode.SCHEDULED)
                    .targetStatus(BotGroupStatus.ACTIVE)
                    .build();
            when(repository.save(any(BotGroup.class))).thenAnswer(inv -> inv.getArgument(0));

            service.setActivationMode(existing,
                    com.vingame.bot.domain.botgroup.model.ActivationMode.MANUAL_ON);

            // No re-read: the caller already holds the document.
            verify(repository, never()).findById(any());
            ArgumentCaptor<BotGroup> captor = ArgumentCaptor.forClass(BotGroup.class);
            verify(repository).save(captor.capture());
            BotGroup saved = captor.getValue();
            assertThat(saved.getActivationMode())
                    .isEqualTo(com.vingame.bot.domain.botgroup.model.ActivationMode.MANUAL_ON);
            assertThat(saved.getUpdatedAt()).isNotNull();
        }
    }

    @Nested
    @DisplayName("delete")
    class DeleteTests {

        @Test
        @DisplayName("Should stop+logout the group then call repository.deleteById (cascade order)")
        void shouldStopLogoutThenDelete() {
            service.delete("123");

            // stopAndLogout must run before the document is removed so the runtime
            // is torn down and every bot logs out before the group vanishes (AD-15).
            org.mockito.InOrder inOrder = org.mockito.Mockito.inOrder(behaviorService, repository);
            inOrder.verify(behaviorService).stopAndLogout("123");
            inOrder.verify(repository).deleteById("123");
        }
    }

    @Nested
    @DisplayName("timestamp stamping (Phase 4, AD-14/AD-16)")
    class TimestampTests {

        @Test
        @DisplayName("New group save stamps both createdAt and updatedAt")
        void newGroupStampsBoth() {
            BotGroup group = BotGroup.builder()
                    .name("Fresh Group")
                    .environmentId("env-1")
                    .namePrefix("bot")
                    .password("pass")
                    .botCount(5)
                    .build();

            when(repository.save(any(BotGroup.class))).thenAnswer(inv -> inv.getArgument(0));

            Instant before = Instant.now();
            BotGroup result = service.save(group, true);

            assertThat(result.getCreatedAt()).isNotNull().isAfterOrEqualTo(before);
            assertThat(result.getUpdatedAt()).isNotNull().isAfterOrEqualTo(before);
        }

        @Test
        @DisplayName("Update re-stamps updatedAt but preserves the original createdAt")
        void updatePreservesCreatedAtRestampsUpdated() {
            Instant original = Instant.parse("2026-01-01T00:00:00Z");
            BotGroup existing = BotGroup.builder()
                    .id("existing-id")
                    .name("Existing")
                    .environmentId("env-1")
                    .createdAt(original)
                    .updatedAt(original)
                    .build();

            when(repository.findById("existing-id")).thenReturn(Optional.of(existing));
            when(repository.save(any(BotGroup.class))).thenAnswer(inv -> inv.getArgument(0));

            BotGroup result = service.update("existing-id", BotGroupDTO.builder().name("Renamed").build());

            assertThat(result.getCreatedAt()).isEqualTo(original);
            assertThat(result.getUpdatedAt()).isAfter(original);
        }
    }

    @Nested
    @DisplayName("save - username length pre-flight")
    class UsernameLengthValidationTests {

        @Test
        @DisplayName("Should accept when prefix + botCount fits the product cap (Tip, cap=12)")
        void shouldAcceptWhenWithinCap() {
            // Tip cap is 12. prefix "authtest" (8) + 9999 (4 digits) = 12, exactly at the cap.
            BotGroup group = BotGroup.builder()
                    .name("Tip Group")
                    .environmentId("env-tip")
                    .namePrefix("authtest")
                    .password("pass")
                    .botCount(9999)
                    .build();

            when(environmentService.findById("env-tip")).thenReturn(envWithProductCode(ProductCode.P_116));
            when(repository.save(any(BotGroup.class))).thenAnswer(inv -> inv.getArgument(0));

            BotGroup result = service.save(group);

            assertThat(result.getId()).isNotNull().isNotEmpty();
            verify(repository).save(group);
        }

        @Test
        @DisplayName("Should reject with BadRequestException when prefix + botCount exceeds the cap")
        void shouldRejectWhenExceedsCap() {
            // Tip cap is 12. prefix "authtestws" (10) + 999 (3 digits) = 13, one over.
            BotGroup group = BotGroup.builder()
                    .name("Tip Overflow Group")
                    .environmentId("env-tip")
                    .namePrefix("authtestws")
                    .password("pass")
                    .botCount(999)
                    .build();

            when(environmentService.findById("env-tip")).thenReturn(envWithProductCode(ProductCode.P_116));

            assertThatThrownBy(() -> service.save(group))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("P_116")
                    .hasMessageContaining("authtestws")
                    .hasMessageContaining("999")
                    .hasMessageContaining("13")
                    .hasMessageContaining("12");

            // Pre-flight must run BEFORE any auth/registration fan-out and BEFORE persistence.
            verify(repository, never()).save(any());
        }

        @Test
        @DisplayName("Should accept any length when product has no documented cap")
        void shouldAcceptWhenProductHasNoCap() {
            // P_097 (BOM) has no documented username cap — should pass even with a very long prefix.
            BotGroup group = BotGroup.builder()
                    .name("Bom Long Prefix Group")
                    .environmentId("env-bom")
                    .namePrefix("aRidiculouslyLongNamePrefixThatWouldNeverPassATipCap")
                    .password("pass")
                    .botCount(50)
                    .build();

            when(environmentService.findById("env-bom")).thenReturn(envWithProductCode(ProductCode.P_097));
            when(repository.save(any(BotGroup.class))).thenAnswer(inv -> inv.getArgument(0));

            BotGroup result = service.save(group);

            assertThat(result.getId()).isNotNull().isNotEmpty();
            verify(repository).save(group);
        }

        @Test
        @DisplayName("Should skip validation entirely on the skipRegistration=true migration path")
        void shouldSkipValidationOnMigrationPath() {
            // Even a username that would blow the Tip cap must be accepted when migrating
            // existing bots — the auth gateway is not contacted on this path.
            BotGroup group = BotGroup.builder()
                    .name("Migrated Tip Group")
                    .environmentId("env-tip")
                    .namePrefix("authtestws")
                    .password("pass")
                    .botCount(999)
                    .build();

            when(repository.save(any(BotGroup.class))).thenAnswer(inv -> inv.getArgument(0));

            BotGroup result = service.save(group, true);

            assertThat(result.getId()).isNotNull().isNotEmpty();
            verify(environmentService, never()).findById(anyString());
        }
    }

    @Nested
    @DisplayName("registration-time deposit (BOT_PROVISIONING Phase 2)")
    class InitialDepositTests {

        private InMemoryDepositLedger ledger() {
            return (InMemoryDepositLedger) depositLedger;
        }

        private BotGroup newGroup(long initialDeposit) {
            return BotGroup.builder().name("Dep").environmentId("env-1").namePrefix("dep")
                    .password("pw").botCount(3).initialDeposit(initialDeposit).build();
        }

        private void mergeDeposit(Long deposit, Integer botCount) {
            org.mockito.Mockito.doAnswer(inv -> {
                BotGroup target = inv.getArgument(1);
                if (deposit != null) {
                    target.setInitialDeposit(deposit);
                }
                if (botCount != null) {
                    target.setBotCount(botCount);
                }
                return null;
            }).when(mapper).updateEntityFromDTO(any(BotGroupDTO.class), any(BotGroup.class));
        }

        @Test
        @DisplayName("a negative initialDeposit is a 400")
        void negativeIsRejected() {
            assertThatThrownBy(() -> service.save(newGroup(-1)))
                    .isInstanceOf(BadRequestException.class).hasMessageContaining("initialDeposit");
            verify(repository, never()).save(any());
        }

        @Test
        @DisplayName("an initialDeposit above bot.provisioning.max-initial-deposit is a 400")
        void aboveTheCapIsRejected() {
            assertThatThrownBy(() -> service.save(newGroup(1_000_000_001L)))
                    .isInstanceOf(BadRequestException.class).hasMessageContaining("1000000000");
            verify(repository, never()).save(any());
        }

        @Test
        @DisplayName("the cap itself and 0 are accepted")
        void boundsAreAccepted() {
            when(environmentService.findById("env-1")).thenReturn(envWithProductCode(ProductCode.P_097));
            when(repository.save(any(BotGroup.class))).thenAnswer(inv -> inv.getArgument(0));

            assertThat(service.save(newGroup(1_000_000_000L)).getInitialDeposit()).isEqualTo(1_000_000_000L);
            assertThat(service.save(newGroup(0)).getInitialDeposit()).isZero();
        }

        @Test
        @DisplayName("existingGroup=true with initialDeposit > 0 is a 400 — nothing is registered, nothing is funded")
        void existingGroupWithDepositIsRejected() {
            assertThatThrownBy(() -> service.save(newGroup(5), true))
                    .isInstanceOf(BadRequestException.class).hasMessageContaining("existingGroup");
            verify(repository, never()).save(any());
        }

        @Test
        @DisplayName("initialDeposit cannot change while registration is PENDING (or FAILED)")
        void changeDuringRegistrationIsRejected() {
            BotGroup pending = newGroup(100);
            pending.setId("g-d");
            pending.setRegistrationState(RegistrationState.PENDING);
            when(repository.findById("g-d")).thenReturn(Optional.of(pending));
            mergeDeposit(200L, null);

            assertThatThrownBy(() -> service.update("g-d", BotGroupDTO.builder().initialDeposit(200L).build()))
                    .isInstanceOf(BadRequestException.class).hasMessageContaining("PENDING");

            pending.setRegistrationState(RegistrationState.FAILED);
            pending.setInitialDeposit(100);
            assertThatThrownBy(() -> service.update("g-d", BotGroupDTO.builder().initialDeposit(200L).build()))
                    .isInstanceOf(BadRequestException.class);
            verify(repository, never()).save(any());
        }

        @Test
        @DisplayName("initialDeposit can change once registration is complete")
        void changeWhenCompleteIsAllowed() {
            BotGroup complete = newGroup(100);
            complete.setId("g-d");
            complete.setRegisteredCount(3);
            when(repository.findById("g-d")).thenReturn(Optional.of(complete));
            when(repository.save(any(BotGroup.class))).thenAnswer(inv -> inv.getArgument(0));
            mergeDeposit(200L, null);

            assertThat(service.update("g-d", BotGroupDTO.builder().initialDeposit(200L).build())
                    .getInitialDeposit()).isEqualTo(200L);
        }

        @Test
        @DisplayName("QA: PATCH initialDeposit on a complete group is range-checked too (negative, above cap → 400)")
        void patchOutOfRangeIsRejected() {
            BotGroup complete = newGroup(100);
            complete.setId("g-d");
            complete.setRegisteredCount(3);
            when(repository.findById("g-d")).thenReturn(Optional.of(complete));

            mergeDeposit(-1L, null);
            assertThatThrownBy(() -> service.update("g-d", BotGroupDTO.builder().initialDeposit(-1L).build()))
                    .isInstanceOf(BadRequestException.class).hasMessageContaining("initialDeposit");

            complete.setInitialDeposit(100);
            mergeDeposit(1_000_000_001L, null);
            assertThatThrownBy(() -> service.update("g-d",
                    BotGroupDTO.builder().initialDeposit(1_000_000_001L).build()))
                    .isInstanceOf(BadRequestException.class).hasMessageContaining("1000000000");
            verify(repository, never()).save(any());
        }

        @Test
        @DisplayName("QA: a PATCH during PENDING that leaves initialDeposit unchanged is not rejected for it")
        void unchangedDepositDuringRegistrationIsAllowed() {
            BotGroup pending = newGroup(100);
            pending.setId("g-d");
            pending.setRegistrationState(RegistrationState.PENDING);
            when(repository.findById("g-d")).thenReturn(Optional.of(pending));
            when(repository.save(any(BotGroup.class))).thenAnswer(inv -> inv.getArgument(0));
            mergeDeposit(100L, null);

            assertThat(service.update("g-d", BotGroupDTO.builder().initialDeposit(100L).build())
                    .getInitialDeposit()).isEqualTo(100L);
        }

        @Test
        @DisplayName("QA: a raise on a REGISTRATION_FAILED group seeds nothing — the failed job still owes its deposits")
        void raiseDuringFailedDoesNotSeed() {
            BotGroup failed = newGroup(100);
            failed.setId("g-d");
            failed.setRegisteredCount(2);
            failed.setRegistrationState(RegistrationState.FAILED);
            when(repository.findById("g-d")).thenReturn(Optional.of(failed));
            org.mockito.Mockito.lenient().when(repository.save(any(BotGroup.class))).thenAnswer(inv -> inv.getArgument(0));
            org.mockito.Mockito.lenient().when(environmentService.findById("env-1"))
                    .thenReturn(envWithProductCode(ProductCode.P_097));
            mergeDeposit(null, 5);

            service.update("g-d", BotGroupDTO.builder().botCount(5).build());

            assertThat(ledger().journal).noneMatch(e -> e.startsWith("seed"));
        }

        @Test
        @DisplayName("QA: a resolution whose marker moved under it (stale) is a 400 and enqueues nothing")
        void staleResolutionIsRejected() {
            failedOnUnknownDeposit();
            // Another request resolved it first, between read and resolve.
            org.mockito.Mockito.doReturn(false).when(depositLedger).resolve("g-d", 2, true);

            assertThatThrownBy(() -> service.retryRegistration("g-d", BotGroupService.DepositResolution.CREDITED, 2))
                    .isInstanceOf(BadRequestException.class).hasMessageContaining("changed");
            verify(registrationWorker, never()).enqueue(anyString());
        }

        @Test
        @DisplayName("QA: deleting a group forgets its ledger entry")
        void deleteForgetsTheLedger() {
            BotGroup g = newGroup(100);
            g.setId("g-d");
            ledger().put("g-d", 3, null);
            org.mockito.Mockito.lenient().when(repository.findById("g-d")).thenReturn(Optional.of(g));

            service.delete("g-d");

            assertThat(ledger().read("g-d")).isEqualTo(new DepositLedger.State(0, null));
        }

        @Test
        @DisplayName("AD-9: a raise on a complete group never funds the accounts that existed before it")
        void raiseOnCompleteGroupSeedsTheLedger() {
            BotGroup complete = newGroup(100);
            complete.setId("g-d");
            complete.setRegisteredCount(3);
            when(repository.findById("g-d")).thenReturn(Optional.of(complete));
            when(repository.save(any(BotGroup.class))).thenAnswer(inv -> inv.getArgument(0));
            when(environmentService.findById("env-1")).thenReturn(envWithProductCode(ProductCode.P_097));
            mergeDeposit(null, 5);

            BotGroup result = service.update("g-d", BotGroupDTO.builder().botCount(5).build());

            assertThat(ledger().read("g-d").depositedCount())
                    .as("accounts 1-3 pre-date the raise: the worker starts funding at 4")
                    .isEqualTo(3);
            assertThat(result.getDepositedCount()).isEqualTo(3);
            assertThat(result.getRegistrationState()).isEqualTo(RegistrationState.PENDING);
        }

        @Test
        @DisplayName("AD-9: the seed never moves the mark back")
        void seedIsMonotonic() {
            BotGroup complete = newGroup(100);
            complete.setId("g-d");
            complete.setBotCount(3);
            complete.setRegisteredCount(5);
            ledger().put("g-d", 5, null);
            when(repository.findById("g-d")).thenReturn(Optional.of(complete));
            when(repository.save(any(BotGroup.class))).thenAnswer(inv -> inv.getArgument(0));
            when(environmentService.findById("env-1")).thenReturn(envWithProductCode(ProductCode.P_097));
            mergeDeposit(null, 8);

            service.update("g-d", BotGroupDTO.builder().botCount(8).build());

            assertThat(ledger().read("g-d").depositedCount()).isEqualTo(5);
        }

        @Test
        @DisplayName("AD-9: a raise during PENDING seeds nothing — those indices still owe a deposit")
        void raiseDuringPendingDoesNotSeed() {
            BotGroup pending = newGroup(100);
            pending.setId("g-d");
            pending.setRegisteredCount(1);
            pending.setRegistrationState(RegistrationState.PENDING);
            when(repository.findById("g-d")).thenReturn(Optional.of(pending));
            when(repository.save(any(BotGroup.class))).thenAnswer(inv -> inv.getArgument(0));
            when(environmentService.findById("env-1")).thenReturn(envWithProductCode(ProductCode.P_097));
            mergeDeposit(null, 5);

            service.update("g-d", BotGroupDTO.builder().botCount(5).build());

            assertThat(ledger().read("g-d").depositedCount()).isZero();
            assertThat(ledger().journal).noneMatch(e -> e.startsWith("seed"));
        }

        private BotGroup failedOnUnknownDeposit() {
            BotGroup failed = newGroup(100);
            failed.setId("g-d");
            failed.setRegisteredCount(3);
            failed.setRegistrationState(RegistrationState.FAILED);
            failed.setDepositInFlight(2);
            ledger().put("g-d", 1, 2);
            when(repository.findById("g-d")).thenReturn(Optional.of(failed));
            stubConditionalFlip(failed);
            return failed;
        }

        @Test
        @DisplayName("AD-8: retrying an unknown deposit outcome without an answer is a 400")
        void retryWithoutAnswerIsRejected() {
            failedOnUnknownDeposit();

            assertThatThrownBy(() -> service.retryRegistration("g-d"))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("depositOutcome=credited");
            assertThat(ledger().read("g-d")).isEqualTo(new DepositLedger.State(1, 2));
            verify(registrationWorker, never()).enqueue(anyString());
        }

        @Test
        @DisplayName("AD-8: 'credited' advances the funded mark and clears the marker")
        void retryCreditedAdvances() {
            failedOnUnknownDeposit();

            BotGroup result = service.retryRegistration("g-d", BotGroupService.DepositResolution.CREDITED, 2);

            assertThat(ledger().read("g-d")).isEqualTo(new DepositLedger.State(2, null));
            assertThat(result.getDepositedCount()).isEqualTo(2);
            assertThat(result.getDepositInFlight()).isNull();
            assertThat(result.getRegistrationState()).isEqualTo(RegistrationState.PENDING);
            verify(registrationWorker).enqueue("g-d");
        }

        @Test
        @DisplayName("AD-8: 'not-credited' leaves the mark, so the index is sent once more")
        void retryNotCreditedLeavesTheMark() {
            failedOnUnknownDeposit();

            service.retryRegistration("g-d", BotGroupService.DepositResolution.NOT_CREDITED, 2);

            assertThat(ledger().read("g-d")).isEqualTo(new DepositLedger.State(1, null));
        }

        @Test
        @DisplayName("AD-8: an answer for a group with no unknown outcome is a 400")
        void answerWithoutUnknownIsRejected() {
            BotGroup failed = newGroup(100);
            failed.setId("g-d");
            failed.setRegistrationState(RegistrationState.FAILED);
            when(repository.findById("g-d")).thenReturn(Optional.of(failed));

            assertThatThrownBy(() -> service.retryRegistration("g-d",
                    BotGroupService.DepositResolution.CREDITED, 2))
                    .isInstanceOf(BadRequestException.class).hasMessageContaining("does not apply");
        }

        @Test
        @DisplayName("review bug 2c: an answer without the index checked is a 400")
        void answerWithoutIndexIsRejected() {
            failedOnUnknownDeposit();

            assertThatThrownBy(() -> service.retryRegistration("g-d",
                    BotGroupService.DepositResolution.NOT_CREDITED, null))
                    .isInstanceOf(BadRequestException.class).hasMessageContaining("depositIndex=2");
            assertThat(ledger().read("g-d")).isEqualTo(new DepositLedger.State(1, 2));
        }

        @Test
        @DisplayName("review bug 2c: an answer for a different index than the live marker is a 400 and changes nothing")
        void answerForTheWrongIndexIsRejected() {
            failedOnUnknownDeposit();
            // The operator checked bot2 — but meanwhile the marker moved to bot5 (a second JVM, or
            // a double-submitted retry already resolved 2 and the resend of 5 is in flight).
            ledger().put("g-d", 4, 5);

            assertThatThrownBy(() -> service.retryRegistration("g-d",
                    BotGroupService.DepositResolution.NOT_CREDITED, 2))
                    .isInstanceOf(BadRequestException.class).hasMessageContaining("does not match");
            assertThat(ledger().read("g-d")).as("the live marker for 5 is untouched")
                    .isEqualTo(new DepositLedger.State(4, 5));
            verify(registrationWorker, never()).enqueue(anyString());
        }

        @Test
        @DisplayName("review bug 2d: FAILED -> PENDING is conditional — a second concurrent retry is a 400, no save")
        void flipIsConditional() {
            BotGroup failed = failedOnUnknownDeposit();
            service.retryRegistration("g-d", BotGroupService.DepositResolution.CREDITED, 2);
            assertThat(failed.getRegistrationState()).isEqualTo(RegistrationState.PENDING);

            // The second retry read FAILED before the first flipped it: simulate by re-failing the
            // ledger question but leaving the document PENDING.
            ledger().put("g-d", 2, 3);
            failed.setRegistrationState(RegistrationState.FAILED);
            org.mockito.Mockito.doAnswer(inv -> {
                failed.setRegistrationState(RegistrationState.PENDING);  // the other retry won
                return inv.callRealMethod();
            }).when(depositLedger).resolve("g-d", 3, false);

            assertThatThrownBy(() -> service.retryRegistration("g-d",
                    BotGroupService.DepositResolution.NOT_CREDITED, 3))
                    .isInstanceOf(BadRequestException.class).hasMessageContaining("another retry");
            verify(repository, never()).save(any());
        }

        @Test
        @DisplayName("review smell: lowering botCount does not clear an unknown-deposit FAILED, marker kept")
        void loweringDoesNotOrphanTheMarker() {
            BotGroup failed = failedOnUnknownDeposit();
            failed.setBotCount(3);
            when(repository.save(any(BotGroup.class))).thenAnswer(inv -> inv.getArgument(0));
            mergeDeposit(null, 2);

            BotGroup result = service.update("g-d", BotGroupDTO.builder().botCount(2).build());

            assertThat(result.getRegistrationState()).isEqualTo(RegistrationState.FAILED);
            assertThat(ledger().read("g-d")).isEqualTo(new DepositLedger.State(1, 2));
        }

        @Test
        @DisplayName("review smell: lowering botCount does not declare a funded group startable with an unfunded index")
        void loweringRequiresTheDepositsToo() {
            BotGroup failed = newGroup(100);
            failed.setId("g-d");
            failed.setBotCount(5);
            failed.setRegisteredCount(3);
            failed.setRegistrationState(RegistrationState.FAILED);
            ledger().put("g-d", 2, null);   // index 3 registered, its deposit refused
            when(repository.findById("g-d")).thenReturn(Optional.of(failed));
            when(repository.save(any(BotGroup.class))).thenAnswer(inv -> inv.getArgument(0));
            mergeDeposit(null, 3);

            assertThat(service.update("g-d", BotGroupDTO.builder().botCount(3).build())
                    .getRegistrationState()).isEqualTo(RegistrationState.FAILED);

            mergeDeposit(null, 2);
            assertThat(service.update("g-d", BotGroupDTO.builder().botCount(2).build())
                    .getRegistrationState()).as("2 registered and funded: startable").isNull();
        }

        @Test
        @DisplayName("depositOutcome parses only 'credited' and 'not-credited'")
        void resolutionParsing() {
            assertThat(BotGroupService.DepositResolution.parse(null)).isNull();
            assertThat(BotGroupService.DepositResolution.parse("credited"))
                    .isEqualTo(BotGroupService.DepositResolution.CREDITED);
            assertThat(BotGroupService.DepositResolution.parse("NOT-CREDITED"))
                    .isEqualTo(BotGroupService.DepositResolution.NOT_CREDITED);
            assertThatThrownBy(() -> BotGroupService.DepositResolution.parse("yes"))
                    .isInstanceOf(BadRequestException.class);
        }
    }

    @Nested
    @DisplayName("update - username length pre-flight on a botCount raise (BOT_PROVISIONING AD-4)")
    class UpdateUsernameLengthValidationTests {

        private BotGroup tipGroup(int botCount) {
            // Tip cap is 12; prefix "prov0123ab" is 10 characters.
            return BotGroup.builder()
                    .id("g-tip").name("Tip").environmentId("env-tip").namePrefix("prov0123ab")
                    .password("pass").botCount(botCount).registeredCount(botCount)
                    .namedCount(botCount)
                    .build();
        }

        private void mergeBotCount(int botCount) {
            org.mockito.Mockito.doAnswer(inv -> {
                inv.<BotGroup>getArgument(1).setBotCount(botCount);
                return null;
            }).when(mapper).updateEntityFromDTO(any(BotGroupDTO.class), any(BotGroup.class));
        }

        @Test
        @DisplayName("a raise past the product cap is a 400 and persists nothing")
        void raisePastTheCapIsRejected() {
            // 99 -> 100 crosses from 12 to 13 characters: this used to pass PATCH and fail at
            // the gateway on index 100.
            when(repository.findById("g-tip")).thenReturn(Optional.of(tipGroup(99)));
            when(environmentService.findById("env-tip")).thenReturn(envWithProductCode(ProductCode.P_116));
            mergeBotCount(100);

            assertThatThrownBy(() -> service.update("g-tip", BotGroupDTO.builder().botCount(100).build()))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("P_116")
                    .hasMessageContaining("prov0123ab")
                    .hasMessageContaining("13")
                    .hasMessageContaining("12");

            verify(repository, never()).save(any());
            verify(registrationWorker, never()).enqueue(anyString());
        }

        @Test
        @DisplayName("a raise that still fits the cap passes")
        void raiseWithinTheCapPasses() {
            when(repository.findById("g-tip")).thenReturn(Optional.of(tipGroup(9)));
            when(environmentService.findById("env-tip")).thenReturn(envWithProductCode(ProductCode.P_116));
            when(repository.save(any(BotGroup.class))).thenAnswer(inv -> inv.getArgument(0));
            mergeBotCount(99);

            BotGroup result = service.update("g-tip", BotGroupDTO.builder().botCount(99).build());

            assertThat(result.getBotCount()).isEqualTo(99);
            assertThat(result.getRegistrationState()).isEqualTo(RegistrationState.PENDING);
        }

        @Test
        @DisplayName("a PATCH that does not raise botCount never runs the check")
        void noRaiseNoCheck() {
            // A group whose prefix already violates the cap (it predates the check) must stay
            // editable: a rename does not make any username longer.
            BotGroup legacy = tipGroup(999);
            when(repository.findById("g-tip")).thenReturn(Optional.of(legacy));
            when(repository.save(any(BotGroup.class))).thenAnswer(inv -> inv.getArgument(0));

            BotGroup result = service.update("g-tip", BotGroupDTO.builder().name("Renamed").build());

            assertThat(result).isNotNull();
            verify(environmentService, never()).findById(anyString());
        }

        @Test
        @DisplayName("lowering botCount never runs the check")
        void loweringNeverChecks() {
            when(repository.findById("g-tip")).thenReturn(Optional.of(tipGroup(999)));
            when(repository.save(any(BotGroup.class))).thenAnswer(inv -> inv.getArgument(0));
            mergeBotCount(500);

            service.update("g-tip", BotGroupDTO.builder().botCount(500).build());

            verify(environmentService, never()).findById(anyString());
        }
    }

    @Nested
    @DisplayName("save - gameId in environment validation (AD-7)")
    class GameEnvironmentValidationTests {

        @Test
        @DisplayName("Should reject with 400 when the game belongs to a different environment")
        void shouldRejectWhenGameEnvMismatch() {
            BotGroup group = BotGroup.builder()
                    .name("Mismatch Group")
                    .environmentId("env-1")
                    .gameId("game-99")
                    .namePrefix("bot")
                    .password("pass")
                    .botCount(5)
                    .build();

            when(gameService.findById("game-99"))
                    .thenReturn(Game.builder().id("game-99").environmentId("env-OTHER").build());

            assertThatThrownBy(() -> service.save(group))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("game-99")
                    .hasMessageContaining("env-OTHER")
                    .hasMessageContaining("env-1");

            // Validation runs before any registration fan-out and before persistence.
            verify(repository, never()).save(any());
        }

        @Test
        @DisplayName("Should accept when the game belongs to the group's environment")
        void shouldAcceptWhenGameEnvMatches() {
            BotGroup group = BotGroup.builder()
                    .name("Match Group")
                    .environmentId("env-1")
                    .gameId("game-1")
                    .namePrefix("bot")
                    .password("pass")
                    .botCount(3)
                    .build();

            when(gameService.findById("game-1"))
                    .thenReturn(Game.builder().id("game-1").environmentId("env-1").build());

            when(environmentService.findById("env-1")).thenReturn(envWithoutCap());
            when(repository.save(any(BotGroup.class))).thenAnswer(inv -> inv.getArgument(0));

            BotGroup result = service.save(group);

            assertThat(result.getId()).isNotNull().isNotEmpty();
            verify(repository).save(group);
        }

        @Test
        @DisplayName("Should allow a null-env game defensively during the migration window")
        void shouldAllowNullEnvGame() {
            BotGroup group = BotGroup.builder()
                    .name("Null Env Game Group")
                    .environmentId("env-1")
                    .gameId("game-unmigrated")
                    .namePrefix("bot")
                    .password("pass")
                    .botCount(2)
                    .build();

            when(gameService.findById("game-unmigrated"))
                    .thenReturn(Game.builder().id("game-unmigrated").environmentId(null).build());

            when(environmentService.findById("env-1")).thenReturn(envWithoutCap());
            when(repository.save(any(BotGroup.class))).thenAnswer(inv -> inv.getArgument(0));

            BotGroup result = service.save(group);

            assertThat(result.getId()).isNotNull().isNotEmpty();
            verify(repository).save(group);
        }

        @Test
        @DisplayName("Should reject a mismatch even when registration is skipped (existing-group migration)")
        void shouldRejectMismatchOnSkipRegistrationPath() {
            // The existing-group migration path (skipRegistration=true) bypasses the
            // auth fan-out but must still enforce AD-7 before persisting.
            BotGroup group = BotGroup.builder()
                    .name("Migrated Mismatch Group")
                    .environmentId("env-1")
                    .gameId("game-99")
                    .namePrefix("bot")
                    .password("pass")
                    .botCount(5)
                    .build();

            when(gameService.findById("game-99"))
                    .thenReturn(Game.builder().id("game-99").environmentId("env-OTHER").build());

            assertThatThrownBy(() -> service.save(group, true))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("game-99")
                    .hasMessageContaining("env-OTHER")
                    .hasMessageContaining("env-1");

            // Rejected before persistence; the skip path never touched the registry either.
            verify(repository, never()).save(any());
        }

        @Test
        @DisplayName("Should not run the gameId-in-env check on the update (existing group) path")
        void shouldNotValidateGameEnvOnUpdate() {
            // AD-7 is a new-group create-time guard only. update() merges + persists
            // without re-checking game/env, so a stale mismatch must not block a PATCH.
            BotGroup existing = BotGroup.builder()
                    .id("existing-1")
                    .name("Existing Group")
                    .environmentId("env-1")
                    .gameId("game-99")
                    .build();
            when(repository.findById("existing-1")).thenReturn(Optional.of(existing));
            when(repository.save(any(BotGroup.class))).thenAnswer(inv -> inv.getArgument(0));

            service.update("existing-1", BotGroupDTO.builder().name("Renamed").build());

            verify(gameService, never()).findById(anyString());
            verify(repository).save(existing);
        }

        @Test
        @DisplayName("Should no-op the check when the group carries no gameId")
        void shouldSkipWhenNoGameId() {
            BotGroup group = BotGroup.builder()
                    .name("No Game Group")
                    .environmentId("env-1")
                    .namePrefix("bot")
                    .password("pass")
                    .botCount(2)
                    .build();

            when(environmentService.findById("env-1")).thenReturn(envWithoutCap());
            when(repository.save(any(BotGroup.class))).thenAnswer(inv -> inv.getArgument(0));

            service.save(group);

            verify(gameService, never()).findById(anyString());
            verify(repository).save(group);
        }
    }

    /**
     * Helper: an Environment whose ProductCode has no documented cap, so the pre-flight
     * length check is a no-op. Used by all pre-existing save tests that don't care about
     * the cap.
     */
    private Environment envWithoutCap() {
        return envWithProductCode(ProductCode.P_097); // BOM — no cap declared
    }

    private Environment envWithProductCode(ProductCode code) {
        return Environment.builder().id("env-stub").productCode(code).build();
    }
}
