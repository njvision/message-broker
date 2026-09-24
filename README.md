# messager — message broker agent

A message agent and the distributed participants around it. **One sender** that
picks its destination per message, and **a list of receivers** it can reach — by
group, or one by name. Participants know the agent's address and a logical
destination, and nothing about each other.

Payload is **JSON** end to end. Transport between participants and the agent is
**HTTP over TCP/IP**; inside the agent, channels are RabbitMQ queues.

## Modules

| Module | What it is |
|---|---|
| `messager-broker` | the agent: receives, validates, routes, stores, delivers |
| `messager-simulator` | the participants: one jar that runs as a sender, a receiver, or both |

## Addressing

One `target` field. Two questions decide where a message goes — **one group or all of them**, and **one member or all of them** — which gives four plural forms plus addressing a single receiver by name:

| Written as | Means | Who gets it |
|---|---|---|
| `workers` or `group:workers` | a group | **one** of the group's receivers, chosen by the agent |
| `every:workers` or `workers:*` | every member of a group | **all** of that group's receivers, and nobody else |
| `@r2` or `receiver:r2` | one receiver | **only** `r2` |
| `all` (or empty) | broadcast | **one member of every group** |
| `every:all` or `every:*` | broadcast to all members | **every receiver there is** |

A bare name is a group, because addressing a group is the common case and the
other three are exceptions that ask for the extra mark.

The three plural forms are easy to confuse, so with `r1`/`r2` in `workers` and
`r3` in `reports`:

|  | one member | every member |
|---|---|---|
| **one group** | `workers` | `every:workers` |
| **all groups** | `all` | `every:all` |

With `r1`/`r2` in `workers` and `r3` in `reports`, measured on a running stack:

| Target | r1 | r2 | r3 |
|---|---|---|---|
| `workers` | one of them | one of them | — |
| `every:workers` | ✓ | ✓ | — |
| `all` | one of them | one of them | ✓ |
| `every:all` | ✓ | ✓ | ✓ |

`all` is the one that surprises: it gives **each group** a copy, but inside a
group a single receiver still takes it. To reach literally everybody, use
`every:all`.

**No group names are configured in the agent.** A group comes into being when
the first receiver of it registers, so the topology is whatever the participants
say it is. `GET /api/messages/targets` reports what can be addressed right now.

## The protocol

```
                         ┌─────────────────────── the agent ───────────────────────┐
sender app               │                                                         │        receiver app
  POST /api/messages ────┼──▶ validate ──▶ route ──▶ messager.exchange (topic)      │
      target: workers    │                  │                                       │
      target: every:...  │                  ├─ messager.workers ─▶ queue.workers ┐   │
      target: @r2        │                  ├─ messager.all     ─▶ every queue   │   │
      target: all        │                  │                                    │   │
                         │                  └─ messager.#       ─▶ queue.audit   │   │
                         │                                                       ▼   │
                         │                          validate ─▶ store ─▶ deliver ─────┼──▶ POST {callbackUrl}
                         │                              │                             │
                         │                              ▼                             │
                         │                      queue.invalid          retry ×3, then
                         │                                             (group only) next
                         │                                             receiver, then queue.dlq
                         └───────────────────────────────────────────────────────────┘
   POST /api/receivers {name, group, callbackUrl} ◀──── receiver registers on startup
```

A message addressed to one receiver, or to every member of a group, still
travels through the group's queue — there is no private queue per receiver, and
no second queue for fan-out. All three forms put the same message on the same
queue; what differs is a header the delivery step reads: the name of the one
receiver that must get it, or a flag saying every member must. Without either,
the agent picks one itself.

### 1. Message format and channels

* JSON, mapped to `ChatMessage` (`id`, `sender`, `content`, `sentAt`).
* One unidirectional channel per group, created when its first receiver
  registers, and removable at runtime.
* Three infrastructure channels: `audit` (a copy of everything), `dlq`
  (undeliverable) and `invalid` (rejected by validation).

### 2. Communication structure

* **One-to-one, by group** — `target: "workers"` reaches only that group's queue;
  `reports` never sees it.
* **One-to-one, by name** — `target: "@r2"` reaches `r2` and nobody else, even
  though `r1` sits on the same queue.
