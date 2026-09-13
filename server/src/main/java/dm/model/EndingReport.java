package dm.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * How a delve ended, the parts of its sentence, and the ledger. Shipped on
 * {@link SceneState} and {@link Diff.DelveEnded}. Spec §9, §10.
 *
 * <p>{@code hurt} is omitted on {@link Ending#PARTY_LOST}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record EndingReport(
        Ending ending,
        List<String> partyNames,
        String roomName,
        String objectiveName,
        int roomsEntered,
        int roomsInSite,
        int potionsUsed,
        int potionsBrought,
        int torchesUsed,
        int torchesBrought,
        int fights,
        ObjectiveFate objective,
        String hurt
) {
    public static final String OBJECTIVE_NAME = "reliquary";
}
