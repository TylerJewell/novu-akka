package io.akka.novu.api;

import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.annotations.http.Post;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.timer.TimerScheduler;
import io.akka.novu.application.DigestDeadlineAction;
import io.akka.novu.application.DigestGroupEntity;
import io.akka.novu.application.SubscriberPreferenceEntity;
import io.akka.novu.domain.Channel;
import io.akka.novu.domain.ChannelSelection;
import io.akka.novu.domain.DigestConfig;
import io.akka.novu.domain.DigestConfigValidation;
import io.akka.novu.domain.DigestConfigurationException;
import io.akka.novu.domain.DigestEvent;
import io.akka.novu.domain.DigestGroup;
import io.akka.novu.domain.DigestGroupKey;
import io.akka.novu.domain.DigestGroupState;
import io.akka.novu.domain.DigestOutcome;
import io.akka.novu.domain.DigestWindow;
import io.akka.novu.domain.DelayOverride;
import io.akka.novu.domain.Payloads;
import io.akka.novu.domain.PreferenceLevel;
import io.akka.novu.domain.PreferenceMerge;
import io.akka.novu.domain.PreferenceSource;
import io.akka.novu.domain.StepType;
import io.akka.novu.domain.SubscriberKey;
import io.akka.novu.domain.WorkflowPreference;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The digest engine's own surface — SPEC-001 §1.
 *
 * <p>Everything the engine decides is reachable from here without a test harness: offer an
 * event and learn whether it opened a digest, joined one or bypassed digesting; read a
 * group; make a digest fire; compute a window on its own; state a subscriber's preference
 * and ask which channels survive.
 */
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.ALL))
@HttpEndpoint("/digest")
public class DigestEndpoint {

    private final ComponentClient componentClient;
    private final TimerScheduler timerScheduler;

    public DigestEndpoint(ComponentClient componentClient, TimerScheduler timerScheduler) {
        this.componentClient = componentClient;
        this.timerScheduler = timerScheduler;
    }

    /**
     * @param now the moment to decide at, or null for the event's own arrival; a caller
     *     that wants a reproducible answer supplies it
     * @param zone IANA zone for a timed schedule's time of day, defaulting to UTC (OD-5)
     */
    public record OfferRequest(
            String environmentId,
            String workflowId,
            String subscriberId,
            DigestEvent event,
            DigestConfig config,
            Instant now,
            String zone) {
    }

    public record OfferResponse(
            DigestOutcome outcome,
            String groupId,
            String mergedInto,
            Instant deadline,
            long windowMillis,
            String configurationRejection) {
    }

    @Post("/offer")
    public OfferResponse offer(OfferRequest request) {
        String groupId = groupId(request);
        Instant now = request.now() == null ? request.event().createdAt() : request.now();

        DigestGroupEntity.Offered offered = componentClient
                .forEventSourcedEntity(groupId)
                .method(DigestGroupEntity::offer)
                .invoke(new DigestGroupEntity.Offer(request.event(), request.config(), now, request.zone()));

        // Registered only for the event that opened the digest: a merge joins a window that
        // is already being waited on, and a skip is not digested at all.
        if (offered.outcome() == DigestOutcome.CREATED && offered.configurationRejection() == null) {
            timerScheduler.createSingleTimer(
                    "digest-deadline-" + groupId + "-" + request.event().eventId(),
                    Duration.between(now, offered.deadline()).isNegative()
                            ? Duration.ZERO
                            : Duration.between(now, offered.deadline()),
                    componentClient.forTimedAction()
                            .method(DigestDeadlineAction::fire)
                            .deferred(new DigestDeadlineAction.Deadline(groupId)));
        }

        return new OfferResponse(
                offered.outcome(),
                groupId,
                offered.mergedInto(),
                offered.deadline(),
                offered.deadline() == null ? 0L : Duration.between(now, offered.deadline()).toMillis(),
                offered.configurationRejection());
    }

    /**
     * A group as a caller sees it.
     *
     * @param openEventIds the events the open digest is currently holding, in arrival order
     * @param remembered how many events the group still remembers, which is bounded
     */
    public record GroupView(String groupId, String masterEventId, Instant openedAt, Instant deadline,
                            boolean open, List<String> openEventIds, int remembered) {
    }

    @Get("/groups/{groupId}")
    public GroupView group(String groupId) {
        DigestGroupState state = componentClient
                .forEventSourcedEntity(groupId).method(DigestGroupEntity::get).invoke();

        return new GroupView(groupId, state.masterEventId(), state.openedAt(), state.deadline(),
                state.open(),
                DigestGroup.collect(state).stream().map(DigestEvent::eventId).toList(),
                state.events().size());
    }

    /**
     * @param wasOpen false where nothing was open to fire, which is what a second delivery
     *     for one deadline answers
     */
    public record DeliveryView(String groupId, boolean wasOpen, List<DigestEvent> events) {
    }

    /** Fire a digest now rather than waiting for its deadline. */
    @Post("/groups/{groupId}/deliver")
    public DeliveryView deliver(String groupId) {
        DigestGroupEntity.Delivered delivered = componentClient
                .forEventSourcedEntity(groupId)
                .method(DigestGroupEntity::deliver)
                .invoke();

        return new DeliveryView(groupId, delivered.wasOpen(), delivered.events());
    }