* **Competing consumers inside a group** — the queue runs
  `messager.concurrency-per-group` consumers, and the agent hands the message to
  one of the group's registered receivers, rotating between them.
* **One-to-many inside a group** — `target: "every:workers"` gives every member
  of that group its own copy, while leaving other groups out.
* **One-to-many across groups** — `target: "all"` gives each group a copy, which
  one of its members takes; `target: "every:all"` gives a copy to every receiver
  in the system.

### 3. Delivery policies

| Case | What the agent does |
|---|---|
| Sender sends a malformed message | 400 with the list of violations; it never enters a queue |
| Sender names a group or receiver that does not exist | 400 from the routing layer, before anything is published |
| Invalid message found on a queue | parked in the **Invalid Message Channel** — retrying would fail forever |
| Receiver is slow or answers non-2xx | **3 attempts** with doubling backoff |
| A **group** message's receiver keeps failing | **fails over** to the next receiver of the group |
| A **named** receiver keeps failing | **no failover** — handing it to somebody else is not what the sender asked for; it dead-letters |
| One member fails on an `every:` send | the others still get it; the message dead-letters once, naming who missed it (`PARTIALLY_DELIVERED`) |
| Named receiver unregistered since publishing | dead-lettered, with that as the reason |
| No receiver of the group accepts it | **Dead Letter Channel** (`messager.queue.dlq`) with the reason in headers |
| Message matches no queue | returned to the agent (`mandatory` + returns callback) and dead-lettered |
| RabbitMQ is down or refuses the publish | publisher confirms time out, the sender gets **503**, nothing is silently lost |
| Consumer throws | 3 redeliveries, then RabbitMQ dead-letters it via `messager.exchange.dlx` |

The dead-letter and invalid queues are drained into the agent's own store, so
`GET /api/messages/dead-letters` and `/invalid` show what failed and why.

### 4. Storage

* **Transient** — `MessageStore` holds one `BoundedMessageChannel` per queue:
  a `ConcurrentLinkedDeque` with an `AtomicInteger` size, so the bound holds
  while every consumer thread writes at once. `BoundedMessageChannelTest` asserts
  that under 8 threads × 500 messages.
* `GET /api/messages/channels` reports buffered / capacity / received / evicted
  per channel.

### 5. Routing

`MessageTarget` parses what the sender wrote; `MessageRouter` turns it into a
routing key plus the delivery instruction that travels with the message — a
named recipient, an every-member flag, or neither. That is the only place that
knows how a destination becomes a queue, and the only place a nonexistent group
or receiver is caught, before anything is published.

## Patterns applied

Message Channel · Publish-Subscribe Channel · Point-to-Point Channel ·
Competing Consumers · **Recipient List** (`every:` fan-out) ·
Wire Tap (the audit queue) · **Invalid Message Channel** ·
**Dead Letter Channel** · Durable Subscriber · Message Endpoint (receiver
registration and naming)

## Build and run

Needs JDK 21+. The jars are built on the host, then copied into the images:

```bash
./mvnw -DskipTests package
docker compose up -d --build
docker compose logs -f broker
```

That starts RabbitMQ, the agent, **one sender** and **three receivers**: `r1` and
`r2` in group `workers`, `r3` in group `reports`. The groups exist because those
receivers registered — nothing declares them.

Stop / clean:

```bash
docker compose down          # keep broker data
docker compose down -v       # drop the rabbitmq volume too
```

| Service | Port | Role |
|---|---|---|
| `rabbitmq` | 15672 | management UI (guest/guest) |
| `broker` | 8080 | the agent |
| `sender` | 9200 | the sending participant |
| `receiver-1` | 9101 | receiver `r1`, group `workers` |
| `receiver-2` | 9102 | receiver `r2`, group `workers` |
| `receiver-3` | 9103 | receiver `r3`, group `reports` |
| `receiver-4` | 9104 | receiver `r4`, group `archive` — profile `extra`, not started by default |

Nothing above is baked into the applications: change `RECEIVER_NAME` and
`RECEIVER_GROUP` in `docker-compose.yml` and the topology follows.

## Try it

