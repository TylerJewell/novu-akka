package io.akka.novu.bench;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.akka.novu.domain.Channel;
import io.akka.novu.domain.ChannelSelection;
import io.akka.novu.domain.DelayOverride;
import io.akka.novu.domain.DigestConfig;
import io.akka.novu.domain.DigestConfigurationException;
import io.akka.novu.domain.DigestEvent;
import io.akka.novu.domain.DigestGroup;
import io.akka.novu.domain.DigestGroupState;
import io.akka.novu.domain.DigestKind;
import io.akka.novu.domain.DigestOutcome;
import io.akka.novu.domain.DigestUnit;
import io.akka.novu.domain.MonthlyType;
import io.akka.novu.domain.Ordinal;
import io.akka.novu.domain.OrdinalValue;
import io.akka.novu.domain.PreferenceLevel;
import io.akka.novu.domain.PreferenceMerge;
import io.akka.novu.domain.PreferenceSource;
import io.akka.novu.domain.TimedConfig;
import io.akka.novu.domain.WorkflowPreference;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs {@code bench/workloads.json} through the port's own domain classes in process — the
 * same classes the tests drive — and writes either the answers or the timings as JSON.
 *
 * <p>Usage: {@code BenchRunner <workloads.json> answers|timings <out.json>}
 *
 * <p>The answers file has the shape {@code toolkit/answer_diff.py} reads, and is written to
 * be compared field for field against the source runner's.
 */
public final class BenchRunner {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static void main(String[] args) throws Exception {
        JsonNode workloads = MAPPER.readTree(Files.readString(Path.of(args[0])));
        String mode = args[1];
        Path out = Path.of(args[2]);

        ObjectNode document = MAPPER.createObjectNode();
        if (mode.equals("answers")) {
            ObjectNode answers = document.putObject("answers");
            for (JsonNode workload : workloads) {
                answers.set(workload.get("name").asText(), answersFor(workload));
            }
            document.putObject("timing");
        } else {
            document.set("timing", timings(workloads));
            document.putObject("answers");
        }
        Files.writeString(out, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(document));
        System.out.println("wrote " + out);
    }

    // ---- answers -----------------------------------------------------------------

    private static ArrayNode answersFor(JsonNode workload) {
        String kind = workload.path("kind").asText("");
        if (kind.equals("window")) {
            return windowAnswers(workload);
        }
        if (kind.equals("sequence")) {
            return sequenceAnswers(workload, workload.get("batches"));
        }
        if (workload.path("sequence").asText("").equals("arrival-orders")) {
            return arrivalOrderAnswers(workload);
        }
        if (kind.equals("channels")) {
            return channelAnswers(workload);
        }

        return levelAnswers(workload);
    }

    private static ArrayNode windowAnswers(JsonNode workload) {
        return windowAnswers(workload, false);
    }

    /**
     * @param oneMomentPerWorkload ignore each case's own moment and use the workload's, which
     *     is what the timing run does on both sides so that neither pays for installing a
     *     clock inside the loop
     */
    private static ArrayNode windowAnswers(JsonNode workload, boolean oneMomentPerWorkload) {
        ArrayNode rows = MAPPER.createArrayNode();
        for (JsonNode one : workload.get("cases")) {
            ObjectNode row = rows.addObject();
            row.put("step", one.get("step").asInt());
            Instant now = Instant.parse(oneMomentPerWorkload
                    ? text(workload, "now", "2026-03-10T12:00:00Z")
                    : text(one, "now", text(workload, "now", null)));
            String zoneName = text(one, "zone", text(workload, "zone", null));
            ZoneId zone = zoneName == null ? ZoneId.of("UTC") : ZoneId.of(zoneName);
            try {
                long millis = io.akka.novu.domain.DigestWindow.of(
                        config(one.get("config")), Map.of(), override(one.get("override")), now, zone);
                row.put("millis", millis);
                row.putNull("rejection");
            } catch (DigestConfigurationException e) {
                row.putNull("millis");
                row.put("rejection", e.getMessage());
            }
        }

        return rows;
    }

    private static ArrayNode sequenceAnswers(JsonNode workload, JsonNode batches) {
        ArrayNode rows = MAPPER.createArrayNode();
        DigestConfig config = config(workload.get("config"));
        String zoneName = text(workload, "zone", null);
        ZoneId zone = zoneName == null ? ZoneId.of("UTC") : ZoneId.of(zoneName);
        DigestGroupState state = DigestGroupState.empty();
        int step = 0;

        for (JsonNode batch : batches) {
            for (JsonNode raw : batch) {
                DigestEvent event = event(raw);
                DigestGroup.Decision decision =
                        DigestGroup.offer(state, event, config, event.createdAt(), zone);
                state = decision.state();
                ObjectNode row = rows.addObject();
                row.put("step", step++);
                row.put("eventId", event.eventId());
                row.put("outcome", decision.outcome().name());
                if (decision.mergedInto() == null) {
                    row.putNull("mergedInto");
                } else {
                    row.put("mergedInto", decision.mergedInto());
                }
                row.putNull("master");
                row.putNull("reachFromMillis");
            }
            // Every row in a sequence carries the same fields: a comparison that takes its
            // field list from the first row cannot see a field a later row introduces.
            ObjectNode delivery = rows.addObject();
            delivery.put("step", step++);
            delivery.putNull("eventId");
            delivery.put("outcome", "DELIVERED");
            delivery.putNull("mergedInto");
            if (state.open()) {
                delivery.put("master", state.masterEventId());
                delivery.put("reachFromMillis",
                        DigestGroup.reachFrom(state).toEpochMilli() - state.openedAt().toEpochMilli());
                state = DigestGroup.deliver(state).state();
            } else {
                delivery.putNull("master");
                delivery.putNull("reachFromMillis");
            }
        }

        return rows;
    }

