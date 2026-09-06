package dm.ai;

import dm.content.RoomDefinition;
import dm.engine.GameEngine;
import dm.model.Combatant;
import dm.model.Event;
import dm.model.Outcome;
import dm.model.Phase;
import dm.state.EventLog;
import dm.state.WorldState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Runs one DM turn as three phases against two different models.
 *
 * <p><b>Phase 1 — mechanics, fast model.</b> Decides tool calls and nothing else; any prose it
 * emits is discarded. Loops until it stops asking for tools, seeing each engine result before
 * choosing the next call. This is the phase the first-feedback budget applies to, because it is
 * what puts dice on the table.
 *
 * <p><b>Phase 2 — narration, strong model.</b> Writes the prose, with the engine's actual results
 * as context. It runs <em>while the dice are still animating</em>, which is what buys it the time
 * to be good rather than merely fast.
 *
 * <p><b>Phase 3 — reconcile, fast model.</b> Hands the finished narration back to the mechanics
 * model and asks what has to become true for it to have been true. This is what stops the writer
 * describing a creature nobody spawned, and equally what lets it decide the fight starts: the
 * strong model has the dramatic judgement and the fast one has the verbs, and this is the wire
 * between them. It runs behind speech the client has already queued, so it is close to free.
 *
 * <p>Narration stays with exactly one model, so the tone cannot drift between turns — the failure
 * {@code ai-dm-system-design.md} §10 warns is audible immediately.
 *
 * <p>Context is a window of recent turns plus the projection of established facts.
 */
public final class DmService {

    private static final Logger log = LoggerFactory.getLogger(DmService.class);

    /** Bounded so a confused model cannot spin through tool rounds forever. */
    private static final int MAX_TOOL_ROUNDS = 4;

    /**
     * What the DM is asked at the top of the session. Phrased as a beat to narrate rather than
     * as a request for a room description, because asking a model to "describe the room" gets
     * an estate-agent listing of its contents.
     */
    private static final String OPENING = "The party has just come through the entrance and is "
            + "standing inside. Open the session: what they walk into.";

    /**
     * The directive for a combat beat. Shorter than the standing three-sentence rule on purpose:
     * a fight is a rally, and a narrator who writes a paragraph between swings stops the rally.
     */
    private static final String COMBAT_BEAT = "Narrate this moment of the fight. "
            + "One or two sentences. Do not say whose turn it is. "
            + "Describe only what the facts state — no swing they do not mention, "
            + "no wound they do not report.";

    /** §7: reject, let it retry once, then take the tools away. */
    private static final int REJECTIONS_BEFORE_DEGRADING = 2;

    /**
     * How many turns of transcript the prose model sees. A tuning knob, not a design decision —
     * and one that can now be tested against a recorded session rather than guessed at.
     */
    static final int WINDOW_TURNS = 6;

    private final DmClient toolClient;
    private final DmClient proseClient;
    private final GameEngine engine;
    private final ToolDispatcher dispatcher;
    private final String toolPrompt;
    private final String prosePrompt;
    private final String reconcilePrompt;
    private final TurnMetrics metrics = new TurnMetrics();

    /** The durable conversation: what the player said, and what the narrator said back. */
    private final List<DmClient.ChatMessage> history = new ArrayList<>();

    /** The room is described once per session, not once per reconnect. */
    private final java.util.concurrent.atomic.AtomicBoolean opened =
            new java.util.concurrent.atomic.AtomicBoolean();

    /**
     * One narration at a time, across every path that produces any.
     *
     * <p>Two narrations in flight interleave their sentences into nonsense, and the transcript is
     * a single ordered list with nowhere to put a second voice. Who waits and who gives up is not
     * symmetric: narration the <em>player</em> asked for waits its turn, because dropping it would
     * silently swallow something they typed; narration the <em>engine</em> generated gives up,
     * because a swing narrated ten seconds late is worse than a swing narrated not at all.
     */
    private final java.util.concurrent.locks.ReentrantLock narrating =
            new java.util.concurrent.locks.ReentrantLock();

    public DmService(DmClient toolClient, DmClient proseClient, GameEngine engine,
                     String toolPrompt, String prosePrompt, String reconcilePrompt) {
        this.toolClient = toolClient;
        this.proseClient = proseClient;
        this.engine = engine;
        this.dispatcher = new ToolDispatcher(engine);
        this.toolPrompt = toolPrompt;
        this.prosePrompt = prosePrompt;
        this.reconcilePrompt = reconcilePrompt;
    }

    public String modelId() {
        return toolClient.modelId() + " + " + proseClient.modelId();
    }

    /** Ping both endpoints in the background and shout if either is unhealthy. */
    public void warmCheck() {
        Thread.ofVirtual().name("warm-check").start(() -> {
            check("tools", toolClient);
            check("prose", proseClient);
        });
    }

    private static void check(String role, DmClient client) {
        long ms = client.ping();
        if (ms < 0) {
            log.error("{} model '{}' is NOT RESPONDING", role, client.modelId());
        } else if (ms > 5_000) {
            log.error("{} model '{}' answered a 4-token request in {}ms. It is cold or "
                            + "overloaded — every turn will be unusable. Switch models.",
                    role, client.modelId(), ms);
        } else if (ms > 2_000) {
            log.warn("{} model '{}' is sluggish: {}ms for a 4-token request",
                    role, client.modelId(), ms);
        } else {
            log.info("{} model '{}' healthy: {}ms", role, client.modelId(), ms);
        }
    }