    /**
     * @param stepType the step the configuration belongs to; only a digest step accepts one
     */
    public record WindowRequest(
            StepType stepType,
            DigestConfig config,
            Map<String, Object> payload,
            DelayOverride override,
            Instant now,
            String zone,
            boolean validate) {
    }

    public record WindowResponse(Long millis, String rejection) {
    }

    /** The window on its own, with no group behind it — SPEC-001 §3 rules W1-W9, V1-V7. */
    @Post("/window")
    public WindowResponse window(WindowRequest request) {
        Instant now = request.now() == null ? Instant.now() : request.now();
        ZoneId zone;
        try {
            zone = request.zone() == null ? ZoneId.of("UTC") : ZoneId.of(request.zone());
        } catch (RuntimeException e) {
            // A zone name the platform does not know is a caller mistake. Answered as a
            // rejection alongside every other one rather than as an unexplained 500.
            return new WindowResponse(null, "Unknown time zone");
        }
        try {
            if (request.validate()) {
                DigestConfigValidation.validate(
                        request.stepType() == null ? StepType.DIGEST : request.stepType(), request.config());
            }
            Map<String, Object> payload = request.payload() == null ? Map.of() : request.payload();

            return new WindowResponse(
                    DigestWindow.of(request.config(), payload, request.override(), now, zone), null);
        } catch (DigestConfigurationException e) {
            return new WindowResponse(null, e.getMessage());
        }
    }

    public record SetPreference(String environmentId, String subscriberId, String workflowId,
                                WorkflowPreference preference) {
    }

    @Post("/preferences")
    public SubscriberPreferenceEntity.State setPreference(SetPreference request) {
        String id = new SubscriberKey(request.environmentId(), request.subscriberId()).id();
        if (request.workflowId() == null) {
            return componentClient.forKeyValueEntity(id)
                    .method(SubscriberPreferenceEntity::setGlobal)
                    .invoke(request.preference());
        }

        return componentClient.forKeyValueEntity(id)
                .method(SubscriberPreferenceEntity::setForWorkflow)
                .invoke(new SubscriberPreferenceEntity.SetWorkflowPreference(
                        request.workflowId(), request.preference()));
    }

    /**
     * @param activeChannels the channels the workflow has an active step for
     * @param workflowResource the workflow's own preference
     * @param workflowUser a preference set on the workflow by a user
     * @param tenantOverride a tenant-scoped override of the workflow's preference
     */
    public record ChannelRequest(
            String environmentId,
            String subscriberId,
            String workflowId,
            List<Channel> activeChannels,
            WorkflowPreference workflowResource,
            WorkflowPreference workflowUser,
            WorkflowPreference tenantOverride,
            boolean excludeSubscriberPreferences) {
    }

    /**
     * @param channels which channels survive, and whether each is on
     * @param reasons which source last set each channel
     * @param sends the channels a step would actually go out on
     */
    public record ChannelResponse(
            boolean enabled,
            Map<Channel, Boolean> channels,
            Map<Channel, PreferenceSource> reasons,
            Map<PreferenceLevel, WorkflowPreference> stated,
            List<Channel> sends) {
    }

    /** Which channels a digested notification goes out on — SPEC-001 §3 rules C1-C8. */
    @Post("/channels")
    public ChannelResponse channels(ChannelRequest request) {
        SubscriberPreferenceEntity.State subscriber = componentClient
                .forKeyValueEntity(new SubscriberKey(request.environmentId(), request.subscriberId()).id())
                .method(SubscriberPreferenceEntity::get)
                .invoke();

        PreferenceMerge.Result merged = PreferenceMerge.merge(new PreferenceMerge.Levels(
                request.workflowResource(),
                request.workflowUser(),
                subscriber.global(),
                subscriber.perWorkflow().get(request.workflowId())),
                request.excludeSubscriberPreferences());

        Map<PreferenceSource, Map<Channel, Boolean>> stated = new LinkedHashMap<>();
        stated.put(PreferenceSource.WORKFLOW_RESOURCE,
                request.workflowResource() == null ? null : request.workflowResource().channels());
        stated.put(PreferenceSource.WORKFLOW_OVERRIDE,
                request.tenantOverride() == null ? null : request.tenantOverride().channels());
        stated.put(PreferenceSource.SUBSCRIBER, merged.channels());

        ChannelSelection.Result resolved = ChannelSelection.resolve(
                ChannelSelection.candidates(
                        request.activeChannels() == null ? List.of() : request.activeChannels()),
                stated);

        List<Channel> sends = resolved.channels().keySet().stream()
                .filter(channel -> ChannelSelection.sends(merged.enabled(), resolved.channels(), channel))
                .toList();

        return new ChannelResponse(merged.enabled(), resolved.channels(), resolved.reasons(),
                merged.stated(), sends);
    }

    private static String groupId(OfferRequest request) {
        String digestValue = Payloads.digestValue(request.config(), request.event().payload());

        return new DigestGroupKey(request.environmentId(), request.workflowId(),
                request.subscriberId(), digestValue).id();
    }
}
