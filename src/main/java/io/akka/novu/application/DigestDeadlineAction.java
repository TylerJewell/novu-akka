package io.akka.novu.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.timedaction.TimedAction;

/**
 * What fires when a digest's window closes — SPEC-001 §3 rules R1-R4, open decision OD-3.
 *
 * <p>An entity cannot schedule a timer, so the endpoint that admitted the opening event
 * registers this call and this is where the group is told to deliver (question-log row 31).
 */
@Component(id = "digest-deadline")
public class DigestDeadlineAction extends TimedAction {

    /**
     * Only the group's address travels with the timer. The reach is measured by the
     * configuration the digest was opened with, which the group already holds — so a
     * timer parameter stays a reference rather than growing with the payload behind it.
     */
    public record Deadline(String groupId) {
    }

    private final ComponentClient componentClient;

    public DigestDeadlineAction(ComponentClient componentClient) {
        this.componentClient = componentClient;
    }

    public Effect fire(Deadline deadline) {
        // A timer is at-least-once, so a second firing for one deadline finds nothing open
        // and the group answers an empty delivery. Both answers are complete: there is no
        // branch here because there is nothing different to do about either.
        componentClient
                .forEventSourcedEntity(deadline.groupId())
                .method(DigestGroupEntity::deliver)
                .invoke();

        return effects().done();
    }
}
