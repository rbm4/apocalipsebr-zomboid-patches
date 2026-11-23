package cloud.apocalipse.pz.agent.features.npc.brain;

import java.util.List;

/** Content port: resolve one announcement snapshot, without performing actions. */
@FunctionalInterface
public interface HumanAnnouncementProvider {
    HumanAnnouncementProvider NONE = List::of;
    List<String> lines();
}
