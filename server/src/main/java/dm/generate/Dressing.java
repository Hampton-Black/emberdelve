package dm.generate;

import java.util.Map;

/**
 * What the dress pass decided, after validation.
 *
 * @param propDescriptions keyed by prop id, and containing only ids the generator placed
 */
public record Dressing(
        String name,
        String overview,
        String sensory,
        Map<String, String> propDescriptions
) {
}