    /**
     * Narrates the room unprompted, before the player has typed anything (§2 step 2).
     *
     * <p>Prose only — there is nothing to adjudicate yet, so the mechanics model is not consulted
     * and the whole turn is one streaming call. Runs at most once per process: the client
     * reconnects on every dropped socket and on every dev-server reload, and a DM that
     * re-describes the room each time would be a bug that reads as a haunting.
     *
     * @param force ignores the once-per-session guard, for iterating on the opening by hand
     */
    public void openScene(TurnSink sink, boolean force) {
        if (!opened.compareAndSet(false, true) && !force) {
            // Still has to close the turn. The client gives the DM the floor the moment the
            // player clicks through the title and waits for this to hand it back — so a reload
            // against a server that has already opened the scene, which is every reload during
            // development, used to leave the input box disabled and a thinking cursor blinking
            // forever. Nothing was wrong except that nobody said the turn was over.
            sink.complete();
            return;
        }

        var failed = new boolean[]{false};
        long started = System.nanoTime();

        narrating.lock();
        try {
            var prose = runProsePhase(OPENING, List.of(), sink, failed, false);
            if (failed[0]) {
                return;
            }

            // The instruction itself stays out of the history: it is stage direction, and replaying
            // it every turn would have the model treating it as something the player said.
            history.add(DmClient.ChatMessage.assistant(prose.text()));

            log.info("OPENING  first token {}ms  total {}ms  {} chars",
                    prose.firstTokenMs(), (System.nanoTime() - started) / 1_000_000,
                    prose.text().length());
        } finally {
            narrating.unlock();
        }
        sink.complete();
    }

    /**
     * Narrates a moment of combat: the enemy's whole turn as one call, or a kill, or a crit.
     *
     * <p>Prose only, like the opening — the engine has already decided everything and there is
     * nothing to adjudicate. The facts arrive as plain sentences from {@code CombatSink.beat};
     * this turns them into something worth hearing and never the other way round.
     *
     * <p><b>One at a time.</b> A second beat arriving mid-narration is dropped rather than queued.
     * The player can click faster than the prose model can write, and two overlapping calls would
     * interleave their sentences into nonsense — while a queue would narrate a swing that landed
     * ten seconds ago. Silence is the better failure.
     *
     * @return whether it ran
     */
    public boolean narrateCombat(List<String> facts, TurnSink sink) {
        if (facts.isEmpty()) {
            return false;
        }
        if (!narrating.tryLock()) {
            log.info("dropped a combat beat, the DM is already speaking: {}", facts);
            return false;
        }

        try {
            var failed = new boolean[]{false};
            long started = System.nanoTime();
            // The player is not talking during an enemy's swing, so an unmarked quotation here
            // is the creature and nothing else. This is the call that needs the seed.
            var prose = runProsePhase(COMBAT_BEAT, facts, sink, failed, true);
            if (failed[0]) {
                return false;
            }

            // Only the reply is kept. The directive is stage direction, and replaying it each
            // turn would have the model treating it as something the player said.
            history.add(DmClient.ChatMessage.assistant(prose.text()));

            log.info("COMBAT   first token {}ms  total {}ms  {} chars  <- {}",
                    prose.firstTokenMs(), (System.nanoTime() - started) / 1_000_000,
                    prose.text().length(), facts);
            sink.complete();
            return true;
        } finally {
            narrating.unlock();
        }
    }

    /**
     * Handles a free-text turn. Blocking by design — each turn runs on its own virtual thread,
     * so the two-phase orchestration reads like ordinary sequential code.
     */
    public void handleFreeText(String actorId, String text, TurnSink sink) {
        engine.log().append(new Event.PlayerSaid(Instant.now(), actorId, text));
        // The other half of the transcript. Without it the log showed everything the DM said and
        // nothing it was answering, which makes a turn that went wrong unreadable after the fact.
        log.info("| {} | {}", actorId, text);

        // Waits rather than gives up. The tool phase runs inside the lock too: it puts dice on
        // the table, and dice landing under someone else's narration is the same collision.
        narrating.lock();
        try {
            handleFreeTextLocked(actorId, text, sink);
        } finally {
            narrating.unlock();
        }
    }

    private void handleFreeTextLocked(String actorId, String text, TurnSink sink) {
        var failed = new boolean[]{false};

        long toolStart = System.nanoTime();
        var mechanics = runToolPhase(text, sink, failed);
        long toolPhaseMs = (System.nanoTime() - toolStart) / 1_000_000;

        if (failed[0]) {
            return;
        }

        long proseStart = System.nanoTime();
        // A creature does not get the benefit of the doubt on an unmarked quotation when the
        // player might also be talking. When they plainly are not, it does.
        boolean playerMightBeSpeaking = soundsLikeSpeech(text);
        var prose = runProsePhase(text, mechanics.results(), sink, failed,
                !playerMightBeSpeaking);
        long proseMs = (System.nanoTime() - proseStart) / 1_000_000;

        if (failed[0]) {
            return;
        }

        history.add(DmClient.ChatMessage.user(text));
        history.add(DmClient.ChatMessage.assistant(prose.text()));

        // Phase 3, and the reason it is worth a third call: see runReconcilePhase.
        long reconcileStart = System.nanoTime();
        int reconciled = runReconcilePhase(prose.text(), mechanics.results(), sink);
        long reconcileMs = (System.nanoTime() - reconcileStart) / 1_000_000;

        metrics.record(new TurnMetrics.TurnShape(
                mechanics.calls(), mechanics.applied(), mechanics.rejected(),
                mechanics.firstFeedbackMs(), toolPhaseMs, proseMs, prose.firstTokenMs(),
                reconcileMs, reconciled));

        // Last, and after the reconcile on purpose: closing the turn is what hands the fight to
        // whoever won initiative, and a fight the reconcile has just started has to exist by then.
        sink.complete();
    }