    private static ArrayNode arrivalOrderAnswers(JsonNode workload) {
        ArrayNode rows = MAPPER.createArrayNode();
        List<JsonNode> events = new ArrayList<>();
        workload.get("rows").forEach(events::add);

        int orders = Math.min(6, events.size());
        for (int k = 0; k < orders; k++) {
            List<JsonNode> rotated = new ArrayList<>(events.subList(k, events.size()));
            rotated.addAll(events.subList(0, k));

            ArrayNode batch = MAPPER.createArrayNode();
            rotated.forEach(batch::add);
            ArrayNode batches = MAPPER.createArrayNode();
            batches.add(batch);
            ArrayNode answer = sequenceAnswers(workload, batches);

            StringBuilder order = new StringBuilder();
            StringBuilder outcomes = new StringBuilder();
            String master = null;
            for (JsonNode row : answer) {
                if (row.get("outcome").asText().equals("DELIVERED")) {
                    master = row.get("master").isNull() ? null : row.get("master").asText();
                    continue;
                }
                if (order.length() > 0) {
                    order.append(',');
                    outcomes.append(',');
                }
                order.append(row.get("eventId").asText());
                outcomes.append(row.get("outcome").asText());
            }
            ObjectNode row = rows.addObject();
            row.put("step", k);
            row.put("order", order.toString());
            row.put("outcomes", outcomes.toString());
            if (master == null) {
                row.putNull("master");
            } else {
                row.put("master", master);
            }
        }

        return rows;
    }

    private static ArrayNode channelAnswers(JsonNode workload) {
        ArrayNode rows = MAPPER.createArrayNode();
        List<Channel> active = new ArrayList<>();
        workload.get("activeChannels").forEach(name -> active.add(Channel.valueOf(name.asText())));

        Map<PreferenceSource, Map<Channel, Boolean>> stated = new LinkedHashMap<>();
        workload.get("sources").fields().forEachRemaining(entry -> {
            Map<Channel, Boolean> flags = new LinkedHashMap<>();
            entry.getValue().fields().forEachRemaining(
                    flag -> flags.put(Channel.valueOf(flag.getKey()), flag.getValue().asBoolean()));
            stated.put(PreferenceSource.valueOf(entry.getKey()), flags);
        });

        boolean enabled = workload.get("enabled").asBoolean();
        ChannelSelection.Result resolved =
                ChannelSelection.resolve(ChannelSelection.candidates(active), stated);

        ObjectNode row = rows.addObject();
        row.put("step", 0);
        row.put("enabled", enabled);
        ObjectNode channels = row.putObject("channels");
        resolved.channels().forEach((channel, on) -> channels.put(channel.name(), on));
        ObjectNode reasons = row.putObject("reasons");
        resolved.reasons().forEach((channel, source) -> reasons.put(channel.name(), source.name()));
        ArrayNode sends = row.putArray("sends");
        resolved.channels().keySet().stream()
                .filter(channel -> ChannelSelection.sends(enabled, resolved.channels(), channel))
                .forEach(channel -> sends.add(channel.name()));

        return rows;
    }

    private static ArrayNode levelAnswers(JsonNode workload) {
        ArrayNode rows = MAPPER.createArrayNode();
        for (JsonNode one : workload.get("cases")) {
            JsonNode levels = one.get("levels");
            PreferenceMerge.Result merged = PreferenceMerge.merge(new PreferenceMerge.Levels(
                    preference(levels.get("WORKFLOW_RESOURCE")),
                    preference(levels.get("WORKFLOW_USER")),
                    preference(levels.get("SUBSCRIBER_GLOBAL")),
                    preference(levels.get("SUBSCRIBER_WORKFLOW"))),
                    one.get("excludeSubscriberPreferences").asBoolean());

            ObjectNode row = rows.addObject();
            row.put("step", one.get("step").asInt());
            row.put("enabled", merged.enabled());
            ObjectNode channels = row.putObject("channels");
            merged.channels().forEach((channel, on) -> channels.put(channel.name(), on));
        }

        return rows;
    }

