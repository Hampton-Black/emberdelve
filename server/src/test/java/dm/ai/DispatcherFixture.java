package dm.ai;

import dm.content.ContentLoader;
import dm.engine.GameEngine;
import dm.engine.RandomDiceRoller;
import dm.model.Event;
import dm.state.EventLog;

final class DispatcherFixture {

    private final EventLog log;
    private final GameEngine engine;
    private final ToolDispatcher dispatcher;

    private DispatcherFixture(EventLog log, GameEngine engine, ToolDispatcher dispatcher) {
        this.log = log;
        this.engine = engine;
        this.dispatcher = dispatcher;
    }

    static DispatcherFixture inCrypt() {
        var log = new EventLog();
        var engine = new GameEngine(new ContentLoader(), log, new RandomDiceRoller());
        engine.start();
        return new DispatcherFixture(log, engine, new ToolDispatcher(engine));
    }

    GameEngine engine() {
        return engine;
    }

    ToolDispatcher.Result dispatch(String name, String argumentsJson) {
        return dispatcher.dispatch(new DmClient.ToolCall("test", name, argumentsJson));
    }

    <T extends Event> T lastEvent(Class<T> type) {
        return log.events().stream()
                .filter(type::isInstance)
                .map(type::cast)
                .reduce((a, b) -> b)
                .orElseThrow(() -> new AssertionError("no " + type.getSimpleName() + " in the log"));
    }
}
