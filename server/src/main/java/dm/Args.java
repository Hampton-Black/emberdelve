package dm;

import java.nio.file.Path;
import java.util.Optional;

/**
 * The command line, parsed once.
 *
 * <p>A record rather than three scattered loops in {@code main} because {@code --port} is now
 * load-bearing: the Godot client derives every URL it uses from one setting, and a port the
 * server misreads is a client that attaches to nothing with no error worth reading.
 */
public record Args(boolean demoMode, int port, Long generateSeed, Optional<Path> replay) {

    private static final int DEFAULT_PORT = 7070;

    public static Args parse(String[] args) {
        boolean demo = false;
        int port = DEFAULT_PORT;
        Long seed = null;
        Optional<Path> replay = Optional.empty();

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--demo" -> demo = true;
                case "--port" -> {
                    port = Integer.parseInt(value(args, i, "--port"));
                    i++;
                }
                case "--generate" -> {
                    seed = Long.parseLong(value(args, i, "--generate"));
                    i++;
                }
                case "--replay" -> {
                    replay = Optional.of(Path.of(value(args, i, "--replay")));
                    i++;
                }
                default -> { }
            }
        }

        return new Args(demo, port, seed, replay);
    }

    private static String value(String[] args, int at, String flag) {
        if (at + 1 >= args.length) {
            throw new IllegalArgumentException(flag + " needs a value");
        }
        return args[at + 1];
    }
}