[`DEMO.md`](DEMO.md) walks through the whole thing scenario by scenario. The same
scenarios are in [`postman/`](postman/) as a Postman collection with assertions
(`npx newman run postman/messager.postman_collection.json`).

The short version — what can be addressed right now:

```bash
curl http://localhost:8080/api/messages/targets
curl http://localhost:9200/targets              # the sender asks the same thing
```

Send to a group, to one receiver, to everyone:

```bash
curl -X POST 'http://localhost:9200/send/group/workers?content=for anyone'
curl -X POST 'http://localhost:9200/send/everyone/workers?content=for all of workers'
curl -X POST 'http://localhost:9200/send/to/r1?content=for r1 only'
curl -X POST 'http://localhost:9200/send?target=all&content=one per group'
curl -X POST 'http://localhost:9200/send?target=every:all&content=for literally everyone'
```

Or straight to the agent:

```bash
curl -X POST http://localhost:8080/api/messages \
  -H 'Content-Type: application/json' \
  -d '{"sender":"alice","content":"for r1 only","target":"@r1"}'
```

Check what each receiver actually got:

```bash
curl http://localhost:9101/received      # r1
curl http://localhost:9102/received      # r2 - never sees @r1 messages
curl http://localhost:9103/received      # r3 - broadcasts only
```

Watch retry, failover and the dead letter channel:

```bash
curl -X POST http://localhost:9101/fail        # r1 refuses everything

# addressed to the group: fails over to r2
curl -X POST 'http://localhost:9200/send/group/workers?content=anyone will do'
# addressed to r1 by name: no failover, goes to dead letters
curl -X POST 'http://localhost:9200/send/to/r1?content=r1 or nobody'

docker compose logs broker | grep Attempt
curl http://localhost:8080/api/messages/dead-letters
curl -X POST http://localhost:9101/heal
```

What the agent itself has stored:

```bash
curl http://localhost:8080/api/messages           # every channel
curl http://localhost:8080/api/messages/workers   # one group
curl http://localhost:8080/api/messages/channels  # size / capacity / evicted
curl http://localhost:8080/api/messages/invalid   # rejected by validation
curl http://localhost:8080/actuator/health        # includes RabbitMQ
```

## Receivers and groups at runtime

A receiver registers itself; the group is created if it is new.

```bash
curl http://localhost:8080/api/receivers

curl -X POST http://localhost:8080/api/receivers \
  -H 'Content-Type: application/json' \
  -d '{"name":"r9","group":"archive","callbackUrl":"http://receiver-9:9100/receive"}'

curl -X DELETE http://localhost:8080/api/receivers/r9     # name or id
```

Names are addresses, so they are unique across the agent; registering a name that
is taken replaces the old entry, which is how a receiver that restarts on a new
port reclaims its own name.

Groups can also be managed directly, for the cases where you want a queue to
exist before any receiver shows up:

```bash
curl http://localhost:8080/api/groups
curl -X POST 'http://localhost:8080/api/groups/archive?consumers=3'
curl -X PUT 'http://localhost:8080/api/groups/archive/consumers?consumers=5'
curl -X DELETE 'http://localhost:8080/api/groups/archive?deleteQueue=true'
```

Group names must match `[a-zA-Z0-9][a-zA-Z0-9_-]{0,63}` and may not be `all`,
`audit`, `dlq` or `invalid` — the name becomes a queue name and a topic routing
key, so a dot in it would quietly change which bindings match.

Dropping a group without `deleteQueue` unbinds it but keeps the queue, so
messages already buffered survive until the group is added back. Dropping it
also unregisters its receivers.

## Configuration

Everything is read from env vars with local defaults.

### Agent (`messager-broker`)