    // ---- Phase 1: mechanics ----

    private record Mechanics(List<String> results, int calls, int applied, int rejected,
                             long firstFeedbackMs) {
    }

    private Mechanics runToolPhase(String text, TurnSink sink, boolean[] failed) {
        var results = new ArrayList<String>();
        var localRounds = new ArrayList<DmClient.ChatMessage>();
        long phaseStart = System.nanoTime();
        long firstFeedback = -1;
        int calls = 0;
        int applied = 0;
        int rejected = 0;

        // Prose from this phase is thrown away; a separate model does the writing.
        var discard = new DmClient.DmListener() {
            @Override
            public void onTextDelta(String delta) {
            }

            @Override
            public void onError(Throwable error) {
                failed[0] = true;
                sink.error(error);
            }
        };

        for (int round = 0; round < MAX_TOOL_ROUNDS && !failed[0]; round++) {
            // Rebuilt each round: revealing a prop removes it from the legal set.
            var tools = rejected >= REJECTIONS_BEFORE_DEGRADING
                    ? null
                    : ToolSchema.forTurn(engine);
            if (tools == null) {
                break;
            }

            var conversation = new ArrayList<DmClient.ChatMessage>();
            conversation.add(DmClient.ChatMessage.system(toolPrompt + "\n\n" + worldState(false)));
            conversation.addAll(window(history, WINDOW_TURNS));
            conversation.add(DmClient.ChatMessage.user(text));
            conversation.addAll(localRounds);

            var result = toolClient.streamTurn(conversation, tools, discard);
            if (failed[0] || !result.wantsTools()) {
                break;
            }

            localRounds.add(
                    DmClient.ChatMessage.assistantToolCalls(result.text(), result.toolCalls()));

            for (var call : result.toolCalls()) {
                calls++;
                var outcome = dispatcher.dispatch(call);
                engine.log().append(new Event.ToolCallIssued(Instant.now(), Phase.MECHANICS,
                        call.name(), call.argumentsJson(), outcome.ok(), outcome.message()));

                if (outcome.ok()) {
                    applied++;
                    results.add(outcome.message());
                } else {
                    rejected++;
                    log.warn("tool rejected: {}({}) -> {}",
                            call.name(), call.argumentsJson(), outcome.message());
                }

                boolean visible = !outcome.diffs().isEmpty() || !outcome.rolls().isEmpty();
                if (visible && firstFeedback < 0) {
                    firstFeedback = (System.nanoTime() - phaseStart) / 1_000_000;
                }

                if (!outcome.diffs().isEmpty()) {
                    sink.diffs(outcome.diffs());
                }
                // Pushing the rolls here is what starts the dice animating, which is the whole
                // reason the prose model is allowed to be slow.
                outcome.rolls().forEach(sink::roll);

                localRounds.add(DmClient.ChatMessage.toolResult(call.id(), outcome.message()));
            }
        }

        return new Mechanics(List.copyOf(results), calls, applied, rejected, firstFeedback);
    }

    // ---- Phase 2: narration ----

    private record Prose(String text, long firstTokenMs) {
    }

