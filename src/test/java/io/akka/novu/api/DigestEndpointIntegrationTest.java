package io.akka.novu.api;

import akka.javasdk.testkit.TestKitSupport;
import io.akka.novu.domain.Channel;
import io.akka.novu.domain.DigestConfig;
import io.akka.novu.domain.DigestEvent;
import io.akka.novu.domain.DigestKind;
import io.akka.novu.domain.DigestOutcome;
import io.akka.novu.domain.DigestUnit;
import io.akka.novu.domain.PreferenceSource;
import io.akka.novu.domain.StepType;
import io.akka.novu.domain.TimedConfig;
import io.akka.novu.domain.WorkflowPreference;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The engine as something outside a test can reach — SPEC-001 §5, the last conformance row.
 *
 * <p>Every decision below goes over HTTP against a running service, so the entity's own
 * event replay and the endpoint's grouping are exercised rather than the domain classes
 * alone.
 */
public class DigestEndpointIntegrationTest extends TestKitSupport {

    private static final Instant NOON = Instant.parse("2026-03-10T12:00:00Z");

    private DigestEndpoint.OfferResponse offer(String subscriber, String eventId, Instant createdAt,
                                               Instant now, DigestConfig config) {
        var response = httpClient.POST("/digest/offer")
                .withRequestBody(new DigestEndpoint.OfferRequest(
                        "env-1", "tpl-1", subscriber,
                        new DigestEvent(eventId, "notif-" + eventId, createdAt, Map.of("orderId", "order-7")),
                        config, now, "UTC"))
                .responseBodyAs(DigestEndpoint.OfferResponse.class)
                .invoke();
        assertThat(response.httpResponse().status().isSuccess()).isTrue();

        return response.body();
    }

    private DigestEndpoint.DeliveryView deliver(String groupId) {
        return httpClient.POST("/digest/groups/" + groupId + "/deliver")
                .responseBodyAs(DigestEndpoint.DeliveryView.class)
                .invoke().body();
    }

    private static DigestConfig regular(long amount, DigestUnit unit) {
        return DigestConfig.builder(DigestKind.REGULAR)
                .amount(amount).unit(unit).digestKey("orderId").digestValue("order-7").build();
    }

    @Test
    public void aBurstOfEventsBecomesOneDigest() {
        DigestConfig config = regular(5, DigestUnit.MINUTES);

        var first = offer("sub-burst", "e1", NOON, NOON, config);
        var second = offer("sub-burst", "e2", NOON.plusSeconds(30), NOON.plusSeconds(30), config);
        var third = offer("sub-burst", "e3", NOON.plusSeconds(60), NOON.plusSeconds(60), config);

        assertThat(List.of(first.outcome(), second.outcome(), third.outcome()))
                .containsExactly(DigestOutcome.CREATED, DigestOutcome.MERGED, DigestOutcome.MERGED);
        assertThat(second.mergedInto()).isEqualTo("e1");
        assertThat(first.deadline()).isEqualTo(NOON.plusSeconds(300));
        assertThat(second.deadline())
                .as("a merge joins the window already being waited on")
                .isEqualTo(first.deadline());

        var delivered = deliver(first.groupId());

        assertThat(delivered.events().stream().map(DigestEvent::eventId))
                .containsExactly("e1", "e2", "e3");
    }

    @Test
    public void adifferentDigestValueIsADifferentDigest() {
        DigestConfig seven = regular(5, DigestUnit.MINUTES);
        DigestConfig eight = DigestConfig.builder(DigestKind.REGULAR)
                .amount(5L).unit(DigestUnit.MINUTES).digestKey("orderId").digestValue("order-8").build();

        var first = offer("sub-values", "v1", NOON, NOON, seven);
        var other = offer("sub-values", "v2", NOON.plusSeconds(10), NOON.plusSeconds(10), eight);

        assertThat(other.groupId()).isNotEqualTo(first.groupId());
        assertThat(other.outcome()).isEqualTo(DigestOutcome.CREATED);
    }

    @Test
    public void aDeliveredDigestDoesNotCarryItsEventsAgain() {
        DigestConfig config = regular(30, DigestUnit.MINUTES);

        var first = offer("sub-twice", "t1", NOON, NOON, config);
        deliver(first.groupId());

        var second = offer("sub-twice", "t2", NOON.plusSeconds(60), NOON.plusSeconds(60), config);
        assertThat(second.outcome())
                .as("the delivered digest is closed, so the next event opens a new one")
                .isEqualTo(DigestOutcome.CREATED);

        var delivered = deliver(second.groupId());

        assertThat(delivered.events().stream().map(DigestEvent::eventId)).containsExactly("t2");
    }

    @Test
    public void aBackoffBurstSkipsThenDigests() {
        DigestConfig config = DigestConfig.builder(DigestKind.BACKOFF)
                .amount(5L).unit(DigestUnit.MINUTES).backoff(true)
                .backoffAmount(10L).backoffUnit(DigestUnit.MINUTES)
                .digestKey("orderId").digestValue("order-7").build();

        var first = offer("sub-backoff", "b1", NOON, NOON, config);
        var second = offer("sub-backoff", "b2", NOON.plusSeconds(60), NOON.plusSeconds(60), config);
        var third = offer("sub-backoff", "b3", NOON.plusSeconds(120), NOON.plusSeconds(120), config);

        assertThat(List.of(first.outcome(), second.outcome(), third.outcome()))
                .containsExactly(DigestOutcome.SKIPPED, DigestOutcome.CREATED, DigestOutcome.MERGED);

        var group = httpClient.GET("/digest/groups/" + second.groupId())
                .responseBodyAs(DigestEndpoint.GroupView.class).invoke().body();
        assertThat(group.masterEventId()).isEqualTo("b2");
        assertThat(group.openEventIds()).containsExactly("b2", "b3");
    }