| Env var | Default | Meaning |
|---|---|---|
| `SERVER_PORT` | `8080` | HTTP port |
| `RABBITMQ_HOST` | `localhost` (`rabbitmq` in compose) | broker host |
| `RABBITMQ_PORT` | `5672` | AMQP port |
| `RABBITMQ_USER` / `RABBITMQ_PASSWORD` | `guest` | credentials |
| `RABBITMQ_VHOST` | `/` | virtual host |
| `MESSAGER_GROUPS` | *(empty)* | groups declared up front; normally none — they follow the receivers |
| `MESSAGER_BROADCAST_KEY` | `all` | target name that reaches every group |
| `MESSAGER_CONCURRENCY` | `2` | competing consumers per group |
| `MESSAGER_EXCHANGE` | `messager.exchange` | topic exchange name |
| `MESSAGER_AUDIT_QUEUE` | `messager.queue.audit` | queue mirroring every message |
| `MESSAGER_HISTORY_LIMIT` | `100` | messages kept per in-memory channel |
| `MESSAGER_DELIVERY_ATTEMPTS` | `3` | attempts per receiver |
| `MESSAGER_DELIVERY_BACKOFF` | `500ms` | wait between attempts, doubling |
| `MESSAGER_DELIVERY_TIMEOUT` | `3s` | how long one callback may take |
| `MESSAGER_CONFIRM_TIMEOUT` | `5s` | how long to wait for the publish confirm |

### Participants (`messager-simulator`)

| Env var | Default | Meaning |
|---|---|---|
| `SERVER_PORT` | `9100` | HTTP port |
| `BROKER_URL` | `http://localhost:8080` | where the agent is |
| `RECEIVER_ENABLED` | `false` | run as a receiver |
| `RECEIVER_NAME` | *(host name)* | the address senders write as `@name` |
| `RECEIVER_GROUP` | `default` | group joined; created by the agent if new |
| `RECEIVER_CALLBACK_URL` | *(derived)* | where the agent should push; empty means own host:port |
| `SENDER_ENABLED` | `false` | run as a sender |
| `SENDER_NAME` | `sender` | goes into the message |
| `SENDER_DEFAULT_TARGET` | `all` | only where the timer sends; every `POST /send` can override it |
| `SENDER_AUTO` | `false` | keep sending on a timer |
| `SENDER_INTERVAL` | `5s` | how often |

Host ports for the compose services: `APP_PORT`, `RABBITMQ_AMQP_PORT`,
`RABBITMQ_UI_PORT`, `SENDER_PORT`, `RECEIVER_1_PORT` … `RECEIVER_4_PORT`.
Override via a `.env` next to `docker-compose.yml`, or inline:

```bash
APP_PORT=8081 docker compose up -d
```

## Lombok

`ChatMessage` and `SendRequest` are Lombok classes (`@Data`, `@NoArgsConstructor`,
`@AllArgsConstructor`, `@Builder`) — the no-arg constructor plus setters is what
Jackson uses to rebuild the payload on the consumer side. Beans use
`@RequiredArgsConstructor` for injection and `@Slf4j` for logging. The annotation
processor is wired in the parent `pom.xml` (`maven-compiler-plugin` →
`annotationProcessorPaths`), and Lombok is excluded from the fat jars.

## Tests

```bash
./mvnw test
```

17 tests:

* `BoundedMessageChannelTest` — the transient channel keeps its bound under
  concurrent writers (no broker needed).
* `MessageRoundTripTest` — group routing, broadcast, the audit copy, a message
  that fails validation landing in the invalid channel, and an unknown target
  rejected before publishing.
* `ReceiverDeliveryTest` — a registered receiver gets messages pushed to it; a
  message addressed by name reaches only that receiver; an `every:` send reaches
  all members of the group and no one outside it while a plain group send still
  reaches exactly one; `every:all` reaches all three receivers while a plain
  broadcast reaches one per group; a **named** receiver that refuses dead-letters without
  failover while a **group** message does fail over.

Integration tests start a real broker with Testcontainers, so Docker has to be
running — and with enough headroom, since a loaded Docker makes the container's
startup wait time out.

## Running outside Docker

Broker in Docker, the rest from the IDE / Maven:

```bash
docker compose up -d rabbitmq
./mvnw -pl messager-broker spring-boot:run

RECEIVER_ENABLED=true RECEIVER_NAME=r1 RECEIVER_GROUP=workers SERVER_PORT=9101 \
  ./mvnw -pl messager-simulator spring-boot:run

SENDER_ENABLED=true SERVER_PORT=9200 \
  ./mvnw -pl messager-simulator spring-boot:run
```