    /**
     * @param inferUnmarkedQuotes whether a quotation with no marker before it may be handed to the
     *                            one creature in the room. True only where the creature is the
     *                            only thing that can be talking. On a free-text turn it is not:
     *                            the narrator now writes the player's dialogue too, and guessing
     *                            wrong there puts the player's own taunt in the mouth — and the
     *                            colour, and the voice — of the thing they were taunting. Falling
     *                            back to the narrator is also wrong, but it is wrong in the way
     *                            that does not attribute words to a character who never said them.
     */
    private Prose runProsePhase(String text, List<String> mechanics, TurnSink sink,
                                boolean[] failed, boolean inferUnmarkedQuotes) {
        var narrated = new StringBuilder();
        var seed = inferUnmarkedQuotes ? soleCreature() : null;
        var parser = new NarrationParser(liveSpeakers(), seed, segment -> {
            narrated.append(segment.text());
            engine.log().append(Event.narration(segment.speakerId(), segment.text()));
            // The transcript, in the server log, one segment per line. The log recorded every
            // tool call, every timing and every rejection, and not one word of what was actually
            // said — so "the DM quoted its own prompt" and "there was an empty line from
            // Roderick" were reports that could only be reasoned about, never checked.
            log.info("| {} | {}", segment.speakerId(), segment.text().strip());
            sink.narration(segment);
        });

        long started = System.nanoTime();
        var firstTokenAt = new long[]{0};

        var listener = new DmClient.DmListener() {
            @Override
            public void onTextDelta(String delta) {
                if (firstTokenAt[0] == 0) {
                    firstTokenAt[0] = System.nanoTime();
                }
                parser.accept(delta);
            }

            @Override
            public void onError(Throwable error) {
                failed[0] = true;
                sink.error(error);
            }
        };

        var conversation = new ArrayList<DmClient.ChatMessage>();
        // World state is recomputed here: phase 1 may have spawned or revealed things.
        conversation.add(DmClient.ChatMessage.system(prosePrompt + "\n\n" + worldState(true)));
        conversation.addAll(window(history, WINDOW_TURNS));
        conversation.add(DmClient.ChatMessage.user(text));

        // The engine's rulings, not the model's tool calls — the prose model never made those,
        // and replaying them as tool_calls confuses models that did not emit them. Deliberately
        // plain prose with no brackets, headings or bullets. An earlier version wrapped this in
        // [square brackets] and the model started emitting its own bracketed stage directions,
        // which the voice then read aloud. Models imitate the shape of what you send them.
        //
        // The length limit is repeated here rather than left to the system prompt alone. It is
        // stated there and was being ignored — six sentences against a stated three — and this
        // is the message the model is actually answering. Length is not a style preference now
        // that a real voice reads it: three sentences is about fifteen seconds of audio and the
        // whole world waits behind it.
        var directive = new StringBuilder();
        if (!mechanics.isEmpty()) {
            directive.append("The engine has already resolved this action. Every line below "
                            + "happened, in this order. Narrate all of them as one continuous "
                            + "moment — a failed check followed by something else means the "
                            + "attempt failed AND the something else happened anyway.\n\n")
                    .append(String.join("\n", mechanics))
                    .append("\n\n");
        }
        // The failure this is aimed at, seen in full: late in a session a player sent something
        // close to what they had sent earlier, and the model replied with its own narration from
        // that earlier turn reproduced character for character — the lid grinding open and the
        // goblin scrambling out of it, read aloud again while that same goblin was standing in
        // the room mid-fight. With the whole transcript in context and no compaction (shortcut
        // #11), a repeated question gets the repeated answer.
        //
        // Said here rather than in the system prompt because it is only true on the turns where
        // it is true, and a standing "do not repeat yourself" is a rule the model has no way to
        // check itself against.
        if (isRepeat(engine.log(), text)) {
            directive.append("The player has tried something like this before. ");
            narrationAfter(engine.log(), text).ifPresent(earlier -> directive
                    .append("Last time you told them, out loud:\n\n\"")
                    .append(earlier.strip())
                    .append("\"\n\n"));
            directive.append("Do not tell it again. Narrate what is different now — the same "
                    + "action against a room that has changed, or the same obstacle refusing "
                    + "them a second time and what that costs.\n\n");
        }
        directive.append("Three sentences at most.");
        conversation.add(DmClient.ChatMessage.user(directive.toString()));

        // No tools in this phase: narration only.
        proseClient.streamTurn(conversation, null, listener);
        parser.finish();

        long firstTokenMs = firstTokenAt[0] == 0 ? -1 : (firstTokenAt[0] - started) / 1_000_000;
        return new Prose(narrated.toString(), firstTokenMs);
    }

    // ---- Phase 3: reconcile ----

