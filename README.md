# novu-akka

Holds a stream of notifications for one person until a waiting period ends, groups the ones
that belong together into a single message, and decides which ways of reaching them it goes
out on.

A port of [novuhq/novu](https://github.com/novuhq/novu) onto **Akka**, built with **Akka
Specify**.

---

## Where it came from

novuhq/novu is an open-source system for sending notifications to people by email, text
message, in-app inbox, chat and push. It was ported to derive a specification format
precise enough to regenerate a system on a different stack — the port is the vehicle, the
specification is the deliverable.

The part rebuilt here is the digest engine: the piece that stops someone getting forty
separate messages about the same order, and instead sends one message when the noise has
stopped.

The specifications the port was generated from are in
[TylerJewell/akka-specify-harness](https://github.com/TylerJewell/akka-specify-harness)
under `novu-port/`.

---

## novuhq/novu → this port

📉 706 TypeScript lines → **1,333 Java lines**<br>
📁 10 files → **31 files**<br>
🖥️ 3 services → **1 service**<br>
⚡ not measured → **2.2** seconds, cold start<br>
🧮 100.50 → **3.90** microseconds, working out one waiting period in each of six units<br>
🧮 127.09 → **5.11** microseconds, working out when a scheduled message next goes out<br>
🧮 94.31 → **1.00** microseconds, settling four levels of a person's stated choices<br>
🎯 197 compared answers, 189 → **189** in agreement

Full method and the numbers that did *not* make this list:
[`bench/REPORT.md`](https://github.com/TylerJewell/akka-specify-harness/blob/main/novu-port/bench/REPORT.md).

The three microsecond lines are the original with its own logging silenced. As it ships,
the first of them is 100.50 against 3.90 because working out a waiting period writes four
log lines every time it converts a unit, whatever logging is switched on — and that
writing is 96.8% of what the original spends there. Timing it with the writing left in
would have said 26 times faster and meant nothing.

---

## What it took to build

⏱️ **1.8 hours** from the first command to the published repository, **1.8** of them active<br>
💬 **433** exchanges with the model<br>
✍️ **449,269** tokens written by the model, **119,928,922** counting everything sent and re-sent<br>
🙋 **0** questions to a human<br>
🧪 **84** tests

```bash
python toolkit/tokens.py --port novu    # turns, tokens, elapsed and active time
```

The record of every question, and where the time went, is in
[`port-log/`](https://github.com/TylerJewell/akka-specify-harness/tree/main/port-log).

---

## What it does

From the specification:

- **The first notification for a subject starts the waiting period; every later one for the
  same subject joins it.** One message goes out at the end instead of one per event.
- **What counts as the same subject is four things and no others: the environment, the
  workflow, the person, and the value being grouped on.** Change any one of them and a
  separate message is being built.
- **A waiting period is measured from the notification that started it, not from the
  moment the message goes out.** Something that arrived shortly before the first
  notification is still inside the group.
- **In backoff mode the first notification of a burst is sent on its own, undigested.** A
  single event reaches the person immediately, and only a second one within the look-back
  starts a group.
- **A way of reaching someone that the workflow has no active step for is absent, not
  switched off.** Nothing anyone states afterwards can turn it back on.
- **When several sources state a preference for the same channel, the last one to state it
  wins**, and the person themselves is always last.
- **A message that has gone out is never sent again**, even when a later group's waiting
  period still reaches back over it.

---

## Design decisions

**One group per subject, addressed by name.** In the original, "is a message already being
built for this?" is a search across every piece of pending work in a shared database; here
the four things that decide the answer are the group's own name, so the question is
answered by looking at that group and nothing else. Two groups can never see each other's
notifications, and two events for the same group are never handled at the same time.

**The group remembers its own deadline.** The original keeps the waiting period in a queue
outside the code this port rebuilds, and nothing in that code says what becomes of it if
the machine holding it stops; here the deadline is part of what the group durably knows.
A group that was interrupted sends its message late rather than never.

**The same notification offered twice is decided once.** A caller that retries, or a queue
that delivers the same thing again, would otherwise put the notification into the message
twice. The second offer is answered from the record and nothing is written down.

**The group forgets its oldest sent notifications.** A group that is kept close to the
machines that use it has a size limit that a database table does not, so once a group
remembers five hundred notifications it starts dropping the oldest ones it has already
sent. What it never drops is anything still inside some waiting period.

**Every answer is worked out at a stated moment in a stated part of the world.** A system
that reads the machine's own clock and the machine's own region gives different answers on
different machines, which cannot be compared with anything. Callers say when and where, and
get the same answer every time.

---

## Running it — the short path

You do not need Java, Maven, or the Akka CLI installed. Akka Specify installs them for you.

**1. Install Akka Specify** in Claude Code:

```
/plugin marketplace add akka/ai-marketplace
/plugin install akka@akka-ai-marketplace
```

Restart Claude Code when it asks.

**2. Give it this prompt:**

> Clone https://github.com/TylerJewell/novu-akka into a new directory and open it.
> Then run /akka:setup to install everything this project needs, and /akka:build to
> compile it, run the tests, and start it locally.

**3. Open** http://localhost:9053.

---

## Running it — the developer path

### Requirements

- Java 21 or newer
- Maven 3.9 or newer
- An Akka download token — run `akka code token` once

### Start the service

```bash
mvn compile
akka local run
```

The service starts on **port 9053**.

### Ask it something

```bash
curl -s localhost:9053/digest/offer -H 'content-type: application/json' -d '{
  "environmentId": "env-1", "workflowId": "tpl-1", "subscriberId": "sub-1",
  "event": {"eventId": "e1", "notificationId": "n1",
            "createdAt": "2026-03-10T12:00:00Z", "payload": {"orderId": "order-7"}},
  "config": {"kind": "REGULAR", "amount": 5, "unit": "MINUTES",
             "digestKey": "orderId", "digestValue": "order-7"},
  "now": "2026-03-10T12:00:00Z", "zone": "UTC"}'
```

```json
{"outcome":"CREATED","groupId":"ZW52LTE.dHBsLTE.c3ViLTE.b3JkZXItNw.",
 "mergedInto":null,"deadline":"2026-03-10T12:05:00Z","windowMillis":300000,
 "configurationRejection":null}
```

Send the same request again with a different `eventId` and the answer is `MERGED`.

### What it answers

| Request | What it does |
|---|---|
| `POST /digest/offer` | Offer a notification. Answers whether it started a group, joined one, or is going out on its own |
| `GET /digest/groups/{id}` | Read a group: what is holding it, when it goes out, what it currently holds |
| `POST /digest/groups/{id}/deliver` | Send a group's message now instead of waiting |
| `POST /digest/window` | Work out a waiting period on its own, with no group behind it |
| `POST /digest/preferences` | State what one person wants, for everything or for one workflow |
| `POST /digest/channels` | Ask which ways of reaching someone survive, and why each one did |

---

## Configuration

| Variable | Default | Notes |
|---|---|---|
| `HTTP_PORT` | 9053 | Set in `application.conf` as `akka.javasdk.dev-mode.http-port` |

No model provider is used: nothing here calls a language model.

---

## Where it differs from novuhq/novu

Everything not listed here behaves the same way on purpose, including the parts that look
like mistakes.

- **Working out when a scheduled message next goes out, in a named part of the world.**
  novu accepts the name of a region and, across thirty combinations of region, time of day
  and moment of asking, answers all six that name no region and refuses sixteen of the
  twenty-four that name one — every combination for Tokyo among them. This port answers
  all thirty, because a system that cannot say when someone's nine in the morning falls
  where they live is not doing what the setting asks of it.
- **How a schedule is worked out.** novu hands the recurrence rules to a general-purpose
  calendar library. This port walks forward through candidate moments itself and stops at
  the first one that fits, which covers what novu's own settings screen can express — a
  time of day, named days of the week, named days of the month, and the first through
  fifth or last occurrence of something in a month — and no more. A schedule written
  outside that range is refused rather than guessed at.
- **Schedules written as a cron expression.** novu accepts one and passes it to a
  different scheduler entirely. This port refuses it, and says so, because the scheduler
  it would have been passed to is outside what was rebuilt.
- **How long a group remembers.** novu keeps every piece of pending work in a shared
  database with no limit. This port keeps a group's history with it, and a group that is
  replicated between regions has a size limit, so once five hundred notifications have
  accumulated the oldest already-sent ones are dropped. A group holding more than five
  hundred unsent notifications is never trimmed.
- **The same notification offered twice.** novu's pieces of work each have an identity and
  the same one is not processed twice, though the code rebuilt here does not itself check.
  This port answers a repeat offer from what it already decided and writes nothing, because
  the alternative is a person's message quietly containing the same thing twice.
- **What happens across a restart.** novu keeps a waiting period in a queue outside the
  code rebuilt here, and that code states no rule for a machine stopping mid-wait. This
  port keeps the deadline with the group, so an interrupted group sends late rather than
  never.
- **Which source of a stated preference wins.** novu's code contains a comparison meant to
  skip a source, written between a source's name and a true-or-false value, so it never
  matches and the source is never skipped; the effect is that the last source to state
  something always wins. This port implements last-one-wins, which is what novu does. It
  does not implement the skip, because nothing reaches it and there is no way to tell what
  it was meant to do.
- **When a badly configured group is refused.** In both systems a group is marked as
  started before its settings are checked, so a refusal leaves a group that was started
  and holds nothing. This port keeps that order, because someone reading the group in
  between sees the same thing on both sides.
- **A region name neither system knows.** novu's behaviour here was **not checked**. This
  port answers `Unknown time zone` rather than failing, because a name is something a
  caller can correct.
- **Whether a notification already sent is left out of a later group.** Both systems
  intend to leave it out. novu does so through a database query that this comparison could
  not drive honestly, so this behaviour is **not checked** against novu — only against
  this port's own tests.
- **Which ways of reaching someone a message finally goes out on.** The final check in
  novu is a private method inside a component that pulls in its whole application, so it
  was restated rather than driven. The two restatements are identical, so their agreement
  is **not evidence** that the two systems agree.
- **What gets delivered.** Nothing here renders a message or contacts a provider. That is
  outside what was rebuilt rather than a difference in how it behaves.

---

## Licence

novuhq/novu is MIT licensed outside its `enterprise/packages` directory, © Noti-fire Apps
Ltd. Everything read for this port is outside that directory. This port reimplements the
behaviour without copied source, apart from the refusal messages listed in
`ACKNOWLEDGEMENTS.md`; see that file.