    /** The same work as {@link #answersFor}, with one moment per workload. See the timing run. */
    private static ArrayNode timingWorkFor(JsonNode workload) {
        if (workload.path("kind").asText("").equals("window")) {
            return windowAnswers(workload, true);
        }

        return answersFor(workload);
    }

    // ---- timings -----------------------------------------------------------------

    /** Repetitions inside one timed window, sized so a window runs for tens of milliseconds. */
    private static final int WINDOW_REPETITIONS = 20_000;
    private static final int WARM_UP = 50_000;

    private static ObjectNode timings(JsonNode workloads) {
        // Warm the JIT across every workload before timing any of them, so the first one
        // timed does not pay for the others' compilation.
        for (int i = 0; i < WARM_UP / Math.max(1, workloads.size()); i++) {
            for (JsonNode workload : workloads) {
                timingWorkFor(workload);
            }
        }

        ObjectNode timing = MAPPER.createObjectNode();
        for (JsonNode workload : workloads) {
            long started = System.nanoTime();
            for (int i = 0; i < WINDOW_REPETITIONS; i++) {
                timingWorkFor(workload);
            }
            long elapsed = System.nanoTime() - started;
            ObjectNode row = timing.putObject(workload.get("name").asText());
            row.put("repetitions", WINDOW_REPETITIONS);
            row.put("windowNanos", elapsed);
            row.put("nanosPerRun", (double) elapsed / WINDOW_REPETITIONS);
        }

        return timing;
    }

    // ---- reading a workload ------------------------------------------------------

    private static String text(JsonNode node, String field, String fallback) {
        JsonNode value = node.get(field);

        return value == null || value.isNull() ? fallback : value.asText();
    }

    private static DigestEvent event(JsonNode raw) {
        return new DigestEvent(
                raw.get("eventId").asText(),
                raw.get("notificationId").asText(),
                Instant.parse(raw.get("createdAt").asText()),
                Map.of());
    }

    private static DelayOverride override(JsonNode raw) {
        if (raw == null || raw.isNull()) {
            return null;
        }
        JsonNode amount = raw.get("amount");

        return new DelayOverride(
                amount.isNumber() ? (Object) amount.asLong() : (Object) amount.asText(),
                raw.get("unit").asText());
    }

    private static DigestConfig config(JsonNode raw) {
        if (raw == null || raw.isNull()) {
            return null;
        }
        DigestConfig.Builder builder = DigestConfig.builder(DigestKind.valueOf(raw.get("kind").asText()));
        if (raw.has("amount")) {
            builder.amount(raw.get("amount").asLong());
        }
        if (raw.has("unit")) {
            builder.unit(DigestUnit.valueOf(raw.get("unit").asText()));
        }
        if (raw.has("backoff")) {
            builder.backoff(raw.get("backoff").asBoolean());
        }
        if (raw.has("backoffAmount")) {
            builder.backoffAmount(raw.get("backoffAmount").asLong());
        }
        if (raw.has("backoffUnit")) {
            builder.backoffUnit(DigestUnit.valueOf(raw.get("backoffUnit").asText()));
        }
        if (raw.has("digestKey")) {
            builder.digestKey(raw.get("digestKey").asText());
        }
        if (raw.has("digestValue")) {
            builder.digestValue(raw.get("digestValue").asText());
        }
        if (raw.has("timed")) {
            builder.timed(timed(raw.get("timed")));
        }

        return builder.build();
    }

    private static TimedConfig timed(JsonNode raw) {
        List<DayOfWeek> weekDays = new ArrayList<>();
        if (raw.has("weekDays")) {
            raw.get("weekDays").forEach(day -> weekDays.add(DayOfWeek.valueOf(day.asText())));
        }
        List<Integer> monthDays = new ArrayList<>();
        if (raw.has("monthDays")) {
            raw.get("monthDays").forEach(day -> monthDays.add(day.asInt()));
        }

        return new TimedConfig(
                text(raw, "atTime", null),
                weekDays,
                monthDays,
                raw.has("monthlyType") ? MonthlyType.valueOf(raw.get("monthlyType").asText()) : null,
                raw.has("ordinal") ? Ordinal.valueOf(raw.get("ordinal").asText()) : null,
                raw.has("ordinalValue") ? OrdinalValue.valueOf(raw.get("ordinalValue").asText()) : null,
                text(raw, "cronExpression", null));
    }

    private static WorkflowPreference preference(JsonNode raw) {
        if (raw == null || raw.isNull()) {
            return null;
        }
        Map<Channel, Boolean> channels = new LinkedHashMap<>();
        raw.get("channels").fields().forEachRemaining(
                entry -> channels.put(Channel.valueOf(entry.getKey()), entry.getValue().asBoolean()));

        return new WorkflowPreference(
                raw.has("enabled") ? raw.get("enabled").asBoolean() : null,
                raw.has("readOnly") ? raw.get("readOnly").asBoolean() : null,
                channels);
    }

    private BenchRunner() {
    }
}