    /**
     * Catches the board up to what the narrator just said.
     *
     * <p>Phases 1 and 2 run in that order for a latency reason — mechanics first so the dice are
     * already tumbling while the writer works. The cost is that the writer is the last to speak
     * and the first to be believed. Told the sarcophagus holds a goblin that "will come out
     * fighting", it wrote the lid grinding open and claws at the player's throat on a turn where
     * phase 1 had spawned nothing; the room stayed empty and the player walked around a creature
     * that had just attacked them. Stopping the narrator writing that is one fix, and it is the
     * one that keeps the DM honest and timid: a hostile that menaces for turn after turn while
     * nothing on the board moves is scenery, and the player learns they are safe.
     *
     * <p>So the other half. Dramatic judgement lives in the strong model and the verbs live in
     * the fast one, and this is the wire between them: the narration goes back to the mechanics
     * model, which is asked one question — <em>what has to become true for that to have been
     * true?</em> The narrator leads and the engine follows, which is the order a human DM works
     * in. They say it, then they move the miniature.
     *
     * <p>It still never adjudicates. There are no dice in this phase ({@link
     * ToolSchema#forReconcile}), every call is validated by the same dispatcher as any other, and
     * the engine can refuse — so the narrator gained the power to <em>propose</em> a spawn or a
     * fight, not the power to make one happen. Invariant #1 is untouched.
     *
     * <p>The latency is close to free. It starts once the prose has finished streaming, which is
     * the moment the client has fifteen seconds of speech queued and nothing to do but play it;
     * the diffs land in that same ordered queue and arrive as the sentence describing them
     * finishes. The player sees the goblin appear on the word "goblin".
     *
     * @return how many calls were applied
     */
    private int runReconcilePhase(String narration, List<String> alreadyDone, TurnSink sink) {
        // Combat is the engine's to run beat by beat, and a beat is narrated from facts it has
        // already decided. There is nothing to catch up to, and everything to get wrong.
        if (narration.isBlank() || engine.combat().isActive()) {
            return 0;
        }

        var tools = ToolSchema.forReconcile(engine);
        var conversation = new ArrayList<DmClient.ChatMessage>();
        conversation.add(DmClient.ChatMessage.system(reconcilePrompt + "\n\n" + worldState(false)));
        conversation.add(DmClient.ChatMessage.user(
                reconcilePrompt(narration, alreadyDone)));

        var failed = new boolean[]{false};
        var discard = new DmClient.DmListener() {
            @Override
            public void onTextDelta(String delta) {
            }

            @Override
            public void onError(Throwable error) {
                // Deliberately not surfaced to the player. The turn has already been narrated and
                // is already being spoken; a red toast now would report a failure they cannot see
                // over something they can already hear working.
                failed[0] = true;
                log.warn("reconcile pass failed, the board keeps what phase 1 gave it", error);
            }
        };

        int applied = 0;

        // Two rounds at most, and the second only to fix a rejection. Picking a square is the
        // one argument here that can be wrong for a reason the model could not see — the
        // narrator says the goblin is out of the sarcophagus, and the square next to it happens
        // to be where the player is standing. Without the retry that turn narrates a creature
        // into the room and then does not put one there, which is the exact bug this phase
        // exists to close.
        for (int round = 0; round < 2 && !failed[0]; round++) {
            var result = toolClient.streamTurn(conversation, tools, discard);
            if (failed[0] || !result.wantsTools()) {
                break;
            }

            conversation.add(
                    DmClient.ChatMessage.assistantToolCalls(result.text(), result.toolCalls()));

            boolean rejected = false;
            for (var call : result.toolCalls()) {
                // "Nothing needs to change" is the most common right answer here, and the model
                // keeps expressing it by calling a tool named `none`. Dispatched, that is a
                // rejection, and a rejection buys a retry round — so the cheapest turn in the
                // game was costing two round trips to say no twice. Screened out here instead,
                // where it means what it was meant to mean: no calls.
                if (!ToolSchema.allowedInReconcile(call.name())) {
                    log.info("reconcile declined to run {}({}) — reading it as 'nothing to change'",
                            call.name(), call.argumentsJson());
                    conversation.add(DmClient.ChatMessage.toolResult(call.id(), "No change made."));
                    continue;
                }

                var outcome = dispatcher.dispatch(call);
                engine.log().append(new Event.ToolCallIssued(Instant.now(), Phase.RECONCILE,
                        call.name(), call.argumentsJson(), outcome.ok(), outcome.message()));

                if (outcome.ok()) {
                    applied++;
                    log.info("reconcile applied {}({}) -> {}",
                            call.name(), call.argumentsJson(), outcome.message());
                } else {
                    rejected = true;
                    log.warn("reconcile rejected: {}({}) -> {}",
                            call.name(), call.argumentsJson(), outcome.message());
                }

                if (!outcome.diffs().isEmpty()) {
                    sink.diffs(outcome.diffs());
                }
                outcome.rolls().forEach(sink::roll);

                conversation.add(DmClient.ChatMessage.toolResult(call.id(), outcome.message()));
            }

            if (!rejected) {
                break;
            }
        }

        return applied;
    }

    /** What the reconcile model is actually answering. */
    static String reconcilePrompt(String narration, List<String> alreadyDone) {
        var sb = new StringBuilder();
        sb.append(alreadyDone.isEmpty()
                ? "The engine did nothing this turn.\n\n"
                : "The engine already did this, this turn:\n" + String.join("\n", alreadyDone)
                        + "\n\n");

        // The one Established lie the M2 gate produced. Turn 8 failed a wall search and this pass
        // wrote down "The stone perimeter is slick with damp lime and yields nothing to
        // searching"; turn 25 found an alcove in that perimeter. The engine was always going to
        // allow reveal_prop(alcove) — the fault is a failed check recorded as a fact about the
        // world instead of a fact about the search, so the next success walks into a hole the
        // projection dug for it. m2-evaluation.md §4.
        //
        // Said here rather than in dm-reconcile.md for the same reason the repeat directive is
        // said per turn: it is only true on the turns where it is true, and a standing rule about
        // absence is one the model has no way to check itself against.
        if (anyCheckFailed(alreadyDone)) {
            sb.append("A check failed this turn. That means the character **did not find** "
                    + "anything this pass — it does not mean there is nothing there. If you call "
                    + "`assert_fact`, record what they did and did not manage, never what the "
                    + "room does or does not contain. \"The lime is slick and their fingers "
                    + "found no seam\" is a fact. \"The wall holds nothing\" is a claim about a "
                    + "room you cannot see, and a later success will contradict it.\n\n");
        }
        // Quoted and labelled rather than handed over bare. Bare, the model reads it as the scene
        // continuing and answers it as a turn.
        sb.append("The narrator then told the player, out loud:\n\n\"")
                .append(narration.strip())
                .append("\"\n\nWhat must change on the board for that to be true? "
                        + "Call nothing if the answer is nothing.");
        return sb.toString();
    }

    /**
     * Whether any check the engine ran this turn came back a failure.
     *
     * <p>Read off the dispatcher's own messages, which end in the {@link dm.model.Outcome} name.
     * A string match rather than a structured signal because that is what this phase is handed —
     * worth revisiting if the reconcile pass ever takes the events instead.
     */
    private static boolean anyCheckFailed(List<String> alreadyDone) {
        return alreadyDone.stream().anyMatch(result ->
                result.contains(Outcome.FAILURE.name()) || result.contains(Outcome.CRIT_FAIL.name()));
    }

    // ---- Context ----