    @Test
    public void aWindowCanBeAskedForOnItsOwn() {
        var response = httpClient.POST("/digest/window")
                .withRequestBody(new DigestEndpoint.WindowRequest(
                        StepType.DIGEST,
                        DigestConfig.builder(DigestKind.TIMED).amount(1L).unit(DigestUnit.DAYS)
                                .timed(TimedConfig.atTime("09:00")).build(),
                        Map.of(), null, Instant.parse("2026-03-10T08:00:00Z"), "UTC", true))
                .responseBodyAs(DigestEndpoint.WindowResponse.class)
                .invoke().body();

        assertThat(response.millis()).isEqualTo(3_600_000L);
        assertThat(response.rejection()).isNull();
    }

    @Test
    public void aRejectedConfigurationComesBackAsAReasonNotACrash() {
        var response = httpClient.POST("/digest/window")
                .withRequestBody(new DigestEndpoint.WindowRequest(
                        StepType.DIGEST,
                        DigestConfig.builder(DigestKind.REGULAR).amount(0L).unit(DigestUnit.MINUTES).build(),
                        Map.of(), null, NOON, "UTC", true))
                .responseBodyAs(DigestEndpoint.WindowResponse.class)
                .invoke().body();

        assertThat(response.rejection()).isEqualTo("Invalid digest amount");
        assertThat(response.millis()).isNull();
    }

    @Test
    public void offeringOneEventTwiceDecidesItOnce() {
        // A retried offer must not put the event in the digest a second time.
        DigestConfig config = regular(5, DigestUnit.MINUTES);

        var first = offer("sub-retry", "r1", NOON, NOON, config);
        var repeat = offer("sub-retry", "r1", NOON, NOON, config);

        assertThat(repeat.outcome()).isEqualTo(first.outcome());
        assertThat(repeat.deadline()).isEqualTo(first.deadline());
        assertThat(deliver(first.groupId()).events().stream().map(DigestEvent::eventId))
                .containsExactly("r1");
    }

    @Test
    public void anUnknownTimeZoneIsAnAnswerNotAFailure() {
        var response = httpClient.POST("/digest/window")
                .withRequestBody(new DigestEndpoint.WindowRequest(
                        StepType.DIGEST,
                        DigestConfig.builder(DigestKind.TIMED).amount(1L).unit(DigestUnit.DAYS)
                                .timed(TimedConfig.atTime("09:00")).build(),
                        Map.of(), null, NOON, "Mars/Olympus_Mons", true))
                .responseBodyAs(DigestEndpoint.WindowResponse.class)
                .invoke().body();

        assertThat(response.rejection()).isEqualTo("Unknown time zone");
        assertThat(response.millis()).isNull();
    }

    @Test
    public void theSubscribersStatedPreferenceHasTheLastWord() {
        Map<Channel, Boolean> subscriberSays = new LinkedHashMap<>();
        subscriberSays.put(Channel.EMAIL, false);

        httpClient.POST("/digest/preferences")
                .withRequestBody(new DigestEndpoint.SetPreference("env-1", "sub-pref", "tpl-1",
                        new WorkflowPreference(null, false, subscriberSays)))
                .invoke();

        var channels = httpClient.POST("/digest/channels")
                .withRequestBody(new DigestEndpoint.ChannelRequest(
                        "env-1", "sub-pref", "tpl-1",
                        List.of(Channel.EMAIL, Channel.IN_APP),
                        new WorkflowPreference(null, false,
                                new LinkedHashMap<>(Map.of(Channel.EMAIL, true, Channel.IN_APP, true))),
                        null, null, false))
                .responseBodyAs(DigestEndpoint.ChannelResponse.class)
                .invoke().body();

        assertThat(channels.channels().get(Channel.EMAIL)).isFalse();
        assertThat(channels.reasons().get(Channel.EMAIL)).isEqualTo(PreferenceSource.SUBSCRIBER);
        assertThat(channels.sends()).containsExactly(Channel.IN_APP);
        assertThat(channels.channels()).doesNotContainKey(Channel.SMS);
    }

    @Test
    public void aReadOnlyWorkflowKeepsTheSubscriberOut() {
        httpClient.POST("/digest/preferences")
                .withRequestBody(new DigestEndpoint.SetPreference("env-1", "sub-readonly", "tpl-1",
                        new WorkflowPreference(null, false,
                                new LinkedHashMap<>(Map.of(Channel.EMAIL, false)))))
                .invoke();

        var channels = httpClient.POST("/digest/channels")
                .withRequestBody(new DigestEndpoint.ChannelRequest(
                        "env-1", "sub-readonly", "tpl-1",
                        List.of(Channel.EMAIL),
                        new WorkflowPreference(null, true,
                                new LinkedHashMap<>(Map.of(Channel.EMAIL, true))),
                        null, null, false))
                .responseBodyAs(DigestEndpoint.ChannelResponse.class)
                .invoke().body();

        assertThat(channels.sends()).containsExactly(Channel.EMAIL);
    }
}