    /**
     * @param forProse the two models are told the same facts and given opposite permissions. Only
     *                 the mechanics model can change anything, so only it is invited to act on
     *                 what it reads here.
     */
    private String worldState(boolean forProse) {
        RoomDefinition room = engine.room();
        var revealed = engine.state().revealedHere();
        var sb = new StringBuilder();

        sb.append("# Current state\n\n");
        sb.append("## Room: ").append(room.name()).append("\n\n");
        sb.append(room.dmNotes().overview()).append("\n\n");
        sb.append(room.dmNotes().sensory()).append("\n\n");

        sb.append("## What the player can see\n\n");
        for (var prop : room.props()) {
            if (!prop.hidden() || revealed.contains(prop.id())) {
                sb.append("- `").append(prop.id()).append("` at (")
                        .append(prop.x()).append(",").append(prop.y()).append(")");
                // A prop the dresser skipped is still on the board, so it is still listed —
                // but with no description there is nothing to describe, and printing the
                // placeholder would hand the DM stage direction as prose.
                if (prop.description() != null && !prop.description().isBlank()) {
                    sb.append(" — ").append(prop.description());
                }
                sb.append("\n");
            }
        }

        var hidden = room.props().stream()
                .filter(p -> p.hidden() && !revealed.contains(p.id()))
                .toList();
        if (!hidden.isEmpty()) {
            sb.append("\n## Hidden — the player cannot see these yet\n\n");
            sb.append("Do not mention these unless the player finds them.\n\n");
            for (var prop : hidden) {
                sb.append("- `").append(prop.id()).append("` at (")
                        .append(prop.x()).append(",").append(prop.y()).append(")");
                if (prop.revealHint() != null && !prop.revealHint().isBlank()) {
                    sb.append(" — ").append(prop.revealHint());
                }
                sb.append(" Once revealed: ").append(prop.description()).append("\n");
            }
        }

        // A generated room has no crypt-specific notes, and a Secrets heading with nothing
        // under it is an invitation to invent one — so when neither note exists the whole
        // block, preamble included, is omitted.
        var sarcophagusNote = engine.state().find("goblin").isEmpty()
                ? room.dmNotes().theSarcophagus()
                : room.dmNotes().theSarcophagusOpened();
        var doorNote = room.dmNotes().theDoor();
        boolean anySecret = sarcophagusNote != null && !sarcophagusNote.isBlank()
                || doorNote != null && !doorNote.isBlank();
        if (anySecret) {
            sb.append("\n## Secrets you know and the player does not\n\n");
            // The one block that reads as a promise rather than a fact, and the narrator kept it.
            // Told a goblin is inside the sarcophagus and "will come out fighting", the prose
            // model wrote it climbing out — lid grinding open, hand on the rim, claws at the
            // player's throat — on a turn where the mechanics model had spawned nothing and
            // started no fight. Nothing appeared on the grid and the player kept walking around
            // an empty room that had just attacked them. It was not disobeying: its own prompt
            // used to describe tools it has never been given, so it believed writing a thing
            // was how a thing happens.
            if (forProse) {
                sb.append("This is background, not a cue. None of it becomes true because you "
                        + "narrate it: a creature is in the room when it is listed under Entities "
                        + "present, and at no other time.\n\n");
            }
            // A secret stops being one the moment it happens, but the facts underneath it do not
            // stop being true. The first version of this promised a goblin still inside the
            // sarcophagus who would come out fighting, restated every turn under a heading
            // saying the player has not seen it yet — so the DM re-introduced Vessk as a fresh
            // menace under the lid several turns after the player had killed him.
            //
            // Deleting it once he was out fixed that and caused the opposite: with no note at
            // all, the DM forgot the thing in the sarcophagus had *been* Vessk, kept the
            // "something inside" thread running, and invented a robed corpse it can never
            // spawn. So the note is swapped rather than dropped — the premise while it is
            // pending, and what is true afterwards once it is spent.
            //
            // The door note is neither: "the north door never opens" is a standing constraint
            // rather than a pending beat, and it is exactly as true on the last turn as on
            // the first.
            appendNote(sb, sarcophagusNote);
            appendNote(sb, doorNote);
        }

        sb.append("## Entities present\n\n");
        for (var entity : engine.state().entities().values()) {
            sb.append("- `").append(entity.id()).append("` — ").append(entity.name())
                    .append(", ").append(entity.hp()).append("/").append(entity.maxHp())
                    .append(" hp, at (").append(entity.x()).append(",").append(entity.y())
                    .append(")").append(entity.isPlayerControlled() ? " [the player]" : "")
                    .append(entity.isAlive() ? "" : " [dead]")
                    // What they look like and what they are holding. Props have carried their
                    // description here from the start and creatures never did, so the narrator
                    // was writing fights between two entities it knew nothing about beyond a
                    // name — observed giving the fighter's killing blow a scimitar, which is
                    // the goblin's kind of weapon and in fact nobody's: the fighter carries a
                    // longsword and the goblin a notched shortsword, and both facts were
                    // sitting unread in the content files.
                    .append(". ").append(engine.content().entity(entity.kind()).description())
                    .append("\n");
        }

        var combat = engine.combat();
        if (combat.isActive()) {
            var view = combat.view();
            sb.append("\n## The fight\n\n");
            sb.append("Round ").append(view.round()).append(". Initiative order: ")
                    .append(view.order().stream().map(Combatant::name)
                            .collect(Collectors.joining(", ")))
                    .append(".\n");
            sb.append("It is `").append(view.activeId()).append("`'s turn.\n");
            sb.append("Never narrate a turn that has not happened, and never state the order, "
                    + "the round number, or anyone's hit points. Those are on screen.\n");
        }

        // Deliberately a labelled block rather than the sentence this used to be. It is the last
        // thing before the model starts writing, and a plain trailing sentence is something it
        // can simply carry on — observed ending a piece of narration with "Mode: EXPLORATION
        // Position: (6,1)", which the voice then read aloud. Models imitate the shape of what
        // you send them, so the shape has to be obviously not prose.
        // A fact, not an instruction. What to do about it is in the tool prompt, because it is
        // the tool model that owns the verbs capable of changing anything.
        int stuck = engine.consecutiveFailedChecks();
        if (stuck > 0) {
            sb.append("\n## Momentum\n\n")
                    .append(stuck).append(stuck == 1 ? " check has" : " checks have")
                    .append(" failed in a row, with no success since.\n");
        }

        sb.append(established(engine.state()));

        sb.append("\n## Grid\n\n")
                .append("- size: ").append(room.width()).append("x").append(room.height())
                .append("\n- axes: x eastward, y northward")
                .append("\n- mode: ").append(engine.mode()).append("\n");

        return sb.toString();
    }

    /**
     * The Established block the models see. Empty when nothing has been asserted yet.
     *
     * <p>Package-private so the heading can be locked without standing a {@code DmService} up.
     */
    static String established(WorldState state) {
        var facts = state.factsHere();
        if (facts.isEmpty()) {
            return "";
        }
        var sb = new StringBuilder();
        sb.append("\n## Established\n\n");
        sb.append("Things you have already told the player are true in this room. They are "
                + "true. Do not contradict them and do not re-introduce them as new.\n\n");
        for (var fact : facts) {
            sb.append("- ").append(fact.text()).append("\n");
        }
        return sb.toString();
    }

    /**
     * A note that was never written produces no output at all — not the word "null", and not
     * the spacer that would have followed it. Generated rooms carry only {@code overview} and
     * {@code sensory}; the crypt-specific notes are Java null there, and {@code append} would
     * otherwise write the literal string into every prompt.
     */
    private static void appendNote(StringBuilder sb, String note) {
        if (note != null && !note.isBlank()) {
            sb.append(note).append("\n\n");
        }
    }

    /**
     * Forget the session: the transcript so far, and that the scene was ever opened.
     *
     * <p>Without the history a restarted game would still be answering the last one — the model
     * remembers a goblin it killed in a room that no longer contains it.
     */
    public void reset() {
        history.clear();
        opened.set(false);
    }

    /**
     * The one living creature in the room, or null when there is not exactly one.
     *
     * <p>Handed to the parser so an unmarked quotation can be attributed — but only on a combat
     * beat, where nobody else is talking. M0 has a single goblin, which is precisely the case
     * where the inference is safe when it is safe at all.
     */
    private String soleCreature() {
        var creatures = engine.state().entities().values().stream()
                .filter(e -> !e.isPlayerControlled() && e.isAlive())
                .map(e -> e.id().toLowerCase())
                .toList();
        return creatures.size() == 1 ? creatures.getFirst() : null;
    }

    /**
     * Whether the player has already asked for something much like this.
     *
     * <p>Reads the log rather than the transcript, so its reach does not shrink when the window
     * does. It is a bag-of-words comparison over short strings — the cost of scanning every turn
     * of a long session is nothing, and the alternative is detection that silently gets worse
     * the longer someone plays.
     *
     * <p>{@code PlayerSaid} is appended at the start of the turn, before this is consulted, so
     * the last event that equals {@code text} is this turn. A sentence is not a repeat of itself.
     */
    static boolean isRepeat(EventLog log, String text) {
        return earlierMatchingTurn(log, text).isPresent();
    }

    /**
     * The most recent earlier player line that this one is a retry of, if any.
     *
     * <p>{@code PlayerSaid} is appended at the start of the turn, before this is consulted, so
     * the last event that equals {@code text} is this turn and is skipped. "Last time you told
     * them" means the most recent remaining match, not the first time they ever asked.
     */
    static Optional<String> earlierMatchingTurn(EventLog log, String text) {
        var earlier = log.events().stream()
                .filter(Event.PlayerSaid.class::isInstance)
                .map(Event.PlayerSaid.class::cast)
                .map(Event.PlayerSaid::text)
                .toList();
        if (!earlier.isEmpty() && earlier.getLast().equals(text)) {
            earlier = earlier.subList(0, earlier.size() - 1);
        }
        for (String previous : earlier.reversed()) {
            if (resembles(text, previous)) {
                return Optional.of(previous);
            }
        }
        return Optional.empty();
    }

    /**
     * What the DM said on the turn that line opened.
     *
     * <p>Carried into the directive rather than referred to, because the turn being referred to
     * may have fallen out of the window — and a model told not to repeat a thing it cannot see
     * writes a fresh version of it instead. Spec §7a.
     *
     * <p>The candidate is resolved through {@code earlierMatchingTurn} first, so a paraphrased
     * retry still finds the turn it is repeating. An exact string that did not resemble anything
     * still matches by equality, as it did.
     */
    static Optional<String> narrationAfter(EventLog log, String earlierPlayerText) {
        var target = earlierMatchingTurn(log, earlierPlayerText).orElse(earlierPlayerText);
        var events = log.events();
        for (int i = 0; i < events.size(); i++) {
            if (events.get(i) instanceof Event.PlayerSaid said
                    && said.text().equals(target)) {
                return events.stream().skip(i + 1)
                        .takeWhile(e -> !(e instanceof Event.PlayerSaid))
                        .filter(Event.NarrationLogged.class::isInstance)
                        .map(Event.NarrationLogged.class::cast)
                        .map(Event.NarrationLogged::text)
                        .reduce((a, b) -> a + " " + b);
            }
        }
        return Optional.empty();
    }

    /** The most recent turns, verbatim. Spec §7: recency is what prose needs. */
    static List<DmClient.ChatMessage> window(List<DmClient.ChatMessage> history, int turns) {
        int keep = Math.min(history.size(), turns * 2);
        return List.copyOf(history.subList(history.size() - keep, history.size()));
    }

    /** Package-private so the threshold can be tested without a model or a session. */
    static boolean resemblesAny(String text, List<String> earlierInputs) {
        for (String previous : earlierInputs) {
            if (resembles(text, previous)) {
                return true;
            }
        }
        return false;
    }

    /** Two thirds of the shorter bag. The only bag-of-words comparison. */
    private static boolean resembles(String text, String previous) {
        var words = significantWords(text);
        if (words.size() < 2) {
            return false;
        }
        var earlier = significantWords(previous);
        if (earlier.isEmpty()) {
            return false;
        }
        var shared = new java.util.HashSet<>(words);
        shared.retainAll(earlier);
        // Loose enough to catch a reworded retry, tight enough that "I look at the door" and
        // "I look at the sarcophagus" stay different questions.
        return shared.size() * 3 >= Math.min(words.size(), earlier.size()) * 2;
    }

    /** Content words, lowercased. Everything a retry would keep and nothing it would not. */
    private static java.util.Set<String> significantWords(String text) {
        var words = new java.util.HashSet<String>();
        for (String word : text.toLowerCase().split("[^a-z]+")) {
            if (word.length() > 2 && !FILLER.contains(word)) {
                words.add(word);
            }
        }
        return words;
    }

    private static final java.util.Set<String> FILLER = java.util.Set.of(
            "the", "and", "but", "for", "with", "into", "onto", "out", "off", "try", "trying",
            "again", "then", "now", "get", "gets", "put", "puts", "this", "that", "there",
            "here", "one", "over", "back", "down", "any", "all", "some", "you", "your", "his",
            "her", "its", "our", "their");

    /**
     * Whether the player's own words might be about to appear in quotation marks.
     *
     * <p>Decides who an unmarked quotation belongs to. The narrator writes the player's dialogue
     * now, so on a turn where they said they shout something, a quotation could be either voice
     * and the narrator keeps it — guessing wrong there puts their taunt in the mouth, the colour
     * and the voice of the thing they were taunting. On a turn where they heaved at a stone lid,
     * it cannot be them, and the creature should get its line rather than having its arrival
     * read in the same measured voice that just described the room.
     *
     * <p>Crude on purpose, and crude in the safe direction: a false positive costs a creature's
     * line its voice, a false negative misattributes a real person's words.
     */
    static boolean soundsLikeSpeech(String text) {
        String lower = text.toLowerCase();
        if (lower.indexOf('"') >= 0 || lower.indexOf('\u201c') >= 0 || lower.indexOf('\'') >= 0) {
            return true;
        }
        for (String verb : SPEECH_VERBS) {
            if (lower.contains(verb)) {
                return true;
            }
        }
        return false;
    }

    /** Substrings, not words, so "shouts" and "shouting" come along without a stemmer. */
    private static final List<String> SPEECH_VERBS = List.of(
            "say", "said", "speak", "spoke", "talk", "tell", "told", "ask", "answer", "reply",
            "shout", "yell", "scream", "call out", "cry out", "whisper", "mutter", "taunt",
            "insult", "mock", "threaten", "warn", "beg", "plead", "greet", "hail", "bargain",
            "negotiate", "persuade", "convince", "lie to", "curse at", "swear at", "sing",
            "read aloud", "introduce myself", "name myself");

    /**
     * What the narration parser will honour in a {@code [[speaker]]} marker, mapped to the entity
     * id it means. Everything else falls back to the narrator.
     *
     * <p>Names as well as ids. The model is shown {@code `fighter` — Roderick} and writes whichever
     * of the two it feels like; an id-only lookup silently demoted half of those to narration.
     * Ids win a collision, so a creature cannot be renamed into somebody else's voice.
     */
    private Map<String, String> liveSpeakers() {
        var speakers = new java.util.HashMap<String, String>();
        // Creatures that can arrive this turn, not only ones already standing here. A creature's
        // best line is the one it arrives on — and on that turn it does not exist yet, because
        // the reconcile pass that puts it on the grid runs after the last word is parsed. Marked
        // [[goblin]], the line was falling back to the narrator every time. Still a closed set:
        // exactly what spawn_entity can produce, which is what the model was offered.
        for (var kind : ToolSchema.SPAWNABLE_KINDS) {
            speakers.put(kind.toLowerCase(), kind.toLowerCase());
        }
        for (var entity : engine.state().entities().values()) {
            speakers.putIfAbsent(entity.name().toLowerCase(), entity.id().toLowerCase());
        }
        for (var entity : engine.state().entities().values()) {
            speakers.put(entity.id().toLowerCase(), entity.id().toLowerCase());
        }
        return speakers;
    }
}
